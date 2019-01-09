/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.EAPConstants;
import android.net.wifi.FakeKeys;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.CredentialTest}.
 */
@SmallTest
public class CredentialTest {
    /**
     * Helper function for generating Credential for testing.
     *
     * @param userCred Instance of UserCredential
     * @param certCred Instance of CertificateCredential
     * @param simCred Instance of SimCredential
     * @param clientCertificateChain Chain of client certificates
     * @param clientPrivateKey Client private key
     * @param caCerts CA certificates
     * @return {@link Credential}
     */
    private static Credential createCredential(Credential.UserCredential userCred,
            Credential.CertificateCredential certCred,
            Credential.SimCredential simCred,
            X509Certificate[] clientCertificateChain, PrivateKey clientPrivateKey,
            X509Certificate... caCerts) {
        Credential cred = new Credential();
        cred.setCreationTimeInMillis(123455L);
        cred.setExpirationTimeInMillis(2310093L);
        cred.setRealm("realm");
        cred.setCheckAaaServerCertStatus(true);
        cred.setUserCredential(userCred);
        cred.setCertCredential(certCred);
        cred.setSimCredential(simCred);
        if (caCerts != null && caCerts.length == 1) {
            cred.setCaCertificate(caCerts[0]);
        } else {
            cred.setCaCertificates(caCerts);
        }
        cred.setClientCertificateChain(clientCertificateChain);
        cred.setClientPrivateKey(clientPrivateKey);
        return cred;
    }

    /**
     * Helper function for generating certificate credential for testing.
     *
     * @return {@link Credential}
     */
    private static Credential createCredentialWithCertificateCredential()
            throws NoSuchAlgorithmException, CertificateEncodingException {
        Credential.CertificateCredential certCred = new Credential.CertificateCredential();
        certCred.setCertType("x509v3");
        certCred.setCertSha256Fingerprint(
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded()));
        return createCredential(null, certCred, null, new X509Certificate[] {FakeKeys.CLIENT_CERT},
                FakeKeys.RSA_KEY1, FakeKeys.CA_CERT0, FakeKeys.CA_CERT1);
    }

    /**
     * Helper function for generating SIM credential for testing.
     *
     * @return {@link Credential}
     */
    private static Credential createCredentialWithSimCredential() {
        Credential.SimCredential simCred = new Credential.SimCredential();
        simCred.setImsi("1234*");
        simCred.setEapType(EAPConstants.EAP_SIM);
        return createCredential(null, null, simCred, null, null, (X509Certificate[]) null);
    }

    /**
     * Helper function for generating user credential for testing.
     *
     * @return {@link Credential}
     */
    private static Credential createCredentialWithUserCredential() {
        Credential.UserCredential userCred = new Credential.UserCredential();
        userCred.setUsername("username");
        userCred.setPassword("password");
        userCred.setMachineManaged(true);
        userCred.setAbleToShare(true);
        userCred.setSoftTokenApp("TestApp");
        userCred.setEapType(EAPConstants.EAP_TTLS);
        userCred.setNonEapInnerMethod("MS-CHAP");
        return createCredential(userCred, null, null, null, null, FakeKeys.CA_CERT0);
    }

    private static void verifyParcel(Credential writeCred) {
        Parcel parcel = Parcel.obtain();
        writeCred.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        Credential readCred = Credential.CREATOR.createFromParcel(parcel);
        assertTrue(readCred.equals(writeCred));
        assertEquals(writeCred.hashCode(), readCred.hashCode());
    }

    /**
     * Verify parcel read/write for a default/empty credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithDefault() throws Exception {
        verifyParcel(new Credential());
    }

    /**
     * Verify parcel read/write for a certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithCertificateCredential() throws Exception {
        verifyParcel(createCredentialWithCertificateCredential());
    }

    /**
     * Verify parcel read/write for a SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithSimCredential() throws Exception {
        verifyParcel(createCredentialWithSimCredential());
    }

    /**
     * Verify parcel read/write for an user credential.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithUserCredential() throws Exception {
        verifyParcel(createCredentialWithUserCredential());
    }

    /**
     * Verify a valid user credential.
     * @throws Exception
     */
    @Test
    public void validateUserCredential() throws Exception {
        Credential cred = createCredentialWithUserCredential();

        // For R1 validation
        assertTrue(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify that an user credential without CA Certificate is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutCaCert() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.setCaCertificate(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify that an user credential with EAP type other than EAP-TTLS is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithEapTls() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.getUserCredential().setEapType(EAPConstants.EAP_TLS);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }


    /**
     * Verify that an user credential without realm is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutRealm() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.setRealm(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that an user credential without username is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutUsername() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.getUserCredential().setUsername(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that an user credential without password is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutPassword() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.getUserCredential().setPassword(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that an user credential without auth methoh (non-EAP inner method) is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUserCredentialWithoutAuthMethod() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        cred.getUserCredential().setNonEapInnerMethod(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify a certificate credential. CA Certificate, client certificate chain,
     * and client private key are all required.  Also the digest for client
     * certificate must match the fingerprint specified in the certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredential() throws Exception {
        Credential cred = createCredentialWithCertificateCredential();

        // For R1 validation
        assertTrue(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(true));
    }

    /**
     * Verify that an certificate credential without CA Certificate is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithoutCaCert() throws Exception {
        Credential cred = createCredentialWithCertificateCredential();
        cred.setCaCertificate(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify that a certificate credential without client certificate chain is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithoutClientCertChain() throws Exception {
        Credential cred = createCredentialWithCertificateCredential();
        cred.setClientCertificateChain(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that a certificate credential without client private key is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithoutClientPrivateKey() throws Exception {
        Credential cred = createCredentialWithCertificateCredential();
        cred.setClientPrivateKey(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that a certificate credential with mismatch client certificate fingerprint
     * is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCertCredentialWithMismatchFingerprint() throws Exception {
        Credential cred = createCredentialWithCertificateCredential();
        cred.getCertCredential().setCertSha256Fingerprint(new byte[32]);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify a SIM credential using EAP-SIM.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapSim() throws Exception {
        Credential cred = createCredentialWithSimCredential();

        // For R1 validation
        assertTrue(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify a SIM credential using EAP-AKA.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapAka() throws Exception {
        Credential cred = createCredentialWithSimCredential();
        cred.getSimCredential().setEapType(EAPConstants.EAP_AKA);

        // For R1 validation
        assertTrue(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify a SIM credential using EAP-AKA-PRIME.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapAkaPrime() throws Exception {
        Credential cred = createCredentialWithSimCredential();
        cred.getSimCredential().setEapType(EAPConstants.EAP_AKA_PRIME);

        // For R1 validation
        assertTrue(cred.validate(true));

        // For R2 validation
        assertTrue(cred.validate(false));
    }

    /**
     * Verify that a SIM credential without IMSI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithoutIMSI() throws Exception {
        Credential cred = createCredentialWithSimCredential();
        cred.getSimCredential().setImsi(null);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that a SIM credential with an invalid IMSI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithInvalidIMSI() throws Exception {
        Credential cred = createCredentialWithSimCredential();
        cred.getSimCredential().setImsi("dummy");

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that a SIM credential with invalid EAP type is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateSimCredentialWithEapTls() throws Exception {
        Credential cred = createCredentialWithSimCredential();
        cred.getSimCredential().setEapType(EAPConstants.EAP_TLS);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that a credential contained both an user and a SIM credential is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateCredentialWithUserAndSimCredential() throws Exception {
        Credential cred = createCredentialWithUserCredential();
        // Setup SIM credential.
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi("1234*");
        simCredential.setEapType(EAPConstants.EAP_SIM);
        cred.setSimCredential(simCredential);

        // For R1 validation
        assertFalse(cred.validate(true));

        // For R2 validation
        assertFalse(cred.validate(false));
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithNullSource() throws Exception {
        Credential copyCred = new Credential(null);
        Credential defaultCred = new Credential();
        assertTrue(copyCred.equals(defaultCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with user credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithUserCred() throws Exception {
        Credential sourceCred = createCredentialWithUserCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithCertCred() throws Exception {
        Credential sourceCred = createCredentialWithCertificateCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }

    /**
     * Verify that copy constructor works when pass in a source with SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorWithSourceWithSimCred() throws Exception {
        Credential sourceCred = createCredentialWithSimCredential();
        Credential copyCred = new Credential(sourceCred);
        assertTrue(copyCred.equals(sourceCred));
    }
}
