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
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.DSAParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
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

    public void testKeyPairGenerator_GenerateKeyPair_DSA_Unencrypted_Success() throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeyType("DSA")
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "DSA", 1024, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_DSA_2048_Unencrypted_Success()
            throws Exception {
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeyType("DSA")
                .setKeySize(2048)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "DSA", 2048, null, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_DSA_SpecifiedParams_Unencrypted_Success()
            throws Exception {
        /*
         * generated using: openssl dsaparam -C 2048
         */
        BigInteger p = new BigInteger(1, new byte[] {
                (byte) 0xC0, (byte) 0x3D, (byte) 0x86, (byte) 0x09, (byte) 0xCA, (byte) 0x8C,
                (byte) 0x37, (byte) 0xCA, (byte) 0xCC, (byte) 0x4A, (byte) 0x81, (byte) 0xBD,
                (byte) 0xD8, (byte) 0x50, (byte) 0x77, (byte) 0xCD, (byte) 0xDD, (byte) 0x32,
                (byte) 0x0B, (byte) 0x43, (byte) 0xBF, (byte) 0x42, (byte) 0x06, (byte) 0x5A,
                (byte) 0x3D, (byte) 0x18, (byte) 0x50, (byte) 0x47, (byte) 0x79, (byte) 0xE1,
                (byte) 0x5B, (byte) 0x86, (byte) 0x03, (byte) 0xB9, (byte) 0x28, (byte) 0x9C,
                (byte) 0x18, (byte) 0xA9, (byte) 0xF5, (byte) 0xD6, (byte) 0xF4, (byte) 0x94,
                (byte) 0x5B, (byte) 0x87, (byte) 0x58, (byte) 0xCA, (byte) 0xB2, (byte) 0x1E,
                (byte) 0xFC, (byte) 0xED, (byte) 0x37, (byte) 0xC3, (byte) 0x49, (byte) 0xAC,
                (byte) 0xFA, (byte) 0x46, (byte) 0xDB, (byte) 0x7A, (byte) 0x50, (byte) 0x96,
                (byte) 0xCF, (byte) 0x52, (byte) 0xD7, (byte) 0x4E, (byte) 0xEB, (byte) 0x26,
                (byte) 0x41, (byte) 0xA2, (byte) 0x6F, (byte) 0x99, (byte) 0x80, (byte) 0x9F,
                (byte) 0x0F, (byte) 0x0A, (byte) 0xA8, (byte) 0x0D, (byte) 0xAC, (byte) 0xAB,
                (byte) 0xEF, (byte) 0x7D, (byte) 0xE7, (byte) 0x4C, (byte) 0xF1, (byte) 0x88,
                (byte) 0x44, (byte) 0xC9, (byte) 0x17, (byte) 0xD0, (byte) 0xBB, (byte) 0xE2,
                (byte) 0x01, (byte) 0x8C, (byte) 0xC1, (byte) 0x02, (byte) 0x1D, (byte) 0x3C,
                (byte) 0x15, (byte) 0xB7, (byte) 0x41, (byte) 0x30, (byte) 0xD8, (byte) 0x11,
                (byte) 0xBD, (byte) 0x6A, (byte) 0x2A, (byte) 0x0D, (byte) 0x36, (byte) 0x44,
                (byte) 0x9C, (byte) 0x3F, (byte) 0x32, (byte) 0xE2, (byte) 0x1C, (byte) 0xFB,
                (byte) 0xE3, (byte) 0xFF, (byte) 0xCC, (byte) 0x1A, (byte) 0x72, (byte) 0x38,
                (byte) 0x37, (byte) 0x69, (byte) 0x5E, (byte) 0x35, (byte) 0x73, (byte) 0xE1,
                (byte) 0x1E, (byte) 0x74, (byte) 0x35, (byte) 0x44, (byte) 0x07, (byte) 0xB5,
                (byte) 0x2F, (byte) 0x0B, (byte) 0x60, (byte) 0xF4, (byte) 0xA9, (byte) 0xE0,
                (byte) 0x81, (byte) 0xB2, (byte) 0xCD, (byte) 0x8B, (byte) 0x82, (byte) 0x76,
                (byte) 0x7F, (byte) 0xD4, (byte) 0x17, (byte) 0x32, (byte) 0x86, (byte) 0x98,
                (byte) 0x7C, (byte) 0x85, (byte) 0x66, (byte) 0xF6, (byte) 0x77, (byte) 0xED,
                (byte) 0x8B, (byte) 0x1A, (byte) 0x52, (byte) 0x16, (byte) 0xDA, (byte) 0x1C,
                (byte) 0xA7, (byte) 0x16, (byte) 0x79, (byte) 0x20, (byte) 0x1C, (byte) 0x99,
                (byte) 0x5F, (byte) 0x12, (byte) 0x66, (byte) 0x15, (byte) 0x9F, (byte) 0xE5,
                (byte) 0x73, (byte) 0xA9, (byte) 0x61, (byte) 0xBA, (byte) 0xA7, (byte) 0x23,
                (byte) 0x93, (byte) 0x77, (byte) 0xB5, (byte) 0xF6, (byte) 0xEC, (byte) 0x13,
                (byte) 0xBF, (byte) 0x95, (byte) 0x60, (byte) 0x78, (byte) 0x84, (byte) 0xE3,
                (byte) 0x44, (byte) 0xEC, (byte) 0x74, (byte) 0xC2, (byte) 0xCB, (byte) 0xD4,
                (byte) 0x70, (byte) 0xC5, (byte) 0x7B, (byte) 0xF8, (byte) 0x07, (byte) 0x3B,
                (byte) 0xEB, (byte) 0x9F, (byte) 0xC9, (byte) 0x7D, (byte) 0xE0, (byte) 0xA5,
                (byte) 0xBA, (byte) 0x68, (byte) 0x7B, (byte) 0xF4, (byte) 0x70, (byte) 0x40,
                (byte) 0xAE, (byte) 0xE9, (byte) 0x65, (byte) 0xEE, (byte) 0x5B, (byte) 0x71,
                (byte) 0x36, (byte) 0x0B, (byte) 0xB0, (byte) 0xA2, (byte) 0x98, (byte) 0x7D,
                (byte) 0xE3, (byte) 0x24, (byte) 0x95, (byte) 0x2B, (byte) 0xC2, (byte) 0x0A,
                (byte) 0x78, (byte) 0x3D, (byte) 0xCC, (byte) 0x3A, (byte) 0xEE, (byte) 0xED,
                (byte) 0x48, (byte) 0xEB, (byte) 0xA3, (byte) 0x78, (byte) 0xA8, (byte) 0x9D,
                (byte) 0x0A, (byte) 0x8F, (byte) 0x9E, (byte) 0x59, (byte) 0x2C, (byte) 0x44,
                (byte) 0xB5, (byte) 0xF9, (byte) 0x53, (byte) 0x43,
        });

        BigInteger q = new BigInteger(1, new byte[] {
                (byte) 0xA1, (byte) 0x9B, (byte) 0x1D, (byte) 0xC0, (byte) 0xE3, (byte) 0xF6,
                (byte) 0x4A, (byte) 0x35, (byte) 0xE1, (byte) 0x8A, (byte) 0x43, (byte) 0xC2,
                (byte) 0x9C, (byte) 0xF9, (byte) 0x52, (byte) 0x8F, (byte) 0x94, (byte) 0xA1,
                (byte) 0x12, (byte) 0x11, (byte) 0xDB, (byte) 0x9A, (byte) 0xB6, (byte) 0x35,
                (byte) 0x56, (byte) 0x26, (byte) 0x60, (byte) 0x89, (byte) 0x11, (byte) 0xAC,
                (byte) 0xA8, (byte) 0xE5,
        });

        BigInteger g = new BigInteger(1, new byte[] {
                (byte) 0xA1, (byte) 0x5C, (byte) 0x57, (byte) 0x15, (byte) 0xC3, (byte) 0xD9,
                (byte) 0xD7, (byte) 0x41, (byte) 0x89, (byte) 0xD6, (byte) 0xB8, (byte) 0x7B,
                (byte) 0xF3, (byte) 0xE0, (byte) 0xB3, (byte) 0xC5, (byte) 0xD1, (byte) 0xAA,
                (byte) 0xF9, (byte) 0x55, (byte) 0x48, (byte) 0xF1, (byte) 0xDA, (byte) 0xE8,
                (byte) 0x6F, (byte) 0x51, (byte) 0x05, (byte) 0xB2, (byte) 0xC9, (byte) 0x64,
                (byte) 0xDA, (byte) 0x5F, (byte) 0xD4, (byte) 0xAA, (byte) 0xFD, (byte) 0x67,
                (byte) 0xE0, (byte) 0x10, (byte) 0x2C, (byte) 0x1F, (byte) 0x03, (byte) 0x10,
                (byte) 0xD4, (byte) 0x4B, (byte) 0x20, (byte) 0x82, (byte) 0x2B, (byte) 0x04,
                (byte) 0xF9, (byte) 0x09, (byte) 0xAE, (byte) 0x28, (byte) 0x3D, (byte) 0x9B,
                (byte) 0xFF, (byte) 0x87, (byte) 0x76, (byte) 0xCD, (byte) 0xF0, (byte) 0x11,
                (byte) 0xB7, (byte) 0xEA, (byte) 0xE6, (byte) 0xCD, (byte) 0x60, (byte) 0xD3,
                (byte) 0x8C, (byte) 0x74, (byte) 0xD3, (byte) 0x45, (byte) 0x63, (byte) 0x69,
                (byte) 0x3F, (byte) 0x1D, (byte) 0x31, (byte) 0x25, (byte) 0x49, (byte) 0x97,
                (byte) 0x4B, (byte) 0x73, (byte) 0x34, (byte) 0x12, (byte) 0x73, (byte) 0x27,
                (byte) 0x4C, (byte) 0xDA, (byte) 0xF3, (byte) 0x08, (byte) 0xA8, (byte) 0xA9,
                (byte) 0x27, (byte) 0xE4, (byte) 0xB8, (byte) 0xD6, (byte) 0xB5, (byte) 0xC4,
                (byte) 0x18, (byte) 0xED, (byte) 0xBD, (byte) 0x6F, (byte) 0xA2, (byte) 0x36,
                (byte) 0xA2, (byte) 0x9C, (byte) 0x27, (byte) 0x62, (byte) 0x7F, (byte) 0x93,
                (byte) 0xD7, (byte) 0x52, (byte) 0xA9, (byte) 0x76, (byte) 0x55, (byte) 0x99,
                (byte) 0x00, (byte) 0x5B, (byte) 0xC2, (byte) 0xB9, (byte) 0x18, (byte) 0xAC,
                (byte) 0x6B, (byte) 0x83, (byte) 0x0D, (byte) 0xA1, (byte) 0xC5, (byte) 0x01,
                (byte) 0x1A, (byte) 0xE5, (byte) 0x4D, (byte) 0x2F, (byte) 0xCF, (byte) 0x5D,
                (byte) 0xB2, (byte) 0xE7, (byte) 0xC7, (byte) 0xCB, (byte) 0x2C, (byte) 0xFF,
                (byte) 0x51, (byte) 0x1B, (byte) 0x9D, (byte) 0xA4, (byte) 0x05, (byte) 0xEB,
                (byte) 0x17, (byte) 0xD8, (byte) 0x97, (byte) 0x9D, (byte) 0x0C, (byte) 0x59,
                (byte) 0x92, (byte) 0x8A, (byte) 0x03, (byte) 0x34, (byte) 0xFD, (byte) 0x16,
                (byte) 0x0F, (byte) 0x2A, (byte) 0xF9, (byte) 0x7D, (byte) 0xC3, (byte) 0x41,
                (byte) 0x0D, (byte) 0x06, (byte) 0x5A, (byte) 0x4B, (byte) 0x34, (byte) 0xD5,
                (byte) 0xF5, (byte) 0x09, (byte) 0x1C, (byte) 0xCE, (byte) 0xA7, (byte) 0x19,
                (byte) 0x6D, (byte) 0x04, (byte) 0x53, (byte) 0x71, (byte) 0xCC, (byte) 0x84,
                (byte) 0xA0, (byte) 0xB2, (byte) 0xA0, (byte) 0x68, (byte) 0xA3, (byte) 0x40,
                (byte) 0xC0, (byte) 0x67, (byte) 0x38, (byte) 0x96, (byte) 0x73, (byte) 0x2E,
                (byte) 0x8E, (byte) 0x2A, (byte) 0x9D, (byte) 0x56, (byte) 0xE9, (byte) 0xAC,
                (byte) 0xC7, (byte) 0xEC, (byte) 0x84, (byte) 0x7F, (byte) 0xFC, (byte) 0xE0,
                (byte) 0x69, (byte) 0x03, (byte) 0x8B, (byte) 0x48, (byte) 0x64, (byte) 0x76,
                (byte) 0x85, (byte) 0xA5, (byte) 0x10, (byte) 0xD9, (byte) 0x31, (byte) 0xC3,
                (byte) 0x8B, (byte) 0x07, (byte) 0x48, (byte) 0x62, (byte) 0xF6, (byte) 0x68,
                (byte) 0xF2, (byte) 0x96, (byte) 0xB2, (byte) 0x18, (byte) 0x5B, (byte) 0xFF,
                (byte) 0x6D, (byte) 0xD1, (byte) 0x6B, (byte) 0xF5, (byte) 0xFD, (byte) 0x81,
                (byte) 0xF1, (byte) 0xFD, (byte) 0x04, (byte) 0xF0, (byte) 0x9F, (byte) 0xB7,
                (byte) 0x08, (byte) 0x95, (byte) 0x57, (byte) 0x48, (byte) 0x07, (byte) 0x00,
                (byte) 0x52, (byte) 0xEC, (byte) 0x75, (byte) 0x91, (byte) 0x02, (byte) 0x11,
                (byte) 0xA3, (byte) 0x64, (byte) 0x26, (byte) 0xCA,
        });

        AlgorithmParameterSpec spec = new DSAParameterSpec(p, q, g);
        mGenerator.initialize(new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setKeyType("DSA")
                .setKeySize(2048)
                .setAlgorithmParameterSpec(spec)
                .setSubject(TEST_DN_1)
                .setSerialNumber(TEST_SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .build());

        final KeyPair pair = mGenerator.generateKeyPair();
        assertNotNull("The KeyPair returned should not be null", pair);

        assertKeyPairCorrect(pair, TEST_ALIAS_1, "DSA", 2048, spec, TEST_DN_1, TEST_SERIAL_1, NOW,
                NOW_PLUS_10_YEARS);
    }

    public void testKeyPairGenerator_GenerateKeyPair_EC_Unencrypted_Success() throws Exception {
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

            assertTrue(mAndroidKeyStore.password("1111"));
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

        if ("DSA".equalsIgnoreCase(keyType)) {
            DSAPublicKey dsaPubKey = (DSAPublicKey) pubKey;
            DSAParams actualParams = dsaPubKey.getParams();
            assertEquals(keySize, (actualParams.getP().bitLength() + 7) & ~7);
            if (spec != null) {
                DSAParameterSpec expectedParams = (DSAParameterSpec) spec;
                assertEquals(expectedParams.getP(), actualParams.getP());
                assertEquals(expectedParams.getQ(), actualParams.getQ());
                assertEquals(expectedParams.getG(), actualParams.getG());
            }
        } else if ("EC".equalsIgnoreCase(keyType)) {
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
