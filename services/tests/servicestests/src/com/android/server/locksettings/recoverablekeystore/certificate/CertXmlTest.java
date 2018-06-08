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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.security.cert.CertPath;
import java.security.cert.X509Certificate;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class CertXmlTest {

    private byte[] certXmlBytes;

    @Before
    public void setUp() throws Exception {
        certXmlBytes = TestData.readTestFile("xml/valid-cert-file.xml");
    }

    @Test
    public void parse_succeeds() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        assertThat(certXml.getSerial()).isEqualTo(1000L);
        assertThat(certXml.getRefreshInterval()).isEqualTo(2592000L);
    }

    @Test
    public void parse_succeedsIfNoIntermediateCerts() throws Exception {
        CertXml certXml =
                CertXml.parse(TestData.readTestFile("xml/valid-cert-file-no-intermediates.xml"));
        assertThat(certXml.getAllIntermediateCerts()).isEmpty();
    }

    @Test
    public void parse_checkIntermediateCerts() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        List<X509Certificate> intermediateCerts = certXml.getAllIntermediateCerts();
        assertThat(intermediateCerts)
                .containsExactly(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2)
                .inOrder();
    }

    @Test
    public void parse_checkEndpointCerts() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        List<X509Certificate> endpointCerts = certXml.getAllEndpointCerts();
        assertThat(endpointCerts).hasSize(3);
        assertThat(endpointCerts).containsAllOf(TestData.LEAF_CERT_1, TestData.LEAF_CERT_2);
    }

    @Test
    public void parse_throwsIfNoEndpointCert() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-cert-file-no-endpoint-cert.xml")));
        assertThat(expected.getMessage()).contains("at least one");
    }

    @Test
    public void parse_throwsIfNoRefreshInterval() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-cert-file-no-refresh-interval.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfNoSerial() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-cert-file-no-serial.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfTwoRefreshIntervals() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-cert-file-two-refresh-intervals"
                                                        + ".xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfTwoSerials() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-cert-file-two-serials.xml")));
        assertThat(expected.getMessage()).contains("exactly one node");
    }

    @Test
    public void parseAndValidateAllCerts_succeeds() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        for (int index = 0; index < certXml.getAllEndpointCerts().size(); index++) {
            assertThat(
                    certXml.getEndpointCert(
                            index, TestData.DATE_ALL_CERTS_VALID, TestData.ROOT_CA_TRUSTED))
                    .isNotNull();
        }
    }

    @Test
    public void parseAndValidate_returnsExpectedCertPath() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        CertPath certPath =
                certXml.getEndpointCert(
                        /*index=*/ 1, // TestData.LEAF_CERT_2
                        TestData.DATE_ALL_CERTS_VALID,
                        TestData.ROOT_CA_TRUSTED);
        assertThat(certPath.getCertificates())
                .containsExactly(
                        TestData.LEAF_CERT_2, TestData.INTERMEDIATE_CA_2,
                        TestData.INTERMEDIATE_CA_1)
                .inOrder();
    }

    @Test
    public void validateCert_throwsIfRootCertWithDifferentCommonName() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        assertThrows(
                CertValidationException.class,
                () ->
                        certXml.getEndpointCert(
                                /*index=*/ 0, // TestData.LEAF_CERT_1
                                TestData.DATE_ALL_CERTS_VALID,
                                TestData.ROOT_CA_DIFFERENT_COMMON_NAME));
    }

    @Test
    public void validateCert_throwsIfRootCertWithDifferentPublicKey() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        assertThrows(
                CertValidationException.class,
                () ->
                        certXml.getEndpointCert(
                                /*index=*/ 0, // TestData.LEAF_CERT_1
                                TestData.DATE_ALL_CERTS_VALID,
                                TestData.ROOT_CA_DIFFERENT_KEY));
    }

    @Test
    public void validateCert_throwsIfExpired() throws Exception {
        CertXml certXml = CertXml.parse(certXmlBytes);
        assertThrows(
                CertValidationException.class,
                () ->
                        certXml.getEndpointCert(
                                /*index=*/ 1, // TestData.LEAF_CERT_2
                                TestData.DATE_LEAF_CERT_2_EXPIRED,
                                TestData.ROOT_CA_TRUSTED));
    }
}
