/* Copyright 2009 Vladimir Sch�fer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.saml.websso;

import org.joda.time.DateTime;
import org.opensaml.common.SAMLException;
import org.opensaml.common.SAMLObject;
import org.opensaml.common.binding.BasicSAMLMessageContext;
import org.opensaml.common.xml.SAMLConstants;
import org.opensaml.saml2.core.*;
import org.opensaml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.opensaml.security.MetadataCredentialResolver;
import org.opensaml.security.MetadataCriteria;
import org.opensaml.security.SAMLSignatureProfileValidator;
import org.opensaml.ws.transport.http.HTTPInTransport;
import org.opensaml.xml.Configuration;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.security.CriteriaSet;
import org.opensaml.xml.security.credential.UsageType;
import org.opensaml.xml.security.criteria.EntityIDCriteria;
import org.opensaml.xml.security.criteria.UsageCriteria;
import org.opensaml.xml.security.keyinfo.KeyInfoCredentialResolver;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.impl.ExplicitKeySignatureTrustEngine;
import org.opensaml.xml.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.AuthenticationException;
import org.springframework.security.BadCredentialsException;
import org.springframework.security.CredentialsExpiredException;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.security.saml.metadata.MetadataManager;
import org.springframework.security.saml.storage.SAMLMessageStorage;

import java.util.Date;
import java.util.List;

/**
 * Class is able to process Response objects returned from the IDP after SP initialized SSO or unsolicited
 * response from IDP. In case the response is correctly validated and no errors are found the SAMLCredential\
 * is created.
 *
 * @author Vladimir Sch�fer
 */
public class WebSSOProfileConsumerImpl implements WebSSOProfileConsumer {

    private final static Logger log = LoggerFactory.getLogger(WebSSOProfileConsumerImpl.class);

    /**
     * Maximum time from response creation when the message is deemed valid
     */
    private static int DEFAULT_RESPONSE_SKEW = 60;
    /**
     * Maximum time between assertion creation and current time when the assertion is usable
     */
    private static int MAX_ASSERTION_TIME = 3000;
    /**
     * Maximum time between user's authentication and current time
     */
    private static int MAX_AUTHENTICATION_TIME = 7200;

    /**
     * Trust engine used to verify SAML signatures
     */
    private ExplicitKeySignatureTrustEngine trustEngine;

    protected static final String BEARER_CONFIRMATION = "urn:oasis:names:tc:SAML:2.0:cm:bearer";

     /**
     * Initializes the authentication provider
     * @param metadata metadata manager
     * @throws MetadataProviderException error initializing the provider
     */
    public WebSSOProfileConsumerImpl(MetadataManager metadata) throws MetadataProviderException {
        MetadataCredentialResolver mdCredResolver = new MetadataCredentialResolver(metadata);
        KeyInfoCredentialResolver keyInfoCredResolver = Configuration.getGlobalSecurityConfiguration().getDefaultKeyInfoCredentialResolver();
        trustEngine = new ExplicitKeySignatureTrustEngine(mdCredResolver, keyInfoCredResolver);
    }

    /**
     * The inpuc context object must have set the properties related to the returned Response, which is validated
     * and in case no errors are found the SAMLCredentail is returned.
     * @param context context including response object
     * @return SAMLCredential with information about user
     * @throws SAMLException in case the response is invalid
     * @throws org.opensaml.xml.security.SecurityException in the signature on response can't be verified
     * @throws ValidationException in case the response structure is not conforming to the standard
     */
    public SAMLCredential processResponse(BasicSAMLMessageContext context, SAMLMessageStorage protocolCache) throws SAMLException, org.opensaml.xml.security.SecurityException, ValidationException {

        AuthnRequest request = null;
        SAMLObject message = context.getInboundSAMLMessage();

        // Verify type
        if (!(message instanceof Response)) {
            log.debug("Received response is not of a Response object type");
            throw new SAMLException("Error validating SAML response");
        }
        Response response = (Response) message;

        // Verify status
        if (!StatusCode.SUCCESS_URI.equals(response.getStatus().getStatusCode().getValue())) {
            String[] logMessage = new String[2];
            logMessage[0] = response.getStatus().getStatusCode().getValue();
            StatusMessage message1 = response.getStatus().getStatusMessage();
            if (message1 != null) {
                logMessage[1] = message1.getMessage();
            }
            log.debug("Received response has invalid status code", logMessage);
            throw new SAMLException("SAML status is not success code");
        }


        // Verify signature of the response if present
        if (response.getSignature() != null) {
            verifySignature(response.getSignature(), context.getPeerEntityId());
        }

        // Verify issue time
        DateTime time = response.getIssueInstant();
        if (!isDateTimeSkewValid(DEFAULT_RESPONSE_SKEW, time)) {
            log.debug("Response issue time is either too old or with date in the future");
            throw new SAMLException("Error validating SAML response");
        }

        // Verify response to field if present, set request if correct
        if (response.getInResponseTo() != null) {
            XMLObject xmlObject = protocolCache.retreiveMessage(response.getInResponseTo());
            if (xmlObject == null) {
                log.debug("InResponseToField doesn't correspond to sent message", response.getInResponseTo());
                throw new SAMLException("Error validating SAML response");
            } else if (xmlObject instanceof AuthnRequest) {
                request = (AuthnRequest) xmlObject;
            } else {
                log.debug("Sent request was of different type then received response", response.getInResponseTo());
                throw new SAMLException("Error validating SAML response");
            }
        }

        // Verify destination
        if (response.getDestination() != null) {
            SPSSODescriptor localDescriptor = (SPSSODescriptor) context.getLocalEntityRoleMetadata();

            // Check if destination is correct on this SP
            List<AssertionConsumerService> services = localDescriptor.getAssertionConsumerServices();
            boolean found = false;
            for (AssertionConsumerService service : services) {
                if (response.getDestination().equals(service.getLocation()) &&
                        context.getInboundSAMLProtocol().equals(service.getBinding())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                log.debug("Destination of the response was not the expected value", response.getDestination());
                throw new SAMLException("Error validating SAML response");
            }
        }

        // Verify issuer
        if (response.getIssuer() != null) {
            Issuer issuer = response.getIssuer();
            verifyIssuer(issuer, context);
        }

        Assertion subjectAssertion = null;

        // Verify assertions
        List<Assertion> assertionList = response.getAssertions();
        for (Assertion a : assertionList) {
            verifyAssertion(a, request, context);
            if (a.getAuthnStatements().size() > 0) {
                if (a.getSubject() != null && a.getSubject().getSubjectConfirmations() != null) {
                    for (SubjectConfirmation conf : a.getSubject().getSubjectConfirmations()) {
                        if (BEARER_CONFIRMATION.equals(conf.getMethod())) {
                            subjectAssertion = a;
                        }
                    }
                }
            }
        }

        // Make sure that at least one storage contains authentication statement and subject with bearer cofirmation
        if (subjectAssertion == null) {
            log.debug("Response doesn't contain authentication statement");
            throw new SAMLException("Error validating SAML response");
        }

        return new SAMLCredential(subjectAssertion.getSubject().getNameID(), subjectAssertion, context.getPeerEntityMetadata().getEntityID());
    }

    private void verifyAssertion(Assertion assertion, AuthnRequest request, BasicSAMLMessageContext context) throws AuthenticationException, SAMLException, org.opensaml.xml.security.SecurityException, ValidationException {
        // Verify storage time skew
        if (!isDateTimeSkewValid(MAX_ASSERTION_TIME, assertion.getIssueInstant())) {
            log.debug("Authentication statement is too old to be used", assertion.getIssueInstant());
            throw new CredentialsExpiredException("Users authentication credential is too old to be used");
        }

        // Verify validity of storage
        // Advice is ignored, core 574
        verifyIssuer(assertion.getIssuer(), context);
        verifyAssertionSignature(assertion.getSignature(), context);
        verifySubject(assertion.getSubject(), request, context);

        // Assertion with authentication statement must contain audience restriction
        if (assertion.getAuthnStatements().size() > 0) {
            verifyAssertionConditions(assertion.getConditions(), context, true);
            for (AuthnStatement statement : assertion.getAuthnStatements()) {
                verifyAuthenticationStatement(statement, context);
            }
        } else {
            verifyAssertionConditions(assertion.getConditions(), context, false);
        }
    }

    /**
     * Verifies validity of Subject element, only bearer confirmation is validated.
     * @param subject subject to validate
     * @param request request
     * @param context context
     * @throws SAMLException error validating the object
     */
    protected void verifySubject(Subject subject, AuthnRequest request, BasicSAMLMessageContext context) throws SAMLException {
        boolean confirmed = false;
        for (SubjectConfirmation confirmation : subject.getSubjectConfirmations()) {
            if (BEARER_CONFIRMATION.equals(confirmation.getMethod())) {

                SubjectConfirmationData data = confirmation.getSubjectConfirmationData();

                // Bearer must have confirmation 554
                if (data == null) {
                    log.debug("Assertion invalidated by missing confirmation data");
                    throw new SAMLException("SAML Assertion is invalid");
                }

                // Not before forbidden by core 558
                if (data.getNotBefore() != null) {
                    log.debug("Assertion contains not before in bearer confirmation, which is forbidden");
                    throw new SAMLException("SAML Assertion is invalid");
                }

                // Validate not on or after
                if (data.getNotOnOrAfter().isBeforeNow()) {
                    confirmed = false;
                    continue;
                }

                // Validate in response to
                if (request != null) {
                    if (data.getInResponseTo() == null) {
                        log.debug("Assertion invalidated by subject confirmation - missing inResponseTo field");
                        throw new SAMLException("SAML Assertion is invalid");
                    } else {
                        if (!data.getInResponseTo().equals(request.getID())) {
                            log.debug("Assertion invalidated by subject confirmation - invalid in response to");
                            throw new SAMLException("SAML Assertion is invalid");
                        }
                    }
                }

                // Validate recipient
                if (data.getRecipient() == null) {
                    log.debug("Assertion invalidated by subject confirmation - recipient is missing in bearer confirmation");
                    throw new SAMLException("SAML Assertion is invalid");
                } else {
                    SPSSODescriptor spssoDescriptor = (SPSSODescriptor) context.getLocalEntityRoleMetadata();
                    for (AssertionConsumerService service : spssoDescriptor.getAssertionConsumerServices()) {
                        if (context.getInboundSAMLProtocol().equals(service.getBinding()) && service.getLocation().equals(data.getRecipient())) {
                            confirmed = true;
                        }
                    }
                }
            }
            // Was the subject confirmed by this confirmation data? If so let's store the subject in context.
            if (confirmed) {
                context.setSubjectNameIdentifier(subject.getNameID());
                return;
            }
        }

        log.debug("Assertion invalidated by subject confirmation - can't be confirmed by bearer method");
        throw new SAMLException("SAML Assertion is invalid");
    }

    /**
     * Verifies signature of the assertion. In case signature is not present and SP required signatures in metadata
     * the exception is thrown.
     * @param signature signature to verify
     * @param context context
     * @throws SAMLException signature missing although required
     * @throws org.opensaml.xml.security.SecurityException signature can't be validated
     * @throws ValidationException signature is malformed
     */
    protected void verifyAssertionSignature(Signature signature, BasicSAMLMessageContext context) throws SAMLException, org.opensaml.xml.security.SecurityException, ValidationException {
        SPSSODescriptor roleMetadata = (SPSSODescriptor) context.getLocalEntityRoleMetadata();
        boolean wantSigned = roleMetadata.getWantAssertionsSigned();
        if (signature != null && wantSigned) {
            verifySignature(signature, context.getPeerEntityMetadata().getEntityID());
        } else if (wantSigned) {
            log.debug("Assertion must be signed, but is not");
            throw new SAMLException("SAML Assertion is invalid");
        }
    }

    protected void verifyIssuer(Issuer issuer, BasicSAMLMessageContext context) throws SAMLException {
        // Validat format of issuer
        if (issuer.getFormat() != null && !issuer.getFormat().equals(NameIDType.ENTITY)) {
            log.debug("Assertion invalidated by issuer type", issuer.getFormat());
            throw new SAMLException("SAML Assertion is invalid");
        }

        // Validate that issuer is expected peer entity
        if (!context.getPeerEntityMetadata().getEntityID().equals(issuer.getValue())) {
            log.debug("Assertion invalidated by unexpected issuer value", issuer.getValue());
            throw new SAMLException("SAML Assertion is invalid");
        }
    }

    protected void verifySignature(Signature signature, String IDPEntityID) throws org.opensaml.xml.security.SecurityException, ValidationException {
        SAMLSignatureProfileValidator validator = new SAMLSignatureProfileValidator();
        validator.validate(signature);
        CriteriaSet criteriaSet = new CriteriaSet();
        criteriaSet.add(new EntityIDCriteria(IDPEntityID));
        criteriaSet.add(new MetadataCriteria(IDPSSODescriptor.DEFAULT_ELEMENT_NAME, SAMLConstants.SAML20P_NS));
        criteriaSet.add(new UsageCriteria(UsageType.SIGNING));
        log.debug("Verifying signature", signature);
        trustEngine.validate(signature, criteriaSet);
    }

    protected void verifyAssertionConditions(Conditions conditions, BasicSAMLMessageContext context, boolean audienceRequired) throws SAMLException {
        // If no conditions are implied, storage is deemed valid
        if (conditions == null) {
            return;
        }

        if (conditions.getNotBefore() != null) {
            if (conditions.getNotBefore().isAfterNow()) {
                log.debug("Assertion is not yet valid, invalidated by condition notBefore", conditions.getNotBefore());
                throw new SAMLException("SAML response is not valid");
            }
        }
        if (conditions.getNotOnOrAfter() != null) {
            if (conditions.getNotOnOrAfter().isBeforeNow()) {
                log.debug("Assertion is no longer valid, invalidated by condition notOnOrAfter", conditions.getNotOnOrAfter());
                throw new SAMLException("SAML response is not valid");
            }
        }

        if (audienceRequired && conditions.getAudienceRestrictions().size() == 0) {
            log.debug("Assertion invalidated by missing audience restriction");
            throw new SAMLException("SAML response is not valid");
        }

        audience:
        for (AudienceRestriction rest : conditions.getAudienceRestrictions()) {
            if (rest.getAudiences().size() == 0) {
                log.debug("No audit audience specified for the assertion");
                throw new SAMLException("SAML response is invalid");
            }
            for (Audience aud : rest.getAudiences()) {
                if (context.getLocalEntityId().equals(aud.getAudienceURI())) {
                    continue audience;
                }
            }
            log.debug("Our entity is not the intended audience of the assertion");
            throw new SAMLException("SAML response is not intended for this entity");
        }

        /** ? BUG
         if (conditions.getConditions().size() > 0) {
         log.debug("Assertion contain not understood conditions");
         throw new SAMLException("SAML response is not valid");
         }
         */
    }

    protected void verifyAuthenticationStatement(AuthnStatement auth, BasicSAMLMessageContext context) throws AuthenticationException {
        // Validate that user wasn't authenticated too long time ago
        if (!isDateTimeSkewValid(MAX_AUTHENTICATION_TIME, auth.getAuthnInstant())) {
            log.debug("Authentication statement is too old to be used", auth.getAuthnInstant());
            throw new CredentialsExpiredException("Users authentication data is too old");
        }

        // Validate users session is still valid
        if (auth.getSessionNotOnOrAfter() != null && auth.getSessionNotOnOrAfter().isAfter(new Date().getTime())) {
            log.debug("Authentication session is not valid anymore", auth.getSessionNotOnOrAfter());
            throw new CredentialsExpiredException("Users authentication is expired");
        }

        if (auth.getSubjectLocality() != null) {
            HTTPInTransport httpInTransport = (HTTPInTransport) context.getInboundMessageTransport();
            if (auth.getSubjectLocality().getAddress() != null) {
                if (!httpInTransport.getPeerAddress().equals(auth.getSubjectLocality().getAddress())) {
                    throw new BadCredentialsException("User is accessing the service from invalid address");
                }
            }
        }
    }

    private boolean isDateTimeSkewValid(int skewInSec, DateTime time) {
        return time.isAfter(new Date().getTime() - skewInSec * 1000) && time.isBeforeNow();
    }

}
