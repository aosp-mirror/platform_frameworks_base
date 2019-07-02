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

import com.android.internal.annotations.VisibleForTesting;

import org.w3c.dom.Element;

import java.security.SecureRandom;
import java.security.cert.CertPath;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Parses and holds the XML file containing the list of THM public-key certificates and related
 * metadata.
 */
public final class CertXml {

    private static final String METADATA_NODE_TAG = "metadata";
    private static final String METADATA_SERIAL_NODE_TAG = "serial";
    private static final String ENDPOINT_CERT_LIST_TAG = "endpoints";
    private static final String ENDPOINT_CERT_ITEM_TAG = "cert";
    private static final String INTERMEDIATE_CERT_LIST_TAG = "intermediates";
    private static final String INTERMEDIATE_CERT_ITEM_TAG = "cert";

    private final long serial;
    private final List<X509Certificate> intermediateCerts;
    private final List<X509Certificate> endpointCerts;

    private CertXml(
            long serial,
            List<X509Certificate> intermediateCerts,
            List<X509Certificate> endpointCerts) {
        this.serial = serial;
        this.intermediateCerts = intermediateCerts;
        this.endpointCerts = endpointCerts;
    }

    /** Gets the serial number of the XML file containing public-key certificates. */
    public long getSerial() {
        return serial;
    }

    @VisibleForTesting
    List<X509Certificate> getAllIntermediateCerts() {
        return intermediateCerts;
    }

    @VisibleForTesting
    List<X509Certificate> getAllEndpointCerts() {
        return endpointCerts;
    }

    /**
     * Chooses a random endpoint certificate from the XML file, validates the chosen certificate,
     * and returns the certificate path including the chosen certificate if it is valid.
     *
     * @param trustedRoot the trusted root certificate
     * @return the certificate path including the chosen certificate if the certificate is valid
     * @throws CertValidationException if the chosen certificate cannot be validated based on the
     *                                 trusted root certificate
     */
    public CertPath getRandomEndpointCert(X509Certificate trustedRoot)
            throws CertValidationException {
        return getEndpointCert(
                new SecureRandom().nextInt(this.endpointCerts.size()),
                /*validationDate=*/ null,
                trustedRoot);
    }

    @VisibleForTesting
    CertPath getEndpointCert(
            int index, @Nullable Date validationDate, X509Certificate trustedRoot)
            throws CertValidationException {
        X509Certificate chosenCert = endpointCerts.get(index);
        return CertUtils.validateCert(validationDate, trustedRoot, intermediateCerts, chosenCert);
    }

    /**
     * Parses a byte array as the content of the XML file containing a list of endpoint
     * certificates.
     *
     * @param bytes the bytes of the XML file
     * @return a {@code CertXml} instance that contains the parsing result
     * @throws CertParsingException if any parsing error occurs
     */
    public static CertXml parse(byte[] bytes) throws CertParsingException {
        Element rootNode = CertUtils.getXmlRootNode(bytes);
        return new CertXml(
                parseSerial(rootNode),
                parseIntermediateCerts(rootNode),
                parseEndpointCerts(rootNode));
    }

    private static long parseSerial(Element rootNode) throws CertParsingException {
        List<String> contents =
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_EXACTLY_ONE,
                        rootNode,
                        METADATA_NODE_TAG,
                        METADATA_SERIAL_NODE_TAG);
        return Long.parseLong(contents.get(0));
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

    private static List<X509Certificate> parseEndpointCerts(Element rootNode)
            throws CertParsingException {
        List<String> contents =
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_AT_LEAST_ONE,
                        rootNode,
                        ENDPOINT_CERT_LIST_TAG,
                        ENDPOINT_CERT_ITEM_TAG);
        List<X509Certificate> res = new ArrayList<>();
        for (String content : contents) {
            res.add(CertUtils.decodeCert(CertUtils.decodeBase64(content)));
        }
        return Collections.unmodifiableList(res);
    }
}
