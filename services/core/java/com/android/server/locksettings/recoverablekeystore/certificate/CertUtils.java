/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.locksettings.recoverablekeystore.certificate;

import android.annotation.IntDef;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Utility functions related to parsing and validating public-key certificates. */
public final class CertUtils {

    private static final String CERT_FORMAT = "X.509";
    private static final String CERT_PATH_ALG = "PKIX";
    private static final String CERT_STORE_ALG = "Collection";
    private static final String SIGNATURE_ALG = "SHA256withRSA";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MUST_EXIST_UNENFORCED, MUST_EXIST_EXACTLY_ONE, MUST_EXIST_AT_LEAST_ONE})
    @interface MustExist {}
    static final int MUST_EXIST_UNENFORCED = 0;
    static final int MUST_EXIST_EXACTLY_ONE = 1;
    static final int MUST_EXIST_AT_LEAST_ONE = 2;

    private CertUtils() {}

    /**
     * Decodes a byte array containing an encoded X509 certificate.
     *
     * @param certBytes the byte array containing the encoded X509 certificate
     * @return the decoded X509 certificate
     * @throws CertParsingException if any parsing error occurs
     */
    static X509Certificate decodeCert(byte[] certBytes) throws CertParsingException {
        return decodeCert(new ByteArrayInputStream(certBytes));
    }

    /**
     * Decodes an X509 certificate from an {@code InputStream}.
     *
     * @param inStream the input stream containing the encoded X509 certificate
     * @return the decoded X509 certificate
     * @throws CertParsingException if any parsing error occurs
     */
    static X509Certificate decodeCert(InputStream inStream) throws CertParsingException {
        CertificateFactory certFactory;
        try {
            certFactory = CertificateFactory.getInstance(CERT_FORMAT);
        } catch (CertificateException e) {
            // Should not happen, as X.509 is mandatory for all providers.
            throw new RuntimeException(e);
        }
        try {
            return (X509Certificate) certFactory.generateCertificate(inStream);
        } catch (CertificateException e) {
            throw new CertParsingException(e);
        }
    }

    /**
     * Parses a byte array as the content of an XML file, and returns the root node of the XML file.
     *
     * @param xmlBytes the byte array that is the XML file content
     * @return the root node of the XML file
     * @throws CertParsingException if any parsing error occurs
     */
    static Element getXmlRootNode(byte[] xmlBytes) throws CertParsingException {
        try {
            Document document =
                    DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder()
                            .parse(new ByteArrayInputStream(xmlBytes));
            document.getDocumentElement().normalize();
            return document.getDocumentElement();
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new CertParsingException(e);
        }
    }

    /**
     * Gets the text contents of certain XML child nodes, given a XML root node and a list of tags
     * representing the path to locate the child nodes. The whitespaces and newlines in the text
     * contents are stripped away.
     *
     * <p>For example, the list of tags [tag1, tag2, tag3] represents the XML tree like the
     * following:
     *
     * <pre>
     *   <root>
     *     <tag1>
     *       <tag2>
     *         <tag3>abc</tag3>
     *         <tag3>def</tag3>
     *       </tag2>
     *     </tag1>
     *   <root>
     * </pre>
     *
     * @param mustExist whether and how many nodes must exist. If the number of child nodes does not
     *                  satisfy the requirement, CertParsingException will be thrown.
     * @param rootNode  the root node that serves as the starting point to locate the child nodes
     * @param nodeTags  the list of tags representing the relative path from the root node
     * @return a list of strings that are the text contents of the child nodes
     * @throws CertParsingException if any parsing error occurs
     */
    static List<String> getXmlNodeContents(@MustExist int mustExist, Element rootNode,
            String... nodeTags)
            throws CertParsingException {
        if (nodeTags.length == 0) {
            throw new CertParsingException("The tag list must not be empty");
        }

        // Go down through all the intermediate node tags (except the last tag for the leaf nodes).
        // Note that this implementation requires that at most one path exists for the given
        // intermediate node tags.
        Element parent = rootNode;
        for (int i = 0; i < nodeTags.length - 1; i++) {
            String tag = nodeTags[i];
            List<Element> children = getXmlDirectChildren(parent, tag);
            if ((children.size() == 0 && mustExist != MUST_EXIST_UNENFORCED)
                    || children.size() > 1) {
                throw new CertParsingException(
                        "The XML file must contain exactly one path with the tag " + tag);
            }
            if (children.size() == 0) {
                return new ArrayList<>();
            }
            parent = children.get(0);
        }

        // Then collect the contents of the leaf nodes.
        List<Element> leafs = getXmlDirectChildren(parent, nodeTags[nodeTags.length - 1]);
        if (mustExist == MUST_EXIST_EXACTLY_ONE && leafs.size() != 1) {
            throw new CertParsingException(
                    "The XML file must contain exactly one node with the path "
                            + String.join("/", nodeTags));
        }
        if (mustExist == MUST_EXIST_AT_LEAST_ONE && leafs.size() == 0) {
            throw new CertParsingException(
                    "The XML file must contain at least one node with the path "
                            + String.join("/", nodeTags));
        }
        List<String> result = new ArrayList<>();
        for (Element leaf : leafs) {
            // Remove whitespaces and newlines.
            result.add(leaf.getTextContent().replaceAll("\\s", ""));
        }
        return result;
    }

    /** Get the direct child nodes with a given tag. */
    private static List<Element> getXmlDirectChildren(Element parent, String tag) {
        // Cannot use Element.getElementsByTagName because it will return all descendant elements
        // with the tag name, i.e. not only the direct child nodes.
        List<Element> children = new ArrayList<>();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(tag)) {
                children.add((Element) node);
            }
        }
        return children;
    }

    /**
     * Decodes a base64-encoded string.
     *
     * @param str the base64-encoded string
     * @return the decoding decoding result
     * @throws CertParsingException if the input string is not a properly base64-encoded string
     */
    public static byte[] decodeBase64(String str) throws CertParsingException {
        try {
            return Base64.getDecoder().decode(str);
        } catch (IllegalArgumentException e) {
            throw new CertParsingException(e);
        }
    }

    /**
     * Verifies a public-key signature that is computed by RSA with SHA256.
     *
     * @param signerPublicKey the public key of the original signer
     * @param signature       the public-key signature
     * @param signedBytes     the bytes that have been signed
     * @throws CertValidationException if the signature verification fails
     */
    static void verifyRsaSha256Signature(
            PublicKey signerPublicKey, byte[] signature, byte[] signedBytes)
            throws CertValidationException {
        Signature verifier;
        try {
            verifier = Signature.getInstance(SIGNATURE_ALG);
        } catch (NoSuchAlgorithmException e) {
            // Should not happen, as SHA256withRSA is mandatory for all providers.
            throw new RuntimeException(e);
        }
        try {
            verifier.initVerify(signerPublicKey);
            verifier.update(signedBytes);
            if (!verifier.verify(signature)) {
                throw new CertValidationException("The signature is invalid");
            }
        } catch (InvalidKeyException | SignatureException e) {
            throw new CertValidationException(e);
        }
    }

    /**
     * Validates a leaf certificate, and returns the certificate path if the certificate is valid.
     * If the given validation date is null, the current date will be used.
     *
     * @param validationDate    the date for which the validity of the certificate should be
     *                          determined
     * @param trustedRoot       the certificate of the trusted root CA
     * @param intermediateCerts the list of certificates of possible intermediate CAs
     * @param leafCert          the leaf certificate that is to be validated
     * @return the certificate path if the leaf cert is valid
     * @throws CertValidationException if {@code leafCert} is invalid (e.g., is expired, or has
     *                                 invalid signature)
     */
    static CertPath validateCert(
            @Nullable Date validationDate,
            X509Certificate trustedRoot,
            List<X509Certificate> intermediateCerts,
            X509Certificate leafCert)
            throws CertValidationException {
        PKIXParameters pkixParams =
                buildPkixParams(validationDate, trustedRoot, intermediateCerts, leafCert);
        CertPath certPath = buildCertPath(pkixParams);

        CertPathValidator certPathValidator;
        try {
            certPathValidator = CertPathValidator.getInstance(CERT_PATH_ALG);
        } catch (NoSuchAlgorithmException e) {
            // Should not happen, as PKIX is mandatory for all providers.
            throw new RuntimeException(e);
        }
        try {
            certPathValidator.validate(certPath, pkixParams);
        } catch (CertPathValidatorException | InvalidAlgorithmParameterException e) {
            throw new CertValidationException(e);
        }
        return certPath;
    }

    /**
     * Validates a given {@code CertPath} against the trusted root certificate.
     *
     * @param trustedRoot the trusted root certificate
     * @param certPath the certificate path to be validated
     * @param validationDate use null for current time
     * @throws CertValidationException if the given certificate path is invalid, e.g., is expired,
     *                                 or does not have a valid signature
     */
    public static void validateCertPath(X509Certificate trustedRoot, CertPath certPath,
                @Nullable Date validationDate) throws CertValidationException {
        validateCertPath(validationDate, trustedRoot, certPath);
    }

    /**
     * Validates a given {@code CertPath} against a given {@code validationDate}. If the given
     * validation date is null, the current date will be used.
     */
    @VisibleForTesting
    static void validateCertPath(@Nullable Date validationDate, X509Certificate trustedRoot,
            CertPath certPath) throws CertValidationException {
        if (certPath.getCertificates().isEmpty()) {
            throw new CertValidationException("The given certificate path is empty");
        }
        if (!(certPath.getCertificates().get(0) instanceof X509Certificate)) {
            throw new CertValidationException(
                    "The given certificate path does not contain X509 certificates");
        }

        List<X509Certificate> certificates = (List<X509Certificate>) certPath.getCertificates();
        X509Certificate leafCert = certificates.get(0);
        List<X509Certificate> intermediateCerts =
                certificates.subList(/*fromIndex=*/ 1, certificates.size());

        validateCert(validationDate, trustedRoot, intermediateCerts, leafCert);
    }

    @VisibleForTesting
    static CertPath buildCertPath(PKIXParameters pkixParams) throws CertValidationException {
        CertPathBuilder certPathBuilder;
        try {
            certPathBuilder = CertPathBuilder.getInstance(CERT_PATH_ALG);
        } catch (NoSuchAlgorithmException e) {
            // Should not happen, as PKIX is mandatory for all providers.
            throw new RuntimeException(e);
        }
        try {
            return certPathBuilder.build(pkixParams).getCertPath();
        } catch (CertPathBuilderException | InvalidAlgorithmParameterException e) {
            throw new CertValidationException(e);
        }
    }

    @VisibleForTesting
    static PKIXParameters buildPkixParams(
            @Nullable Date validationDate,
            X509Certificate trustedRoot,
            List<X509Certificate> intermediateCerts,
            X509Certificate leafCert)
            throws CertValidationException {
        // Create a TrustAnchor from the trusted root certificate.
        Set<TrustAnchor> trustedAnchors = new HashSet<>();
        trustedAnchors.add(new TrustAnchor(trustedRoot, null));

        // Create a CertStore from the list of intermediate certificates.
        List<X509Certificate> certs = new ArrayList<>(intermediateCerts);
        certs.add(leafCert);
        CertStore certStore;
        try {
            certStore =
                    CertStore.getInstance(CERT_STORE_ALG, new CollectionCertStoreParameters(certs));
        } catch (NoSuchAlgorithmException e) {
            // Should not happen, as Collection is mandatory for all providers.
            throw new RuntimeException(e);
        } catch (InvalidAlgorithmParameterException e) {
            throw new CertValidationException(e);
        }

        // Create a CertSelector from the leaf certificate.
        X509CertSelector certSelector = new X509CertSelector();
        certSelector.setCertificate(leafCert);

        // Build a PKIXParameters from TrustAnchor, CertStore, and CertSelector.
        PKIXBuilderParameters pkixParams;
        try {
            pkixParams = new PKIXBuilderParameters(trustedAnchors, certSelector);
        } catch (InvalidAlgorithmParameterException e) {
            throw new CertValidationException(e);
        }
        pkixParams.addCertStore(certStore);

        // If validationDate is null, the current time will be used.
        pkixParams.setDate(validationDate);
        pkixParams.setRevocationEnabled(false);

        return pkixParams;
    }
}
