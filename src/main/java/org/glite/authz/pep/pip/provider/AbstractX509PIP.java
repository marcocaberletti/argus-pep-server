/*
 * Copyright (c) Members of the EGEE Collaboration. 2006-2010.
 * See http://www.eu-egee.org/partners/ for details on the copyright holders.
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

package org.glite.authz.pep.pip.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

import org.glite.authz.common.config.ConfigurationException;
import org.glite.authz.common.model.Attribute;
import org.glite.authz.common.model.Request;
import org.glite.authz.common.model.Subject;
import org.glite.authz.common.util.Strings;
import org.glite.authz.pep.pip.PIPException;
import org.glite.authz.pep.pip.PIPProcessingException;
import org.italiangrid.voms.VOMSAttribute;
import org.italiangrid.voms.ac.VOMSACValidator;
import org.italiangrid.voms.ac.VOMSValidationResult;
import org.italiangrid.voms.error.VOMSValidationErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.emi.security.authn.x509.ValidationError;
import eu.emi.security.authn.x509.ValidationResult;
import eu.emi.security.authn.x509.X509CertChainValidator;
import eu.emi.security.authn.x509.impl.CertificateUtils;
import eu.emi.security.authn.x509.impl.X500NameUtils;
import eu.emi.security.authn.x509.impl.CertificateUtils.Encoding;
import eu.emi.security.authn.x509.proxy.ProxyUtils;

/** Base class for PIPs which work with X.509 certificates. */
public abstract class AbstractX509PIP extends AbstractPolicyInformationPoint {

    /** Class logger. */
    private Logger log= LoggerFactory.getLogger(AbstractX509PIP.class);

    /**
     * Set to <code>false</code> to disable the X509 certificate to be embedded
     * in the Subject
     */
    private boolean requireCertificate= true;

    /**
     * Whether the given cert chain must contain a proxy certificate in order to
     * be valid.
     */
    private boolean requireProxyCertificate;

    /** Whether to perform PKIX validation on the incoming certificate. */
    private boolean performPKIXValidation;

    /** Whether VOMS AC support is currently enabled. */
    private boolean vomsSupportEnabled;

    /**
     * X509 cert chain validator for the subject's end entity certificate
     */
    private X509CertChainValidator certChainValidator;

    /**
     * Verifier used to validate an X.509 certificate chain which may, or may
     * not, include AC certs.
     */
    private VOMSACValidator vomsACValidator;

    /**
     * The constructor for this PIP. This constructor enables support for the
     * VOMS attribute certificates.
     * 
     * @param pipID
     *            ID of this PIP
     * @param requireProxy
     *            whether a subject's certificate chain must require a proxy in
     *            order to be valid
     * @param x509Validator
     *            the X.509 validator used to validate the subject's end entity
     *            certificate
     * @param vomsACValidator
     *            VOMS AC validator used to validate the subject's attribute
     *            certificate certificate, may be null of AC support is not
     *            desired
     * 
     * @throws ConfigurationException
     *             thrown if the configuration of the PIP fails
     */
    public AbstractX509PIP(String pipID, boolean requireProxy,
                           X509CertChainValidator x509Validator,
                           VOMSACValidator vomsACValidator)
                                                           throws ConfigurationException {
        super(pipID);

        requireProxyCertificate= requireProxy;

        if (x509Validator == null) {
            throw new ConfigurationException("Policy information point trust material may not be null");
        }

        if (vomsACValidator == null) {
            vomsSupportEnabled= false;
        }
        else {
            vomsSupportEnabled= true;
        }

        this.certChainValidator= x509Validator;
        this.vomsACValidator= vomsACValidator;
    }

    /**
     * Gets whether VOMS support is enabled.
     * 
     * @return whether VOMS support is enabled
     */
    public boolean isVOMSSupportEnabled() {
        return vomsSupportEnabled;
    }

    /**
     * Gets whether the PKIX validation is performed against the processed cert
     * chain.
     * 
     * @return whether the PKIX validation is performed against the processed
     *         cert chain
     */
    public boolean isPKIXValidationEnabled() {
        return performPKIXValidation;
    }

    /**
     * @return whether a proxy certificate is mandatory.
     */
    public boolean isProxyCertificateRequired() {
        return requireProxyCertificate;
    }

    /**
     * Sets whether the PKIX validation is performed against the processed cert
     * chain.
     * 
     * @param perform
     *            whether the PKIX validation is performed against the processed
     *            cert chain
     */
    public void performPKIXValidation(boolean perform) {
        performPKIXValidation= perform;
    }

    /**
     * Gets the X.509 certificate validator.
     * 
     * @return X.509 cert chain validator
     */
    public X509CertChainValidator getX509CertChainValidator() {
        return certChainValidator;
    }

    /**
     * @return the VOMS AC validator
     */
    protected VOMSACValidator getVOMSACValidator() {
        return vomsACValidator;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Shutdown the VOMS AC validator (if any).
     */
    public void stop() throws PIPException {
        super.stop();
        if (vomsACValidator != null) {
            log.debug("Shutdown VOMS AC validator...");
            vomsACValidator.shutdown();
            vomsACValidator= null;
        }
    }

    /** {@inheritDoc} */
    public boolean populateRequest(Request request)
            throws PIPProcessingException {
        if (!appliesToRequest(request)) {
            return false;
        }

        X509Certificate[] certChain;
        Collection<Attribute> certAttributes;
        for (Subject subject : request.getSubjects()) {
            log.debug("Extracting cert chain from Subject...");
            certChain= extractCertificateChain(subject);
            if (certChain == null) {
                continue;
            }
            // sort the cert chain starting from proxy/end-entity cert
            // needed???
            certChain= sortCertificateChain(certChain);

            // bug fix: complete cert chain up to trust anchor
            // it is now implemented in caNl validator, see ValidationResult.getValidChain()
            certChain= completeCertificateChain(certChain);
            
            if (log.isDebugEnabled()) {
                int i= 0;
                log.debug("before caNl validation:");
                for (X509Certificate cert : certChain) {
                    String certSubject= cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
                    log.debug("certChain[{}]: {}", i++, certSubject);
                }
            }

            if (isPKIXValidationEnabled()) {
                log.debug("Validating cert chain...");

                ValidationResult result= certChainValidator.validate(certChain);
                if (!result.isValid()) {
                    StringBuilder sb= new StringBuilder();
                    sb.append("PKIX validation failed: ");
                    boolean first= true;
                    for (ValidationError validationError : result.getErrors()) {
                        log.error(validationError.toString());
                        if (!first) {
                            sb.append(" | ");
                        }
                        String certName= X500NameUtils.getReadableForm(validationError.getChain()[validationError.getPosition()].getSubjectX500Principal());
                        sb.append(certName).append(": ");
                        sb.append(validationError.getMessage());
                        first= false;
                    }
                    throw new PIPProcessingException(sb.toString());
                }
                else {
                    // get full certchain from validator
                    List<X509Certificate> validChain= result.getValidChain();
                    certChain= validChain.toArray(new X509Certificate[validChain.size()]);
                }
            }

            if (log.isDebugEnabled()) {
                int i= 0;
                log.debug("after caNl validation:");
                for (X509Certificate cert : certChain) {
                    String certSubject= cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
                    log.debug("certChain[{}]: {}", i++, certSubject);
                }
            }

            X509Certificate userCert= ProxyUtils.getEndUserCertificate(certChain);

            log.debug("Extracting subject attributes from certificate with subject: {}", userCert.getSubjectX500Principal());
            certAttributes= processCertChain(userCert, certChain);
            if (certAttributes != null) {
                log.debug("Extracted subject attributes {} from certificate with subject {}", certAttributes, userCert.getSubjectX500Principal());
                updateSubjectCertificateAttributes(subject, certAttributes);
                return true;
            }
        }

        if (requireCertificate) {
            String errMsg= "Subject did not contain the required certificate chain in attribute: "
                    + getCertificateAttributeId()
                    + " datatype: "
                    + getCertificateAttributeDatatype();
            log.error(errMsg);
            throw new PIPProcessingException(errMsg);
        }
        else {
            log.debug("No certificate chain found, but requireCertificate="
                    + requireCertificate);
            return true;
        }
    }

    protected void setRequireCertificate(boolean required) {
        requireCertificate= required;
    }

    /**
     * Update the Subject certificate attributes (subject-id, subject-issuer,
     * ...) with the attributes given as parameter. If the subject already
     * contains the attributes, their respective values will be overwritten.
     * 
     * @param subject
     *            the subject to update
     * @param certAttributes
     *            the certificate attributes
     */
    private void updateSubjectCertificateAttributes(Subject subject,
                                                    Collection<Attribute> certAttributes) {
        for (Attribute certAttribute : certAttributes) {
            boolean alreadyExists= false;
            String certAttributeId= certAttribute.getId();
            String certAttributeDataType= certAttribute.getDataType();
            for (Attribute subjectAttribute : subject.getAttributes()) {
                if (subjectAttribute.getId().equals(certAttributeId)
                        && subjectAttribute.getDataType().equals(certAttributeDataType)) {
                    alreadyExists= true;
                    log.debug("Subject {} already contains values, replace them with {}", subjectAttribute, certAttribute);
                    subjectAttribute.getValues().clear();
                    subjectAttribute.getValues().addAll(certAttribute.getValues());
                }
            }
            if (!alreadyExists) {
                log.debug("Add {} to Subject", certAttribute);
                subject.getAttributes().add(certAttribute);
            }
        }
    }

    /**
     * Checks whether this PIP applies to this request.
     * 
     * @param request
     *            the incoming request to be checked
     * 
     * @return true if this PIP applies to the request, false if not
     */
    protected abstract boolean appliesToRequest(Request request);

    /**
     * Gets the certificate chain from the subject's attribute id and datatype
     * 
     * @param subject
     *            subject from which to extract the certificate chain
     * 
     * @return the extracted certificate chain or <code>null</code> if the
     *         subject did not contain a chain of X.509 version 3 certificates
     * 
     * @throws PIPProcessingException
     *             thrown if the subject contained more than one certificate
     *             chain or if the chain was not properly PEM encoded
     * 
     * @see #getCertificateAttributeId()
     * @see #getCertificateAttributeDatatype()
     */
    protected X509Certificate[] extractCertificateChain(Subject subject)
            throws PIPProcessingException {
        String pemCertChain= null;
        for (Attribute attribute : subject.getAttributes()) {
            // check attribute Id and datatype
            if (Strings.safeEquals(attribute.getId(), getCertificateAttributeId())
                    && Strings.safeEquals(attribute.getDataType(), getCertificateAttributeDatatype())) {
                if (pemCertChain != null || attribute.getValues().size() < 1) {
                    String errorMsg= "Subject contains more than one X509 certificate chain.";
                    log.error(errorMsg);
                    throw new PIPProcessingException(errorMsg);
                }

                if (attribute.getValues().size() == 1) {
                    pemCertChain= Strings.safeTrimOrNullString((String) attribute.getValues().iterator().next());
                }
            }
        }

        if (pemCertChain == null) {
            return null;
        }

        InputStream is= new ByteArrayInputStream(pemCertChain.getBytes());
        X509Certificate[] certChain= null;
        try {
            // loadCertificateChain also sort the chain!!!
            certChain= CertificateUtils.loadCertificateChain(is, Encoding.PEM);
        } catch (IOException e) {
            String error= "Failed to load certificate chain from Subject: " + e.getMessage();
            log.error(error);
            throw new PIPProcessingException(error, e);
        }

        boolean proxyPresent= false;
        for (X509Certificate cert : certChain) {
            if (cert.getVersion() < 3) {
                log.warn("Subject certificate {} is not a version 3, or greater, certificate, certificate chain ignored", cert.getSubjectX500Principal().getName(X500Principal.RFC2253));
                return null;
            }
            if (isProxyCertificateRequired() && ProxyUtils.isProxy(cert)) {
                proxyPresent= true;
            }
        }

        if (isProxyCertificateRequired() && !proxyPresent) {
            log.warn("Proxy is required, but none found");
            return null;
        }

        return certChain;
    }

    /**
     * Gets the ID of the Subject attribute which is expected to carry the
     * user's certificate.
     * 
     * @return ID of the Subject attribute which is expected to carry the user's
     *         certificate
     */
    protected abstract String getCertificateAttributeId();

    /**
     * Gets the datatype of the Subject attribute which is expected to carry the
     * user's certificate.
     * 
     * @return datatype of the Subject attribute which is expected to carry the
     *         user's certifica
     */
    protected abstract String getCertificateAttributeDatatype();

    /**
     * Processes one certificate chain and extract the information for the
     * subjects in the request.
     * 
     * @param endEntityCertificate
     *            end entity certificate for the subject currently being
     *            processed
     * @param certChain
     *            the certificate chain containing the end entity certificate
     *            from which information will be extracted
     * 
     * @return the attribute extracted from the certificate chain
     * 
     * @throws PIPProcessingException
     *             thrown if there is a problem reading the information from the
     *             certificate chain
     */
    protected abstract Collection<Attribute> processCertChain(X509Certificate endEntityCertificate,
                                                              X509Certificate[] certChain)
            throws PIPProcessingException;

    /**
     * Validates any VOMS attribute certificates within the cert chain and
     * extract the <b>first</b> attribute certificate from within.
     * 
     * @param certChain
     *            cert chain which may contain VOMS attribute certificates
     * 
     * @return the attributes extracted from the VOMS certificate or
     *         <code>null</code> if there were no valid attribute certificates
     * 
     * @throws PIPProcessingException
     *             thrown if there is more than one valid attribute certificate
     *             within the certificate chain
     */
    protected VOMSAttribute extractVOMSAttributeCertificate(X509Certificate[] certChain)
            throws PIPProcessingException {

        List<VOMSValidationResult> results= vomsACValidator.validateWithResult(certChain);

        if (results.isEmpty()) {
            log.warn("No VOMS attributes found in cert chain: {}", certChain[0].getSubjectX500Principal().getName(X500Principal.RFC2253));
            return null;
        }

        for (VOMSValidationResult result : results) {
            // return the first valid
            if (result.isValid()) {
                return result.getAttributes();
            }
            else {
                List<VOMSValidationErrorMessage> errorMessages= result.getValidationErrors();
                for (VOMSValidationErrorMessage errorMessage : errorMessages) {
                    log.error(errorMessage.getMessage());
                    throw new PIPProcessingException(errorMessage.getMessage());
                }
            }
        }
        log.error("Unable to extract VOMS attributes (this error should never occur)");
        return null;
    }

    /**
     * Sort the certificate chain by issuer.
     * 
     * @param certChain
     *            the certificate chain to sort
     * @return the sorted certificate chain
     * @throws PIPProcessingException
     *             if an error occurs
     */
    protected X509Certificate[] sortCertificateChain(X509Certificate[] certChain)
            throws PIPProcessingException {
        if (certChain.length == 0)
            return new X509Certificate[0];

        log.trace("sorting certificate chain...");

        List<X509Certificate> certificates= Arrays.asList(certChain);

        Map<X500Principal, X509Certificate> certsMapBySubject= new HashMap<X500Principal, X509Certificate>();
        // in this map root CA cert is not stored (as it has the same Issuer as
        // its direct child)
        Map<X500Principal, X509Certificate> certsMapByIssuer= new HashMap<X500Principal, X509Certificate>();
        for (X509Certificate c : certificates) {
            certsMapBySubject.put(c.getSubjectX500Principal(), c);
            if (!c.getIssuerX500Principal().equals(c.getSubjectX500Principal()))
                certsMapByIssuer.put(c.getIssuerX500Principal(), c);
        }

        // let's start from the random one (the 1st on the received list)
        List<X509Certificate> certsList= new LinkedList<X509Certificate>();
        X509Certificate current= certsMapBySubject.remove(certificates.get(0).getSubjectX500Principal());
        if (!current.getIssuerX500Principal().equals(current.getSubjectX500Principal())) {
            certsMapByIssuer.remove(current.getIssuerX500Principal());
        }
        certsList.add(current);

        // build path from current to root
        while (true) {
            X509Certificate parent= certsMapBySubject.remove(current.getIssuerX500Principal());
            if (parent != null) {
                certsMapByIssuer.remove(parent.getIssuerX500Principal());
                certsList.add(parent);
                current= parent;
            }
            else
                break;
        }

        // build path from the first on the list down to the user's certificate
        current= certsList.get(0);
        while (true) {
            X509Certificate child= certsMapByIssuer.remove(current.getSubjectX500Principal());
            if (child != null) {
                certsList.add(0, child);
                current= child;
            }
            else
                break;
        }

        if (certsMapByIssuer.size() > 0) {
            throw new PIPProcessingException("The certificate chain can not be sorted, it is inconsistent.");
        }

        return certsList.toArray(new X509Certificate[certsList.size()]);

    }

    /**
     * Tries to complete the certificate chain up to a trust anchor from the
     * eeTrustMaterial store. The cert chain MUST be sorted!!!
     * 
     * When PKIX validation is enabled (checked with
     * {@link #isPKIXValidationEnabled()} method), the method will throw a
     * {@link PIPProcessingException} if the chain can not be completed.
     * Otherwise, only a warning is logged.
     * 
     * @param certChain
     *            the sorted certificate chain to complete
     * @return the completed cert chain
     * @throws PIPProcessingException
     *             if PKIX validation is enabled and an error occurs while
     *             building the complete cert chain
     */
    protected X509Certificate[] completeCertificateChain(X509Certificate[] certChain) {
        log.debug("implemented in caNl validator...");
        return certChain;
    }
    /*
     * @SuppressWarnings("unchecked") protected X509Certificate[]
     * completeCertificateChain(X509Certificate[] certChain) throws
     * PIPProcessingException { if (certChain == null) { return null; } if
     * (certChain.length <= 1) { return certChain; } // get the trust anchors
     * store X509Certificate trustedIssers[]=
     * certChainValidator.getTrustedIssuers();
     * 
     * Vector<X509Certificate> certChainVector= new Vector<X509Certificate>();
     * 
     * // first cert is proxy or end-entity cert X509Certificate currentCert=
     * certChain[0]; certChainVector.add(currentCert);
     * 
     * // check the original cert chain
     * log.debug("Checking original certChain.length= {}", certChain.length);
     * for (int i= 1; i < certChain.length; i++) { if
     * (PKIUtils.checkIssued(certChain[i], certChain[i - 1])) { if
     * (log.isDebugEnabled()) { log.debug("checkIssued: YES: {} issued {}",
     * certChain[i].getSubjectX500Principal().getName(X500Principal.RFC2253),
     * certChain[i -
     * 1].getSubjectX500Principal().getName(X500Principal.RFC2253)); }
     * currentCert= certChain[i]; certChainVector.add(currentCert); } }
     * 
     * log.debug("is trust anchor? {}",
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253));
     * 
     * // check that currentCert is self signed and in ca store (trusted //
     * anchor). if (PKIUtils.selfIssued(currentCert)) { String hash=
     * PKIUtils.getHash(currentCert); Vector<X509Certificate> trustAnchors=
     * certificates.get(hash); if (trustAnchors == null ||
     * trustAnchors.indexOf(currentCert) == -1) { String errorMessage=
     * "Certificate " +
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253) +
     * " is self signed, but not a trust anchor"; if (isPKIXValidationEnabled())
     * { log.error("PKIX validation failed: " + errorMessage); throw new
     * PIPProcessingException(errorMessage); } else { log.warn(errorMessage); }
     * } else { log.debug("YES: {} is a valid trust anchor",
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253)); }
     * } else { log.debug("NO: searching trust anchor for {}",
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253)); //
     * and complete the certification path. do { // find trusted issuer String
     * hash= PKIUtils.getHash(currentCert.getIssuerX500Principal());
     * Vector<X509Certificate> issuers= certificates.get(hash); if
     * (log.isTraceEnabled()) { log.trace("Issuers({}): {}", hash, issuers); }
     * if (issuers != null) { for (X509Certificate issuer : issuers) { if
     * (PKIUtils.checkIssued(issuer, currentCert)) { if (log.isDebugEnabled()) {
     * log.debug("checkIssued: YES: {} issued {}",
     * issuer.getSubjectX500Principal().getName(X500Principal.RFC2253),
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253)); }
     * currentCert= issuer; certChainVector.add(currentCert); if
     * (log.isDebugEnabled()) { log.debug("currentCert: {}",
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253)); }
     * break; } } } else { String errorMessage=
     * "No trust anchor found for certificate " +
     * currentCert.getSubjectX500Principal().getName(X500Principal.RFC2253); if
     * (isPKIXValidationEnabled()) { log.error("PKIX validation failed: " +
     * errorMessage); throw new PIPProcessingException(errorMessage); } else {
     * log.warn(errorMessage); // exit while loop break; } } } while
     * (!PKIUtils.selfIssued(currentCert)); } // else
     * 
     * if (log.isTraceEnabled()) { int i= 0; for (X509Certificate cert :
     * certChainVector) { log.trace("completed chain[{}]: {}", i++,
     * cert.getSubjectX500Principal().getName(X500Principal.RFC2253)); } }
     * 
     * return certChainVector.toArray(new
     * X509Certificate[certChainVector.size()]); }
     */
}
