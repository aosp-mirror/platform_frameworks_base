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

import java.math.BigInteger;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

public class KeyPairGeneratorSpecTest extends AndroidTestCase {
    private static final String TEST_ALIAS_1 = "test1";

    private static final X500Principal TEST_DN_1 = new X500Principal("CN=test1");

    private static final long NOW_MILLIS = System.currentTimeMillis();

    private static final BigInteger SERIAL_1 = BigInteger.ONE;

    /* We have to round this off because X509v3 doesn't store milliseconds. */
    private static final Date NOW = new Date(NOW_MILLIS - (NOW_MILLIS % 1000L));

    @SuppressWarnings("deprecation")
    private static final Date NOW_PLUS_10_YEARS = new Date(NOW.getYear() + 10, 0, 1);

    public void testConstructor_Success() throws Exception {
        KeyPairGeneratorSpec spec =
                new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, TEST_DN_1, SERIAL_1,
                        NOW, NOW_PLUS_10_YEARS, 0);

        assertEquals("Context should be the one specified", getContext(), spec.getContext());

        assertEquals("Alias should be the one specified", TEST_ALIAS_1, spec.getKeystoreAlias());

        assertEquals("subjectDN should be the one specified", TEST_DN_1, spec.getSubjectDN());

        assertEquals("startDate should be the one specified", NOW, spec.getStartDate());

        assertEquals("endDate should be the one specified", NOW_PLUS_10_YEARS, spec.getEndDate());
    }

    public void testBuilder_Success() throws Exception {
        KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(getContext())
                .setAlias(TEST_ALIAS_1)
                .setSubject(TEST_DN_1)
                .setSerialNumber(SERIAL_1)
                .setStartDate(NOW)
                .setEndDate(NOW_PLUS_10_YEARS)
                .setEncryptionRequired()
                .build();

        assertEquals("Context should be the one specified", getContext(), spec.getContext());

        assertEquals("Alias should be the one specified", TEST_ALIAS_1, spec.getKeystoreAlias());

        assertEquals("subjectDN should be the one specified", TEST_DN_1, spec.getSubjectDN());

        assertEquals("startDate should be the one specified", NOW, spec.getStartDate());

        assertEquals("endDate should be the one specified", NOW_PLUS_10_YEARS, spec.getEndDate());

        assertEquals("encryption flag should be on", KeyStore.FLAG_ENCRYPTED, spec.getFlags());
    }

    public void testConstructor_NullContext_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(null, TEST_ALIAS_1, TEST_DN_1, SERIAL_1, NOW,
                    NOW_PLUS_10_YEARS, 0);
            fail("Should throw IllegalArgumentException when context is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_NullKeystoreAlias_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), null, TEST_DN_1, SERIAL_1, NOW,
                    NOW_PLUS_10_YEARS, 0);
            fail("Should throw IllegalArgumentException when keystoreAlias is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_NullSubjectDN_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, null, SERIAL_1, NOW,
                    NOW_PLUS_10_YEARS, 0);
            fail("Should throw IllegalArgumentException when subjectDN is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_NullSerial_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, TEST_DN_1, null, NOW,
                    NOW_PLUS_10_YEARS, 0);
            fail("Should throw IllegalArgumentException when startDate is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_NullStartDate_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, TEST_DN_1, SERIAL_1, null,
                    NOW_PLUS_10_YEARS, 0);
            fail("Should throw IllegalArgumentException when startDate is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_NullEndDate_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, TEST_DN_1, SERIAL_1, NOW,
                    null, 0);
            fail("Should throw IllegalArgumentException when keystoreAlias is null");
        } catch (IllegalArgumentException success) {
        }
    }

    public void testConstructor_EndBeforeStart_Failure() throws Exception {
        try {
            new KeyPairGeneratorSpec(getContext(), TEST_ALIAS_1, TEST_DN_1, SERIAL_1,
                    NOW_PLUS_10_YEARS, NOW, 0);
            fail("Should throw IllegalArgumentException when end is before start");
        } catch (IllegalArgumentException success) {
        }
    }
}
