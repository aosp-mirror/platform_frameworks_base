/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class PackageSignaturesTest {
    private static final String TEST_RESOURCES_FOLDER = "PackageSignaturesTest";

    private Context mContext;

    private PackageSetting mPackageSetting;

    // These signatures are the DER encoding of the ec-p256[_X] X509 certificates in the certs/
    // directory. The apksigner tool was used to sign a test APK with these certificates and the
    // corresponding ec-p256{_X].pk8 private key file. For the lineage tests the
    // ec-p256-lineage-X-signers file was provided as the parameter to the --lineage option when
    // signing the APK. The APK was then installed on a test device, the packages.xml file was
    // pulled from the device, and the APK's <sig> tag was used as the basis for these tests.
    // For more details see the README under the xml/ directory.
    private static final String FIRST_EXPECTED_SIGNATURE =
            "3082016c30820111a003020102020900ca0fb64dfb66e772300a06082a8648ce3d04030230123110300e06"
            + "035504030c0765632d70323536301e170d3136303333313134353830365a170d34333038313731343538"
            + "30365a30123110300e06035504030c0765632d703235363059301306072a8648ce3d020106082a8648ce"
            + "3d03010703420004a65f113d22cb4913908307ac31ee2ba0e9138b785fac6536d14ea2ce90d2b4bfe194"
            + "b50cdc8e169f54a73a991ef0fa76329825be078cc782740703da44b4d7eba350304e301d0603551d0e04"
            + "160414d4133568b95b30158b322071ea8c43ff5b05ccc8301f0603551d23041830168014d4133568b95b"
            + "30158b322071ea8c43ff5b05ccc8300c0603551d13040530030101ff300a06082a8648ce3d0403020349"
            + "003046022100f504a0866caef029f417142c5cb71354c79ffcd1d640618dfca4f19e16db78d6022100f8"
            + "eea4829799c06cad08c6d3d2d2ec05e0574154e747ea0fdbb8042cb655aadd";
    private static final String SECOND_EXPECTED_SIGNATURE =
            "3082016d30820113a0030201020209008855bd1dd2b2b225300a06082a8648ce3d04030230123110300e06"
            + "035504030c0765632d70323536301e170d3138303731333137343135315a170d32383037313031373431"
            + "35315a30143112301006035504030c0965632d703235365f323059301306072a8648ce3d020106082a86"
            + "48ce3d030107034200041d4cca0472ad97ee3cecef0da93d62b450c6788333b36e7553cde9f74ab5df00"
            + "bbba6ba950e68461d70bbc271b62151dad2de2bf6203cd2076801c7a9d4422e1a350304e301d0603551d"
            + "0e041604147991d92b0208fc448bf506d4efc9fff428cb5e5f301f0603551d23041830168014d4133568"
            + "b95b30158b322071ea8c43ff5b05ccc8300c0603551d13040530030101ff300a06082a8648ce3d040302"
            + "034800304502202769abb1b49fc2f53479c4ae92a6631dabfd522c9acb0bba2b43ebeb99c63011022100"
            + "d260fb1d1f176cf9b7fa60098bfd24319f4905a3e5fda100a6fe1a2ab19ff09e";
    private static final String THIRD_EXPECTED_SIGNATURE =
            "3082016e30820115a0030201020209008394f5cad16a89a7300a06082a8648ce3d04030230143112301006"
            + "035504030c0965632d703235365f32301e170d3138303731343030303532365a170d3238303731313030"
            + "303532365a30143112301006035504030c0965632d703235365f333059301306072a8648ce3d02010608"
            + "2a8648ce3d03010703420004f31e62430e9db6fc5928d975fc4e47419bacfcb2e07c89299e6cd7e344dd"
            + "21adfd308d58cb49a1a2a3fecacceea4862069f30be1643bcc255040d8089dfb3743a350304e301d0603"
            + "551d0e041604146f8d0828b13efaf577fc86b0e99fa3e54bcbcff0301f0603551d230418301680147991"
            + "d92b0208fc448bf506d4efc9fff428cb5e5f300c0603551d13040530030101ff300a06082a8648ce3d04"
            + "030203470030440220256bdaa2784c273e4cc291a595a46779dee9de9044dc9f7ab820309567df9fe902"
            + "201a4ad8c69891b5a8c47434fe9540ed1f4979b5fad3483f3fa04d5677355a579e";

    // When running tests using the pastSigs tag / lineage the past signers and their capabilities
    // should be returned in the SigningDetails. The flags attribute of the cert tag under the
    // pastSigs tag contains these capabilities; for tests that verify the lineage the capabilities
    // of the signers should be set to the values in this Map.
    private static final Map<String, Integer> SIGNATURE_TO_CAPABILITY_MAP;

    static {
        SIGNATURE_TO_CAPABILITY_MAP = new HashMap<>();
        SIGNATURE_TO_CAPABILITY_MAP.put(FIRST_EXPECTED_SIGNATURE, 3);
        SIGNATURE_TO_CAPABILITY_MAP.put(SECOND_EXPECTED_SIGNATURE, 7);
        SIGNATURE_TO_CAPABILITY_MAP.put(THIRD_EXPECTED_SIGNATURE, 23);
    }

    private static final int[] CAPABILITIES =
            {SigningDetails.CertCapabilities.INSTALLED_DATA,
                    SigningDetails.CertCapabilities.SHARED_USER_ID,
                    SigningDetails.CertCapabilities.PERMISSION,
                    SigningDetails.CertCapabilities.ROLLBACK};

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        mPackageSetting = createPackageSetting();
    }

    @Test
    public void testReadXmlWithOneSignerCompletesSuccessfully() throws Exception {
        // Verifies the good path of reading a single sigs tag with one signer returns the
        // expected signature and scheme version.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer.xml", 1, FIRST_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithTwoV1V2Signers() throws Exception {
        // Verifies the good path of reading a single sigs tag with multiple signers returns the
        // expected signatures and scheme version.
        verifyReadXmlReturnsExpectedSignatures("xml/two-signers-v1v2.xml", 2,
                FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlFromTwoSigsTagsWithSameSigner() throws Exception {
        // Verifies the good path of reading two separate packages tags from the same signer. The
        // first call to readXml should return the list with the expected signature, then the second
        // call should reference this signature and complete successfully with no new entries in the
        // List.
        TypedXmlPullParser parser = getXMLFromResources("xml/one-signer.xml");
        ArrayList<Signature> signatures = new ArrayList<>();
        mPackageSetting.signatures.readXml(parser, signatures);
        Set<String> expectedSignatures = createSetOfSignatures(FIRST_EXPECTED_SIGNATURE);
        verifySignaturesContainExpectedValues(signatures, expectedSignatures);
        parser = getXMLFromResources("xml/one-signer-previous-cert.xml");
        mPackageSetting.signatures.readXml(parser, signatures);
        expectedSignatures = createSetOfSignatures(FIRST_EXPECTED_SIGNATURE);
        verifySignaturesContainExpectedValues(signatures, expectedSignatures);
    }

    @Test
    public void testReadXmlWithSigningLineage() throws Exception {
        // Verifies the good path of reading a single sigs tag including pastSigs with the
        // signing lineage returns the expected signatures and lineage for two and three signers
        // in the lineage.
        verifyReadXmlReturnsExpectedSignaturesAndLineage("xml/two-signers-in-lineage.xml", 3,
                FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE);
        verifyReadXmlReturnsExpectedSignaturesAndLineage("xml/three-signers-in-lineage.xml", 3,
                FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE, THIRD_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidPublicKeyInCertKey() throws Exception {
        // If the cert tag key attribute does not contain a valid public key then a
        // CertificateException should be thrown when attempting to build the SigningDetails; in
        // this case the signing details should be set to UNKNOWN.
        TypedXmlPullParser parser = getXMLFromResources(
                "xml/one-signer-invalid-public-key-cert-key.xml");
        ArrayList<Signature> signatures = new ArrayList<>();
        mPackageSetting.signatures.readXml(parser, signatures);
        assertEquals(
                "The signing details was not UNKNOWN after parsing an invalid public key cert key"
                        + " attribute",
                SigningDetails.UNKNOWN, mPackageSetting.signatures.mSigningDetails);
    }

    @Test
    public void testReadXmlWithMissingSigsCount() throws Exception {
        // Verifies if the sigs count attribute is missing then the signature cannot be read but the
        // method does not throw an exception.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-missing-sigs-count.xml",
                SigningDetails.SignatureSchemeVersion.UNKNOWN);
    }

    @Test
    public void testReadXmlWithMissingSchemeVersion() throws Exception {
        // Verifies if the schemeVersion is an invalid value the signature can still be obtained.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-missing-scheme-version.xml",
                SigningDetails.SignatureSchemeVersion.UNKNOWN,
                FIRST_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithSigningLineageWithMissingSchemeVersion() throws Exception {
        // Verifies if the scheme version cannot be read the signers in the lineage can still be
        // obtained.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/three-signers-in-lineage-missing-scheme-version.xml",
                SigningDetails.SignatureSchemeVersion.UNKNOWN,
                FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE, THIRD_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidCertIndex() throws Exception {
        // If the cert index attribute is invalid the signature will not be read but the call
        // should exit gracefully.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-invalid-cert-index.xml", 3);
    }

    @Test
    public void testReadXmlWithMissingCertIndex() throws Exception {
        // If the cert index attribute is missing the signature will not be read but the call should
        // exit gracefully.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-missing-cert-index.xml", 3);
    }

    @Test
    public void testReadXmlWithInvalidCertKey() throws Exception {
        // If the cert key value is invalid the signature cannot be read but the call should exit
        // gracefully.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-invalid-cert-key.xml", 3);
    }

    @Test
    public void testReadXmlWithMissingCertKey() throws Exception {
        // If the cert key is missing the signature cannot be read but the call should exit
        // gracefully.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-missing-cert-key.xml", 3);
    }

    @Test
    public void testReadXmlWithMissingCertTag() throws Exception {
        // If the cert tag is missing there is no signature to read but the call should exit
        // gracefully.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-missing-cert-tag.xml", 3);
    }

    @Test
    public void testReadXmlWithTooFewCertTags() throws Exception {
        // If the number of cert tags is less than that specified in the count attribute then the
        // signatures that could be read are copied to a smaller array to be used when building
        // the SigningDetails object. This test verifies if there are too few cert tags the
        // available signatures can still be obtained.
        verifyReadXmlReturnsExpectedSignatures("xml/two-signers-v1v2-missing-cert-tag.xml", 1,
                FIRST_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithExtraCertTag() throws Exception {
        // Verifies if there are more cert tags than specified by the count attribute the extra cert
        // tag is ignored and the expected signature from the first cert tag is returned.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-extra-cert-tag.xml", 3,
                FIRST_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidTag() throws Exception {
        // Verifies an invalid tag under sigs is ignored and the expected signature is returned.
        verifyReadXmlReturnsExpectedSignatures("xml/one-signer-invalid-tag.xml", 3,
                FIRST_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidPastSigsCount() throws Exception {
        // Verifies if the pastSigs tag contains an invalid count attribute the current signature
        // is still returned; in this case the third expected signature is the most recent signer.
        verifyReadXmlReturnsExpectedSignatures(
                "xml/three-signers-in-lineage-invalid-pastSigs-count.xml", 3,
                THIRD_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithMissingPastSigsCount() throws Exception {
        // Verifies if the pastSigs tag is missing the count attribute the current signature is
        // still returned; in this case the third expected signature is the most recent signer.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/three-signers-in-lineage-missing-pastSigs-count.xml", 3,
                THIRD_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidCertFlags() throws Exception {
        // Verifies if the cert tag contains an invalid flags attribute the expected signatures
        // are still returned, although since the flags could not be read these signatures will not
        // include the capabilities of the previous signers in the lineage.
        verifyReadXmlReturnsExpectedSignatures("xml/two-signers-in-lineage-invalid-certs-flags.xml",
                3, FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithMissingCertFlags() throws Exception {
        // Verifies if the cert tag does not contain a flags attribute the expected signatures are
        // still returned, although since there are no flags to read these signatures will not
        // include the capabilities of the previous signers in the lineage.
        verifyReadXmlReturnsExpectedSignatures("xml/two-signers-in-lineage-missing-certs-flags.xml",
                3, FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithMultiplePastSigsTags() throws Exception {
        // Verifies if multiple pastSigs tags are found under the sigs tag the additional pastSigs
        // tag is ignored and the expected signatures are returned along with the previous signer in
        // the lineage.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/two-signers-in-lineage-multiple-pastSigs-tags.xml", 3,
                FIRST_EXPECTED_SIGNATURE, SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithInvalidPastSigsCertIndex() throws Exception {
        // If the pastSigs cert tag contains an invalid index attribute that signature cannot be
        // read but the current signature should still be returned.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/two-signers-in-lineage-invalid-pastSigs-cert-index.xml", 3,
                SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithMissingPastSigsCertIndex() throws Exception {
        // If the pastSigs cert tag does not contain an index attribute that signature cannot be
        // read but the current signature should still be returned.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/two-signers-in-lineage-missing-pastSigs-cert-index.xml", 3,
                SECOND_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithUndefinedPastSigsIndex() throws Exception {
        // If a cert tag does not contain a key attribute it is assumed that the index attribute
        // refers to a previously seen signature. If a signature does not yet exist at this index
        // then the current signature cannot be read but any other signatures should still be
        // returned.
        verifyReadXmlReturnsExpectedSignatures(
                "xml/two-signers-in-lineage-undefined-pastSigs-index.xml", 3,
                FIRST_EXPECTED_SIGNATURE, null);
    }

    @Test
    public void testReadXmlWithTooFewPastSigsCertTags() throws Exception {
        // If the number of cert tags is less than that specified in the count attribute of the
        // pastSigs tag then the signatures that could be read are copied to a smaller array to be
        // used when building the SigningDetails object. This test verifies if there are too few
        // cert tags the available signatures and lineage can still be obtained.
        verifyReadXmlReturnsExpectedSignaturesAndLineage(
                "xml/three-signers-in-lineage-missing-pastSigs-cert-tag.xml", 3,
                FIRST_EXPECTED_SIGNATURE, THIRD_EXPECTED_SIGNATURE);
    }

    @Test
    public void testReadXmlWithPastSignerWithNoCapabilities() throws Exception {
        // When rotating the signing key a developer is able to specify the capabilities granted to
        // the apps signed with the previous key. This test verifies a previous signing certificate
        // with the flags set to 0 does not have any capabilities.
        TypedXmlPullParser parser = getXMLFromResources("xml/two-signers-in-lineage-no-caps.xml");
        ArrayList<Signature> signatures = new ArrayList<>();
        mPackageSetting.signatures.readXml(parser, signatures);
        // obtain the Signature in the list matching the previous signing certificate
        Signature previousSignature = null;
        for (Signature signature : signatures) {
            String signatureValue = HexDump.toHexString(signature.toByteArray(), false);
            if (signatureValue.equals(FIRST_EXPECTED_SIGNATURE)) {
                previousSignature = signature;
                break;
            }
        }
        assertNotNull("Unable to find the expected previous signer", previousSignature);
        for (int capability : CAPABILITIES) {
            assertFalse("The previous signer should not have the " + capability + " capability",
                    mPackageSetting.signatures.mSigningDetails.hasCertificate(previousSignature,
                            capability));
        }
    }

    /**
     * Verifies reading the sigs tag of the provided XML file returns the specified signature scheme
     * version and the provided signatures.
     */
    private void verifyReadXmlReturnsExpectedSignatures(String xmlFile, int expectedSchemeVersion,
            String... expectedSignatureValues) throws Exception {
        TypedXmlPullParser parser = getXMLFromResources(xmlFile);
        ArrayList<Signature> signatures = new ArrayList<>();
        mPackageSetting.signatures.readXml(parser, signatures);
        Set<String> expectedSignatures = createSetOfSignatures(expectedSignatureValues);
        verifySignaturesContainExpectedValues(signatures, expectedSignatures);
        assertEquals("The returned signature scheme is not the expected value",
                expectedSchemeVersion,
                mPackageSetting.signatures.mSigningDetails.getSignatureSchemeVersion());
    }

    /**
     * Verifies reading the sigs tag of the provided XML file returns the specified signature scheme
     * version, the provided signatures, and that the previous signers have the expected
     * capabilities.
     */
    private void verifyReadXmlReturnsExpectedSignaturesAndLineage(String xmlFile,
            int schemeVersion, String... expectedSignatureValues) throws Exception {
        TypedXmlPullParser parser = getXMLFromResources(xmlFile);
        ArrayList<Signature> signatures = new ArrayList<>();
        mPackageSetting.signatures.readXml(parser, signatures);
        Set<String> expectedSignatures = createSetOfSignatures(expectedSignatureValues);
        verifySignaturesContainExpectedValues(signatures, expectedSignatures);
        assertEquals("The returned signature scheme is not the expected value", schemeVersion,
                mPackageSetting.signatures.mSigningDetails.getSignatureSchemeVersion());
        for (Signature signature : signatures) {
            String signatureValue = HexDump.toHexString(signature.toByteArray(), false);
            int expectedCapabilities = SIGNATURE_TO_CAPABILITY_MAP.get(signatureValue);
            assertTrue("The signature " + signatureValue
                            + " was not found with the expected capabilities of " +
                            expectedCapabilities
                            + " in the signing details",
                    mPackageSetting.signatures.mSigningDetails.hasCertificate(signature,
                            expectedCapabilities));
        }
    }

    /**
     * Verifies the provided {@code List} contains Signatures that match the provided hex encoded
     * signature values.
     *
     * The provided {@code Set} will be modified by this method as elements will be removed to
     * ensure duplicate expected Signatures are not in the {@code List}.
     */
    private static void verifySignaturesContainExpectedValues(ArrayList<Signature> signatures,
            Set<String> expectedSignatures) {
        assertEquals("The number of signatures does not equal the expected number of signatures",
                expectedSignatures.size(), signatures.size());
        for (Signature signature : signatures) {
            String signatureString = null;
            if (signature != null) {
                signatureString = HexDump.toHexString(signature.toByteArray(), false);
            }
            // If the signature is in the expected set then remove it so that duplicate matching
            // signatures are reported.
            if (expectedSignatures.contains(signatureString)) {
                expectedSignatures.remove(signatureString);
            } else {
                fail("The following unexpected signature was returned: " + signatureString);
            }
        }
    }

    private static Set<String> createSetOfSignatures(String... signatures) {
        Set<String> result = new HashSet<String>();
        for (String signature : signatures) {
            result.add(signature);
        }
        return result;
    }

    private TypedXmlPullParser getXMLFromResources(String xmlFile) throws Exception {
        InputStream xmlStream = mContext.getResources().getAssets().open(
                TEST_RESOURCES_FOLDER + "/" + xmlFile);
        TypedXmlPullParser result = Xml.resolvePullParser(xmlStream);
        int type;
        // advance the parser to the first tag
        while ((type = result.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            ;
        }
        return result;
    }

    private static PackageSetting createPackageSetting() {
        // Generic PackageSetting object with values from a test app installed on a device to be
        // used to test the methods under the PackageSignatures signatures data member.
        return new PackageSettingBuilder()
                .setName("test.app")
                .setCodePath("/data/app/app")
                .setPVersionCode(1)
                .setPkgFlags(940097092)
                .build();
    }
}
