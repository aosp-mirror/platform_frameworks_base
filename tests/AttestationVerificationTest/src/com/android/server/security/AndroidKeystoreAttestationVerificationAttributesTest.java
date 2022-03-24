/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.security;

import static com.google.common.truth.Truth.assertThat;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Test for data class holding parsed X509Certificate attestation attributes. */
@RunWith(AndroidJUnit4.class)
public class AndroidKeystoreAttestationVerificationAttributesTest {
    @Rule public ExpectedException mException = ExpectedException.none();
    private static final String TEST_PHYSCIAL_DEVICE_CERTS =
            "test_attestation_wrong_root_certs.pem";
    private static final String TEST_PHYSICAL_DEVICE_CERTS_2 =
            "test_attestation_with_root_certs.pem";
    private static final String TEST_VIRTUAL_DEVICE_CERTS =
            "test_virtual_device_attestation_certs.pem";
    private static final String TEST_CERT_NO_ATTESTATION_EXTENSION =
            "test_no_attestation_ext_certs.pem";
    private static final String TEST_CERTS_NO_ATTESTATION_EXTENSION_2 =
            "test_root_certs.pem";


    private CertificateFactory mFactory;
    private AndroidKeystoreAttestationVerificationAttributes mPhysicalDeviceAttributes;
    private AndroidKeystoreAttestationVerificationAttributes mPhysicalDeviceAttributes2;
    private AndroidKeystoreAttestationVerificationAttributes mVirtualDeviceAttributes;

    @Before
    public void setUp() throws Exception {
        mFactory = CertificateFactory.getInstance("X.509");
        mPhysicalDeviceAttributes =
                AndroidKeystoreAttestationVerificationAttributes.fromCertificate(
                        generateCertificate(TEST_PHYSCIAL_DEVICE_CERTS));
        mPhysicalDeviceAttributes2 =
                AndroidKeystoreAttestationVerificationAttributes.fromCertificate(
                        generateCertificates(TEST_PHYSICAL_DEVICE_CERTS_2).get(0));
        mVirtualDeviceAttributes =
                AndroidKeystoreAttestationVerificationAttributes.fromCertificate(
                        generateCertificates(TEST_VIRTUAL_DEVICE_CERTS).get(0));
    }

    @Test
    public void parseCertificate_noAttestationExtension() throws Exception {
        List<X509Certificate> certsNoAttestation =
                generateCertificates(TEST_CERTS_NO_ATTESTATION_EXTENSION_2);
        certsNoAttestation.add(generateCertificate(TEST_CERT_NO_ATTESTATION_EXTENSION));
        for (X509Certificate cert: certsNoAttestation) {
            mException.expect(CertificateEncodingException.class);
            mException.expectMessage(
                    CoreMatchers.containsString("No attestation extension found in certificate."));

            AndroidKeystoreAttestationVerificationAttributes.fromCertificate(cert);
        }
    }

    @Test
    public void  parseCertificate_attestationLevel() {
        assertThat(mPhysicalDeviceAttributes.getAttestationVersion()).isEqualTo(3);
        assertThat(mPhysicalDeviceAttributes2.getAttestationVersion()).isEqualTo(3);
        assertThat(mVirtualDeviceAttributes.getAttestationVersion()).isEqualTo(4);
    }

    @Test
    public void  parseCertificate_attestationSecurityLevel() {
        assertThat(mPhysicalDeviceAttributes.getAttestationSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.TRUSTED_ENVIRONMENT);
        assertThat(mPhysicalDeviceAttributes2.getAttestationSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.TRUSTED_ENVIRONMENT);
        assertThat(mVirtualDeviceAttributes.getAttestationSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.SOFTWARE);
    }

    @Test
    public void  parseCertificate_isAttestationHardwareBacked() {
        assertThat(mPhysicalDeviceAttributes.isAttestationHardwareBacked()).isTrue();
        assertThat(mPhysicalDeviceAttributes2.isAttestationHardwareBacked()).isTrue();
        assertThat(mVirtualDeviceAttributes.isAttestationHardwareBacked()).isFalse();
    }

    @Test
    public void  parseCertificate_keymasterLevel() {
        assertThat(mPhysicalDeviceAttributes.getKeymasterVersion()).isEqualTo(4);
        assertThat(mPhysicalDeviceAttributes2.getKeymasterVersion()).isEqualTo(4);
        assertThat(mVirtualDeviceAttributes.getKeymasterVersion()).isEqualTo(41);
    }

    @Test
    public void  parseCertificate_keymasterSecurityLevel() {
        assertThat(mPhysicalDeviceAttributes.getKeymasterSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.TRUSTED_ENVIRONMENT);
        assertThat(mPhysicalDeviceAttributes2.getKeymasterSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.TRUSTED_ENVIRONMENT);
        assertThat(mVirtualDeviceAttributes.getKeymasterSecurityLevel()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.SecurityLevel.SOFTWARE);
    }

    @Test
    public void  parseCertificate_isKeymasterHardwareBacked() {
        assertThat(mPhysicalDeviceAttributes.isKeymasterHardwareBacked()).isTrue();
        assertThat(mPhysicalDeviceAttributes2.isKeymasterHardwareBacked()).isTrue();
        assertThat(mVirtualDeviceAttributes.isKeymasterHardwareBacked()).isFalse();
    }

    @Test
    public void  parseCertificate_attestationChallenge() {
        assertThat(mPhysicalDeviceAttributes.getAttestationChallenge().toByteArray()).isEqualTo(
                "abc".getBytes(UTF_8));
        assertThat(mPhysicalDeviceAttributes2.getAttestationChallenge().toByteArray()).isEqualTo(
                "player456".getBytes(UTF_8));
        assertThat(mVirtualDeviceAttributes.getAttestationChallenge().toByteArray()).isEqualTo(
                "player456".getBytes(UTF_8));
    }

    @Test
    public void  parseCertificate_verifiedBootState() {
        assertThat(mPhysicalDeviceAttributes.getVerifiedBootState()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.VerifiedBootState.UNVERIFIED);
        assertThat(mPhysicalDeviceAttributes2.getVerifiedBootState()).isEqualTo(
                AndroidKeystoreAttestationVerificationAttributes.VerifiedBootState.VERIFIED);
        assertThat(mVirtualDeviceAttributes.getVerifiedBootState()).isNull();
    }

    @Test
    public void  parseCertificate_keyBootPatchLevel() {
        assertThat(mPhysicalDeviceAttributes.getKeyBootPatchLevel()).isEqualTo(201907);
        assertThat(mPhysicalDeviceAttributes2.getKeyBootPatchLevel()).isEqualTo(20220105);
    }

    @Test
    public void parseCertificate_keyBootPatchLevelNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("KeyBootPatchLevel is not set."));

        mVirtualDeviceAttributes.getKeyBootPatchLevel();
    }

    @Test
    public void  parseCertificate_keyOsPatchLevel() {
        assertThat(mPhysicalDeviceAttributes.getKeyOsPatchLevel()).isEqualTo(201907);
        assertThat(mPhysicalDeviceAttributes2.getKeyOsPatchLevel()).isEqualTo(202201);
    }

    @Test
    public void parseCertificate_keyOsPatchLevelNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("KeyOsPatchLevel is not set."));

        mVirtualDeviceAttributes.getKeyOsPatchLevel();
    }

    @Test
    public void  parseCertificate_keyVendorPatchLevel() {
        assertThat(mPhysicalDeviceAttributes.getKeyVendorPatchLevel()).isEqualTo(201907);
        assertThat(mPhysicalDeviceAttributes2.getKeyVendorPatchLevel()).isEqualTo(20220105);
    }

    @Test
    public void parseCertificate_keyVendorPatchLevelNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("KeyVendorPatchLevel is not set."));

        mVirtualDeviceAttributes.getKeyVendorPatchLevel();
    }

    @Test
    public void  parseCertificate_keyAuthenticatorType() {
        assertThat(mPhysicalDeviceAttributes.getKeyAuthenticatorType()).isEqualTo(0);
        assertThat(mPhysicalDeviceAttributes2.getKeyAuthenticatorType()).isEqualTo(0);
    }

    @Test
    public void  parseCertificate_keyOsVersion() {
        assertThat(mPhysicalDeviceAttributes.getKeyOsVersion()).isEqualTo(0);
        assertThat(mPhysicalDeviceAttributes2.getKeyOsVersion()).isEqualTo(120000);
    }

    @Test
    public void parseCertificate_keyOsVersionNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("KeyOsVersion is not set."));

        mVirtualDeviceAttributes.getKeyOsVersion();
    }

    @Test
    public void  parseCertificate_verifiedBootHash() {
        assertThat(mPhysicalDeviceAttributes.getVerifiedBootHash()).isNotEmpty();
        assertThat(mPhysicalDeviceAttributes2.getVerifiedBootHash()).isNotEmpty();
    }

    @Test
    public void  parseCertificate_verifiedBootKey() {
        assertThat(mPhysicalDeviceAttributes.getVerifiedBootKey()).isNotEmpty();
        assertThat(mPhysicalDeviceAttributes2.getVerifiedBootKey()).isNotEmpty();
    }

    @Test
    public void  parseCertificate_isVerifiedBootLocked() {
        assertThat(mPhysicalDeviceAttributes.isVerifiedBootLocked()).isFalse();
        assertThat(mPhysicalDeviceAttributes2.isVerifiedBootLocked()).isTrue();
    }

    @Test
    public void parseCertificate_isVerifiedBootLockedNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("VerifiedBootLocked is not set."));

        mVirtualDeviceAttributes.isVerifiedBootLocked();
    }

    @Test
    public void  parseCertificate_applicationPackageNameVersion() {
        assertThat(mPhysicalDeviceAttributes.getApplicationPackageNameVersion()).isNotEmpty();
    }

    @Test
    public void  parseCertificate_applicationCertificateDigests() {
        assertThat(mPhysicalDeviceAttributes.getApplicationCertificateDigests()).isNotEmpty();
    }

    @Test
    public void parseCertificate_valuesNotSet() {
        assertThat(mPhysicalDeviceAttributes.getDeviceBrand()).isNull();
        assertThat(mPhysicalDeviceAttributes.getDeviceName()).isNull();
        assertThat(mPhysicalDeviceAttributes.getDeviceProductName()).isNull();
        assertThat(mPhysicalDeviceAttributes.isKeyAllowedForAllApplications()).isFalse();
        assertThat(mPhysicalDeviceAttributes2.getDeviceBrand()).isNull();
        assertThat(mPhysicalDeviceAttributes2.getDeviceName()).isNull();
        assertThat(mPhysicalDeviceAttributes2.getDeviceProductName()).isNull();
        assertThat(mPhysicalDeviceAttributes2.isKeyAllowedForAllApplications()).isFalse();
    }

    @Test
    public void parseCertificate_keyRequiresUnlockedDeviceNotSetException() {
        mException.expect(IllegalStateException.class);
        mException.expectMessage(
                CoreMatchers.containsString("KeyRequiresUnlockedDevice is not set."));

        mPhysicalDeviceAttributes.isKeyRequiresUnlockedDevice();
    }

    private X509Certificate generateCertificate(String certificateString)
            throws Exception {
        return generateCertificates(certificateString).get(0);
    }

    private List<X509Certificate> generateCertificates(String certificateString)
            throws Exception {
        Collection<? extends Certificate> certificates = mFactory.generateCertificates(
                InstrumentationRegistry.getInstrumentation().getContext().getResources().getAssets()
                        .open(certificateString));

        ArrayList<X509Certificate> x509Certs = new ArrayList<>();
        for (Certificate cert : certificates) {
            x509Certs.add((X509Certificate) cert);
        }
        return x509Certs;
    }
}
