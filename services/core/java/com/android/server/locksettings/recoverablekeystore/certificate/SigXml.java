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

import android.annotation.Nullable;

import org.w3c.dom.Element;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Parses and holds the XML file containing the signature of the XML file containing the list of THM
 * public-key certificates.
 */
public final class SigXml {

    private static final String INTERMEDIATE_CERT_LIST_TAG = "intermediates";
    private static final String INTERMEDIATE_CERT_ITEM_TAG = "cert";
    private static final String SIGNER_CERT_NODE_TAG = "certificate";
    private static final String SIGNATURE_NODE_TAG = "value";

    private final List<X509Certificate> intermediateCerts;
    private final X509Certificate signerCert;
    private final byte[] signature;

    private SigXml(
            List<X509Certificate> intermediateCerts, X509Certificate signerCert, byte[] signature) {
        this.intermediateCerts = intermediateCerts;
        this.signerCert = signerCert;
        this.signature = signature;
    }

    /**
     * Verifies the signature contained in this XML file against a trusted root certificate and the
     * binary content of another file. The signer's public-key certificate and possible intermediate
     * CA certificates are included in this XML file, and will be validated against the trusted root
     * certificate.
     *
     * @param trustedRoot     the trusted root certificate
     * @param signedFileBytes the original file content that has been signed
     * @param validationDate use null for current time
     *
     * @throws CertValidationException if the signature verification fails, or the signer's
     *                                 certificate contained in this XML file cannot be validated
     *                                 based on the trusted root certificate
     */
    public void verifyFileSignature(
            X509Certificate trustedRoot, byte[] signedFileBytes, @Nullable Date validationDate)
            throws CertValidationException {
        CertUtils.validateCert(validationDate, trustedRoot, intermediateCerts, signerCert);
        CertUtils.verifyRsaSha256Signature(signerCert.getPublicKey(), signature, signedFileBytes);
    }

    /**
     * Parses a byte array as the content of the XML file containing the signature and related
     * certificates.
     *
     * @param bytes the bytes of the XML file
     * @return a {@code SigXml} instance that contains the parsing result
     * @throws CertParsingException if any parsing error occurs
     */
    public static SigXml parse(byte[] bytes) throws CertParsingException {
        Element rootNode = CertUtils.getXmlRootNode(bytes);
        return new SigXml(
                parseIntermediateCerts(rootNode), parseSignerCert(rootNode),
                parseFileSignature(rootNode));
    }

    private static List<X509Certificate> parseIntermediateCerts(Element rootNode)
            throws CertParsingException {
        List<String> contents =
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_UNENFORCED,
                        rootNode,
                        INTERMEDIATE_CERT_LIST_TAG,
                        INTERMEDIATE_CERT_ITEM_TAG);
        List<X509Certificate> res = new ArrayList<>();
        for (String content : contents) {
            res.add(CertUtils.decodeCert(CertUtils.decodeBase64(content)));
        }
        return Collections.unmodifiableList(res);
    }

    private static X509Certificate parseSignerCert(Element rootNode) throws CertParsingException {
        List<String> contents =
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_EXACTLY_ONE, rootNode, SIGNER_CERT_NODE_TAG);
        return CertUtils.decodeCert(CertUtils.decodeBase64(contents.get(0)));
    }

    private static byte[] parseFileSignature(Element rootNode) throws CertParsingException {
        List<String> contents =
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_EXACTLY_ONE, rootNode, SIGNATURE_NODE_TAG);
        return CertUtils.decodeBase64(contents.get(0));
    }
}
