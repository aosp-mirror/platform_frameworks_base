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

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Element;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class CertUtilsTest {

    private static final String XML_STR = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<!-- comment 1 -->"
            + "<root>\n\n\n\r\r\r"
            + "  <node1>\r\n\r\n"
            + "    node1-1</node1>"
            + "  <!-- comment 2 -->"
            + "  <node1>node1-2"
            + "    \n\r\n\r</node1>"
            + "  <node2>"
            + "    <node1>   node2-node1-1</node1>"
            + "    <node1>node2-node1-2   </node1>"
            + "    <!-- comment 3 -->"
            + "    <node1>   node2-node1-3   </node1>"
            + "  </node2>"
            + "</root>";

    private static final String SIGNED_STR = "abcdefg\n";
    private static final String SIGNATURE_BASE64 = ""
            + "KxBt9B3pwL3/59SrjTJTpuhc9JRxLOUNwNr3J4EEdXj5BqkYOUeXIOjyBGp8XaOnmuW8WmBxhko3"
            + "yTR3/M9x0/pJuKDgqQSSFG+I56O/IAri7DmMBfY8QqcgiF8RaR86G7mWXUIdu8ixEtpKa//T4bN7"
            + "c8Txvt96ApAcW0wJDihfCqDEXyi56pFCp+qEZuL4fS8iZtZTUkvxim1tb2/IsZ9OyDd9BWxp+JTs"
            + "zihzH6xqnUCa1aELSUZnU8OzWGeuKpVDQDbDMtQpcxJ9o+6L6wO5vmQutZAulgw5gRPGhYWVs8+0"
            + "ATdNEbv8TSomkXkZ3/lMYnmPXKmaHxcP4330DA==";
    private static final PublicKey SIGNER_PUBLIC_KEY = TestData.INTERMEDIATE_CA_2.getPublicKey();

    @Test
    public void decodeCert_readPemFile_succeeds_singleBlock() throws Exception {
        InputStream f = TestData.openTestFile("pem/valid-cert.pem");
        X509Certificate cert = CertUtils.decodeCert(f);
        assertThat(cert).isEqualTo(TestData.ROOT_CA_TRUSTED);
    }

    @Test
    public void decodeCert_readPemFile_succeeds_multipleBlocks() throws Exception {
        InputStream in = TestData.openTestFile("pem/valid-cert-multiple-blocks.pem");
        X509Certificate cert = CertUtils.decodeCert(in);
        assertThat(cert).isEqualTo(TestData.ROOT_CA_TRUSTED);
    }

    @Test
    public void decodeCert_readPemFile_throwsIfNoBeginEndLines() throws Exception {
        InputStream in = TestData.openTestFile("pem/invalid-cert-1-no-begin-end.pem");
        assertThrows(CertParsingException.class, () -> CertUtils.decodeCert(in));
    }

    @Test
    public void decodeCert_readPemFile_throwsIfEmptyBlock() throws Exception {
        InputStream in = TestData.openTestFile("pem/invalid-cert-2-empty-block.pem");
        assertThrows(CertParsingException.class, () -> CertUtils.decodeCert(in));
    }

    @Test
    public void decodeCert_readPemFile_throwsIfInvalidCert() throws Exception {
        InputStream in = TestData.openTestFile("pem/invalid-cert-3-invalid-key.pem");
        assertThrows(CertParsingException.class, () -> CertUtils.decodeCert(in));
    }

    @Test
    public void decodeCert_readBytes_succeeds() throws Exception {
        X509Certificate cert = CertUtils.decodeCert(TestData.INTERMEDIATE_CA_2.getEncoded());
        assertThat(cert.getIssuerX500Principal().getName())
                .isEqualTo("CN=Google CryptAuthVault Intermediate");
    }

    @Test
    public void decodeCert_readBytes_throwsIfInvalidCert() throws Exception {
        byte[] modifiedCertBytes = TestData.INTERMEDIATE_CA_1.getEncoded();
        modifiedCertBytes[0] ^= (byte) 1;
        assertThrows(CertParsingException.class, () -> CertUtils.decodeCert(modifiedCertBytes));
    }

    @Test
    public void decodeBase64_succeeds() throws Exception {
        assertThat(CertUtils.decodeBase64("VEVTVA==")).isEqualTo("TEST".getBytes(UTF_8));
    }

    @Test
    public void decodeBase64_succeedsIfEmptyInput() throws Exception {
        assertThat(CertUtils.decodeBase64("")).hasLength(0);
    }

    @Test
    public void decodeBase64_throwsIfInvalidInput() throws Exception {
        assertThrows(CertParsingException.class, () -> CertUtils.decodeBase64("EVTVA=="));
    }

    @Test
    public void getXmlRootNode_succeeds() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        assertThat(root.getTagName()).isEqualTo("root");
    }

    @Test
    public void getXmlRootNode_throwsIfEmptyInput() throws Exception {
        assertThrows(CertParsingException.class, () -> CertUtils.getXmlRootNode(new byte[0]));
    }

    @Test
    public void getXmlNodeContents_singleLevel_succeeds() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        assertThat(CertUtils.getXmlNodeContents(CertUtils.MUST_EXIST_UNENFORCED, root, "node1"))
                .containsExactly("node1-1", "node1-2");
    }

    @Test
    public void getXmlNodeContents_multipleLevels_succeeds() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        assertThat(CertUtils.getXmlNodeContents(CertUtils.MUST_EXIST_UNENFORCED, root, "node2", "node1"))
                .containsExactly("node2-node1-1", "node2-node1-2", "node2-node1-3");
    }

    @Test
    public void getXmlNodeContents_mustExistFalse_succeedsIfNotExist() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        assertThat(
                CertUtils.getXmlNodeContents(
                        CertUtils.MUST_EXIST_UNENFORCED, root, "node2", "node-not-exist"))
                .isEmpty();
    }

    @Test
    public void getXmlNodeContents_mustExistAtLeastOne_throwsIfNotExist() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertUtils.getXmlNodeContents(
                                        CertUtils.MUST_EXIST_AT_LEAST_ONE, root, "node2",
                                        "node-not-exist"));
        assertThat(expected.getMessage()).contains("must contain at least one");
    }

    @Test
    public void getXmlNodeContents_mustExistExactlyOne_throwsIfNotExist() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertUtils.getXmlNodeContents(
                                        CertUtils.MUST_EXIST_EXACTLY_ONE, root, "node-not-exist",
                                        "node1"));
        assertThat(expected.getMessage()).contains("must contain exactly one");
    }

    @Test
    public void getXmlNodeContents_mustExistExactlyOne_throwsIfMultipleExist() throws Exception {
        Element root = CertUtils.getXmlRootNode(XML_STR.getBytes(UTF_8));
        CertParsingException expected =
                expectThrows(
                        CertParsingException.class,
                        () ->
                                CertUtils.getXmlNodeContents(
                                        CertUtils.MUST_EXIST_EXACTLY_ONE, root, "node2", "node1"));
        assertThat(expected.getMessage()).contains("must contain exactly one");
    }

    @Test
    public void verifyRsaSha256Signature_succeeds() throws Exception {
        CertUtils.verifyRsaSha256Signature(
                SIGNER_PUBLIC_KEY,
                Base64.getDecoder().decode(SIGNATURE_BASE64),
                SIGNED_STR.getBytes(UTF_8));
    }

    @Test
    public void verifyRsaSha256Signature_throwsIfMismatchSignature() throws Exception {
        byte[] modifiedBytes = SIGNED_STR.getBytes(UTF_8);
        modifiedBytes[0] ^= (byte) 1;
        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.verifyRsaSha256Signature(
                                SIGNER_PUBLIC_KEY, Base64.getDecoder().decode(SIGNATURE_BASE64),
                                modifiedBytes));
    }

    @Test
    public void verifyRsaSha256Signature_throwsIfWrongKeyType() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"));
        PublicKey publicKey = keyPairGenerator.generateKeyPair().getPublic();
        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.verifyRsaSha256Signature(
                                publicKey,
                                Base64.getDecoder().decode(SIGNATURE_BASE64),
                                SIGNED_STR.getBytes(UTF_8)));
    }

    @Test
    public void buildCertPath_succeedsWithoutIntermediates() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        X509Certificate leafCert = TestData.INTERMEDIATE_CA_1;
        CertPath certPath = CertUtils.buildCertPath(
                CertUtils.buildPkixParams(
                        TestData.DATE_ALL_CERTS_VALID, rootCert, Collections.emptyList(),
                        leafCert));
        assertThat(certPath.getCertificates()).containsExactly(
                TestData.INTERMEDIATE_CA_1).inOrder();
    }

    @Test
    public void buildCertPath_succeedsWithIntermediates() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_2;
        CertPath certPath =
                CertUtils.buildCertPath(
                        CertUtils.buildPkixParams(
                                TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                leafCert));
        assertThat(certPath.getCertificates())
                .containsExactly(
                        TestData.LEAF_CERT_2, TestData.INTERMEDIATE_CA_2,
                        TestData.INTERMEDIATE_CA_1)
                .inOrder();
    }

    @Test
    public void buildCertPath_succeedsWithIntermediates_ignoreUnrelatedIntermedateCert()
            throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_1;
        CertPath certPath =
                CertUtils.buildCertPath(
                        CertUtils.buildPkixParams(
                                TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                leafCert));
        assertThat(certPath.getCertificates())
                .containsExactly(TestData.LEAF_CERT_1, TestData.INTERMEDIATE_CA_1)
                .inOrder();
    }

    @Test
    public void buildCertPath_throwsIfWrongRootCommonName() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_DIFFERENT_COMMON_NAME;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_1;

        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.buildCertPath(
                                CertUtils.buildPkixParams(
                                        TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                        leafCert)));
    }

    @Test
    public void buildCertPath_throwsIfMissingIntermediateCert() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_DIFFERENT_COMMON_NAME;
        List<X509Certificate> intermediateCerts = Collections.emptyList();
        X509Certificate leafCert = TestData.LEAF_CERT_1;

        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.buildCertPath(
                                CertUtils.buildPkixParams(
                                        TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                        leafCert)));
    }

    @Test
    public void validateCertPath_succeeds() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_2;
        CertPath certPath =
                CertUtils.buildCertPath(
                        CertUtils.buildPkixParams(
                                TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                leafCert));
        CertUtils.validateCertPath(
                TestData.DATE_ALL_CERTS_VALID, TestData.ROOT_CA_TRUSTED, certPath);
    }

    @Test
    public void validateCertPath_throwsIfEmptyCertPath() throws Exception {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        CertPath emptyCertPath = certFactory.generateCertPath(new ArrayList<X509Certificate>());
        CertValidationException expected =
                expectThrows(
                        CertValidationException.class,
                        () -> CertUtils.validateCertPath(TestData.DATE_ALL_CERTS_VALID,
                                TestData.ROOT_CA_TRUSTED, emptyCertPath));
        assertThat(expected.getMessage()).contains("empty");
    }

    @Test
    public void validateCertPath_throwsIfNotValidated() throws Exception {
        assertThrows(
                CertValidationException.class,
                () -> CertUtils.validateCertPath(TestData.DATE_ALL_CERTS_VALID,
                        TestData.ROOT_CA_DIFFERENT_COMMON_NAME,
                        com.android.server.locksettings.recoverablekeystore.TestData.CERT_PATH_1));
    }

    @Test
    public void validateCert_succeeds() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_2;
        CertUtils.validateCert(TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                leafCert);
    }

    @Test
    public void validateCert_throwsIfExpired() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_TRUSTED;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_2;
        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.validateCert(
                                TestData.DATE_LEAF_CERT_2_EXPIRED, rootCert, intermediateCerts,
                                leafCert));
    }

    @Test
    public void validateCert_throwsIfWrongRootWithTheSameCommonName() throws Exception {
        X509Certificate rootCert = TestData.ROOT_CA_DIFFERENT_KEY;
        List<X509Certificate> intermediateCerts =
                Arrays.asList(TestData.INTERMEDIATE_CA_1, TestData.INTERMEDIATE_CA_2);
        X509Certificate leafCert = TestData.LEAF_CERT_2;
        assertThrows(
                CertValidationException.class,
                () ->
                        CertUtils.validateCert(
                                TestData.DATE_ALL_CERTS_VALID, rootCert, intermediateCerts,
                                leafCert));
    }
}
