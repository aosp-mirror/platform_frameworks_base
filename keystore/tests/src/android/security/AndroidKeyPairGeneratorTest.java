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

package android.security;

import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
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
        assertTrue(mAndroidKeyStore.password("1111"));
        assertTrue(mAndroidKeyStore.isUnlocked());

        String[] aliases = mAndroidKeyStore.saw("");
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

        assertKeyPairCorrect(pair, TEST_ALIAS_1, TEST_DN_1, TEST_SERIAL_1, NOW, NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_Unencrypted_Success() throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, TEST_DN_1, TEST_SERIAL_1, NOW, NOW_PLUS_10_YEARS);
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
            assertKeyPairCorrect(pair1, TEST_ALIAS_1, TEST_DN_1, TEST_SERIAL_1, NOW,
                    NOW_PLUS_10_YEARS);
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
            assertKeyPairCorrect(pair2, TEST_ALIAS_2, TEST_DN_2, TEST_SERIAL_2, NOW,
                    NOW_PLUS_10_YEARS);
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
            assertKeyPairCorrect(pair1, TEST_ALIAS_1, TEST_DN_1, TEST_SERIAL_1, NOW,
                    NOW_PLUS_10_YEARS);
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

            assertTrue(mAndroidKeyStore.password("1111"));
            assertTrue(mAndroidKeyStore.isUnlocked());

            final KeyPair pair2 = mGenerator.generateKeyPair();
            assertNotNull("The KeyPair returned should not be null", pair2);
            assertKeyPairCorrect(pair2, TEST_ALIAS_1, TEST_DN_2, TEST_SERIAL_2, NOW,
                    NOW_PLUS_10_YEARS);
        }
    }

    private void assertKeyPairCorrect(KeyPair pair, String alias, X500Principal dn,
            BigInteger serial, Date start, Date end) throws Exception {
        final PublicKey pubKey = pair.getPublic();
        assertNotNull("The PublicKey for the KeyPair should be not null", pubKey);

        final PrivateKey privKey = pair.getPrivate();
        assertNotNull("The PrivateKey for the KeyPair should be not null", privKey);

        final byte[] userCertBytes = mAndroidKeyStore.get(Credentials.USER_CERTIFICATE + alias);
        assertNotNull("The user certificate should exist for the generated entry", userCertBytes);

        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final Certificate userCert = cf
                .generateCertificate(new ByteArrayInputStream(userCertBytes));

        assertTrue("Certificate should be in X.509 format", userCert instanceof X509Certificate);

        final X509Certificate x509userCert = (X509Certificate) userCert;

        assertEquals("PublicKey used to sign certificate should match one returned in KeyPair",
                pubKey, x509userCert.getPublicKey());

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

        x509userCert.verify(pubKey);

        final byte[] caCerts = mAndroidKeyStore.get(Credentials.CA_CERTIFICATE + alias);
        assertNull("A list of CA certificates should not exist for the generated entry", caCerts);

        final byte[] pubKeyBytes = mAndroidKeyStore.getPubkey(Credentials.USER_PRIVATE_KEY + alias);
        assertNotNull("The keystore should return the public key for the generated key",
                pubKeyBytes);
    }

    private static void assertDateEquals(String message, Date date1, Date date2) throws Exception {
        SimpleDateFormat formatter = new SimpleDateFormat("dd MMM yyyy HH:mm:ss");

        String result1 = formatter.format(date1);
        String result2 = formatter.format(date2);

        assertEquals(message, result1, result2);
    }
}
