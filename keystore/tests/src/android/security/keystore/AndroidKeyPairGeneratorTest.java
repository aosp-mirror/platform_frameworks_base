/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.security.keystore;

import android.security.Credentials;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore;
import android.security.keymaster.ExportResult;
import android.security.keymaster.KeymasterDefs;
import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

public class AndroidKeyPairGeneratorTest extends AndroidTestCase {
    private android.security.KeyStore mAndroidKeyStore;

    private java.security.KeyPairGenerator mGenerator;

    private static final String TEST_ALIAS_1 = "test1";

    private static final String TEST_ALIAS_2 = "test2";

    private static final X500Principal TEST_DN_1 = new X500Principal("CN=test1");

    private static final X500Principal TEST_DN_2 = new X500Principal("CN=test2");

    private static final BigInteger TEST_SERIAL_1 = BigInteger.ONE;

    private static final BigInteger TEST_SERIAL_2 = BigInteger.valueOf(2L);

    private static final long NOW_MILLIS = System.currentTimeMillis();

    /* We have to round this off because X509v3 doesn't store milliseconds. */
    private static final Date NOW = new Date(NOW_MILLIS - (NOW_MILLIS % 1000L));

    @SuppressWarnings("deprecation")
    private static final Date NOW_PLUS_10_YEARS = new Date(NOW.getYear() + 10, 0, 1);

    @Override
    protected void setUp() throws Exception {
        mAndroidKeyStore = android.security.KeyStore.getInstance();

        assertTrue(mAndroidKeyStore.reset());

        assertFalse(mAndroidKeyStore.isUnlocked());

        mGenerator = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
    }

    private void setupPassword() {
        assertTrue(mAndroidKeyStore.onUserPasswordChanged("1111"));
        assertTrue(mAndroidKeyStore.isUnlocked());

        String[] aliases = mAndroidKeyStore.list("");
        assertNotNull(aliases);
        assertEquals(0, aliases.length);
    }

    public void testKeyPairGenerator_Initialize_Params_Encrypted_Success() throws Exception {
        setupPassword();

        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .setEncryptionRequired()
                .build());
    }

    public void testKeyPairGenerator_Initialize_KeySize_Encrypted_Failure() throws Exception {
        setupPassword();

        try {
            mGenerator.initialize(1024);
            fail("KeyPairGenerator should not support setting the key size");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testKeyPairGenerator_Initialize_KeySizeAndSecureRandom_Encrypted_Failure()
            throws Exception {
        setupPassword();

        try {
            mGenerator.initialize(1024, new SecureRandom());
            fail("KeyPairGenerator should not support setting the key size");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testKeyPairGenerator_Initialize_ParamsAndSecureRandom_Encrypted_Failure()
            throws Exception {
        setupPassword();

        mGenerator.initialize(
                new KeyPairGeneratorSpec.Builder(getContext())
                        .setAlias(TEST_ALIAS_1)
                        .setKeyType("RSA")
                        .setKeySize(1024)
                        .setSubject(TEST_DN_1)
                        .setSerialNumber(TEST_SERIAL_1)
                        .setStartDate(NOW)
                        .setEndDate(NOW_PLUS_10_YEARS)
                        .setEncryptionRequired()
                        .build(),
                new SecureRandom());
    }

    public void testKeyPairGenerator_GenerateKeyPair_Encrypted_Success() throws Exception {
        setupPassword();

        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .setEncryptionRequired()
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "RSA", 2048, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_EC_Unencrypted_Success() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(
                TEST_ALIAS_1,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setCertificateSubject(TEST_DN_1)
                .setCertificateSerialNumber(TEST_SERIAL_1)
                .setCertificateNotBefore(NOW)
                .setCertificateNotAfter(NOW_PLUS_10_YEARS)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build());

        final KeyPair pair = generator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "EC", 256, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_Legacy_GenerateKeyPair_EC_Unencrypted_Success()
            throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeyType("EC")
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "EC", 256, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_EC_P521_Unencrypted_Success() throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeyType("EC")
                .setKeySize(521)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "EC", 521, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_RSA_Unencrypted_Success() throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "RSA", 2048, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_RSA_WithParams_Unencrypted_Success()
            throws Exception {
        AlgorithmParameterSpec spec = new RSAKeyGenParameterSpec(1024, BigInteger.valueOf(3L));
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeySize(1024)
                .setAlgorithmParameterSpec(spec)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "RSA", 1024, spec, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_Replaced_Success() throws Exception {
        // Generate the first key
        {
            mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                    .setAlias(TEST_ALIAS_1)
                    .setSubject(TEST_DN_1)
                    .setSerialNumber(TEST_SERIAL_1)
                    .setStartDate(NOW)
                    .setEndDate(NOW_PLUS_10_YEARS)
                    .build());
            final KeyPair pair1 = mGenerator.generateKeyPair();
            assertNotNull("The KeyPair returned should not be null", pair1);
            assertKeyPairCorrect(pair1, TEST_ALIAS_1, "RSA", 2048, null, TEST_DN_1, TEST_SERIAL_1,
                    NOW, NOW_PLUS_10_YEARS);
        }

        // Replace the original key
        {
            mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                    .setAlias(TEST_ALIAS_2)
                    .setSubject(TEST_DN_2)
                    .setSerialNumber(TEST_SERIAL_2)
                    .setStartDate(NOW)
                    .setEndDate(NOW_PLUS_10_YEARS)
                    .build());
            final KeyPair pair2 = mGenerator.generateKeyPair();
            assertNotNull("The KeyPair returned should not be null", pair2);
            assertKeyPairCorrect(pair2, TEST_ALIAS_2, "RSA", 2048, null, TEST_DN_2, TEST_SERIAL_2,
                    NOW, NOW_PLUS_10_YEARS);
        }
    }

    public void testKeyPairGenerator_GenerateKeyPair_Replaced_UnencryptedToEncrypted_Success()
            throws Exception {
        // Generate the first key
        {
            mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                    .setAlias(TEST_ALIAS_1)
                    .setSubject(TEST_DN_1)
                    .setSerialNumber(TEST_SERIAL_1)
                    .setStartDate(NOW)
                    .setEndDate(NOW_PLUS_10_YEARS)
                    .build());
            final KeyPair pair1 = mGenerator.generateKeyPair();
            assertNotNull("The KeyPair returned should not be null", pair1);
            assertKeyPairCorrect(pair1, TEST_ALIAS_1, "RSA", 2048, null, TEST_DN_1, TEST_SERIAL_1,
                    NOW, NOW_PLUS_10_YEARS);
        }

        // Attempt to replace previous key
        {
            mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                    .setAlias(TEST_ALIAS_1)
                    .setSubject(TEST_DN_2)
                    .setSerialNumber(TEST_SERIAL_2)
                    .setStartDate(NOW)
                    .setEndDate(NOW_PLUS_10_YEARS)
                    .setEncryptionRequired()
                    .build());
            try {
                mGenerator.generateKeyPair();
                fail("Should not be able to generate encrypted key while not initialized");
            } catch (IllegalStateException expected) {
            }

            assertTrue(mAndroidKeyStore.onUserPasswordChanged("1111"));
            assertTrue(mAndroidKeyStore.isUnlocked());

            final KeyPair pair2 = mGenerator.generateKeyPair();
            assertNotNull("The KeyPair returned should not be null", pair2);
            assertKeyPairCorrect(pair2, TEST_ALIAS_1, "RSA", 2048, null, TEST_DN_2, TEST_SERIAL_2,
                    NOW, NOW_PLUS_10_YEARS);
        }
    }

    private void assertKeyPairCorrect(KeyPair pair, String alias, String keyType, int keySize,
            AlgorithmParameterSpec spec, X500Principal dn, BigInteger serial, Date start, Date end)
            throws Exception {
        final PublicKey pubKey = pair.getPublic();
        assertNotNull("The PublicKey for the KeyPair should be not null", pubKey);
        assertEquals(keyType, pubKey.getAlgorithm());

        if ("EC".equalsIgnoreCase(keyType)) {
            assertEquals("Curve should be what was specified during initialization", keySize,
                    ((ECPublicKey) pubKey).getParams().getCurve().getField().getFieldSize());
        } else if ("RSA".equalsIgnoreCase(keyType)) {
            RSAPublicKey rsaPubKey = (RSAPublicKey) pubKey;
            assertEquals("Modulus size should be what is specified during initialization",
                    (keySize + 7) & ~7, (rsaPubKey.getModulus().bitLength() + 7) & ~7);
            if (spec != null) {
                RSAKeyGenParameterSpec params = (RSAKeyGenParameterSpec) spec;
                assertEquals((keySize + 7) & ~7, (params.getKeysize() + 7) & ~7);
                assertEquals(params.getPublicExponent(), rsaPubKey.getPublicExponent());
            }
        }

        final PrivateKey privKey = pair.getPrivate();
        assertNotNull("The PrivateKey for the KeyPair should be not null", privKey);
        assertEquals(keyType, privKey.getAlgorithm());

        if ("EC".equalsIgnoreCase(keyType)) {
            assertTrue("EC private key must be instanceof ECKey: " + privKey.getClass().getName(),
                    privKey instanceof ECKey);
            assertEquals("Private and public key must have the same EC parameters",
                    ((ECKey) pubKey).getParams(), ((ECKey) privKey).getParams());
        } else if ("RSA".equalsIgnoreCase(keyType)) {
            assertTrue("RSA private key must be instance of RSAKey: "
                    + privKey.getClass().getName(),
                    privKey instanceof RSAKey);
            assertEquals("Private and public key must have the same RSA modulus",
                    ((RSAKey) pubKey).getModulus(), ((RSAKey) privKey).getModulus());
        }

        final byte[] userCertBytes = mAndroidKeyStore.get(Credentials.USER_CERTIFICATE + alias);
        assertNotNull("The user certificate should exist for the generated entry", userCertBytes);

        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final Certificate userCert =
                cf.generateCertificate(new ByteArrayInputStream(userCertBytes));

        assertTrue("Certificate should be in X.509 format", userCert instanceof X509Certificate);

        final X509Certificate x509userCert = (X509Certificate) userCert;

        assertEquals(
                "Public key used to sign certificate should have the same algorithm as in KeyPair",
                pubKey.getAlgorithm(), x509userCert.getPublicKey().getAlgorithm());

        assertEquals("PublicKey used to sign certificate should match one returned in KeyPair",
                pubKey,
                AndroidKeyStoreProvider.getAndroidKeyStorePublicKey(
                        Credentials.USER_PRIVATE_KEY + alias,
                        x509userCert.getPublicKey().getAlgorithm(),
                        x509userCert.getPublicKey().getEncoded()));

        assertEquals("The Subject DN should be the one passed into the params", dn,
                x509userCert.getSubjectDN());

        assertEquals("The Issuer DN should be the same as the Subject DN", dn,
                x509userCert.getIssuerDN());

        assertEquals("The Serial should be the one passed into the params", serial,
                x509userCert.getSerialNumber());

        assertDateEquals("The notBefore date should be the one passed into the params", start,
                x509userCert.getNotBefore());

        assertDateEquals("The notAfter date should be the one passed into the params", end,
                x509userCert.getNotAfter());

        // Assert that the cert's signature verifies using the public key from generated KeyPair
        x509userCert.verify(pubKey);
        // Assert that the cert's signature verifies using the public key from the cert itself.
        x509userCert.verify(x509userCert.getPublicKey());

        final byte[] caCerts = mAndroidKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        assertNull("A list of CA certificates should not exist for the generated entry", caCerts);

        ExportResult exportResult = mAndroidKeyStore.exportKey(
                Credentials.USER_PRIVATE_KEY + alias, KeymasterDefs.KM_KEY_FORMAT_X509, null, null);
        assertEquals(KeyStore.NO_ERROR, exportResult.resultCode);
        final byte[] pubKeyBytes = exportResult.exportData;
        assertNotNull("The keystore should return the public key for the generated key",
                pubKeyBytes);
        assertTrue("Public key X.509 format should be as expected",
                Arrays.equals(pubKey.getEncoded(), pubKeyBytes));
    }

    private static void assertDateEquals(String message, Date date1, Date date2) throws Exception {
        SimpleDateFormat formatter = new SimpleDateFormat("dd MMM yyyy HH:mm:ss");

        String result1 = formatter.format(date1);
        String result2 = formatter.format(date2);

        assertEquals(message, result1, result2);
    }
}
