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

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class SigXmlTest {

    private byte[] certXmlBytes;
    private byte[] sigXmlBytes;

    @Before
    public void setUp() throws Exception {
        certXmlBytes = TestData.readTestFile("xml/valid-cert-file.xml");
        sigXmlBytes = TestData.readTestFile("xml/valid-sig-file.sig.xml");
    }

    @Test
    public void parseAndVerifyFileSignature_succeeds() throws Exception {
        SigXml sigXml = SigXml.parse(sigXmlBytes);
        sigXml.verifyFileSignature(
                TestData.ROOT_CA_TRUSTED, certXmlBytes, TestData.DATE_ALL_CERTS_VALID);
    }

    @Test
    public void parseAndVerifyFileSignature_throwsIfExpiredCert() throws Exception {
        SigXml sigXml = SigXml.parse(sigXmlBytes);
        assertThrows(
                CertValidationException.class,
                () ->
                        sigXml.verifyFileSignature(
                                TestData.ROOT_CA_TRUSTED, certXmlBytes,
                                TestData.DATE_INTERMEDIATE_CA_2_EXPIRED));
    }

    @Test
    public void parseAndVerifyFileSignature_throwsIfInvalidSignature() throws Exception {
        SigXml sigXml = SigXml.parse(sigXmlBytes);
        byte[] modifiedBytes = sigXmlBytes.clone();
        modifiedBytes[0] ^= (byte) 1; // Flip one bit
        CertValidationException expected =
                expectThrows(
                        CertValidationException.class,
                        () ->
                                sigXml.verifyFileSignature(
                                        TestData.ROOT_CA_TRUSTED, modifiedBytes,
                                        TestData.DATE_ALL_CERTS_VALID));
        assertThat(expected.getMessage()).contains("signature is invalid");
    }

    @Test
    public void parseAndVerifyFileSignature_throwsIfRootCertWithWrongCommonName() throws Exception {
        SigXml sigXml = SigXml.parse(sigXmlBytes);
        assertThrows(
                CertValidationException.class,
                () ->
                        sigXml.verifyFileSignature(
                                TestData.ROOT_CA_DIFFERENT_COMMON_NAME,
                                certXmlBytes,
                                TestData.DATE_ALL_CERTS_VALID));
    }

    @Test
    public void parse_succeedsWithoutIntermediateCerts() throws Exception {
        SigXml.parse(TestData.readTestFile("xml/valid-sig-file-no-intermediates.sig.xml"));
    }

    @Test
    public void parse_throwsIfNoSignerCert() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                SigXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-sig-file-no-signer-cert.sig.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfTwoSignerCerts() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                SigXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-sig-file-two-signer-certs.sig.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfNoSignatureValue() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                SigXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-sig-file-no-signature.sig.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }

    @Test
    public void parse_throwsIfTwoSignatureValues() throws Exception {
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                SigXml.parse(
                                        TestData.readTestFile(
                                                "xml/invalid-sig-file-two-signatures.sig.xml")));
        assertThat(expected.getMessage()).contains("exactly one");
    }
}
