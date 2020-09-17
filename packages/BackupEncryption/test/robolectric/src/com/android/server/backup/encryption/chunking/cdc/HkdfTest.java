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

package com.android.server.backup.encryption.chunking.cdc;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link Hkdf}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class HkdfTest {
    /** HKDF Test Case 1 IKM from RFC 5869 */
    private static final byte[] HKDF_CASE1_IKM = {
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b
    };

    /** HKDF Test Case 1 salt from RFC 5869 */
    private static final byte[] HKDF_CASE1_SALT = {
        0x00, 0x01, 0x02, 0x03, 0x04,
        0x05, 0x06, 0x07, 0x08, 0x09,
        0x0a, 0x0b, 0x0c
    };

    /** HKDF Test Case 1 info from RFC 5869 */
    private static final byte[] HKDF_CASE1_INFO = {
        (byte) 0xf0, (byte) 0xf1, (byte) 0xf2, (byte) 0xf3, (byte) 0xf4,
        (byte) 0xf5, (byte) 0xf6, (byte) 0xf7, (byte) 0xf8, (byte) 0xf9
    };

    /** First 32 bytes of HKDF Test Case 1 OKM (output) from RFC 5869 */
    private static final byte[] HKDF_CASE1_OKM = {
        (byte) 0x3c, (byte) 0xb2, (byte) 0x5f, (byte) 0x25, (byte) 0xfa,
        (byte) 0xac, (byte) 0xd5, (byte) 0x7a, (byte) 0x90, (byte) 0x43,
        (byte) 0x4f, (byte) 0x64, (byte) 0xd0, (byte) 0x36, (byte) 0x2f,
        (byte) 0x2a, (byte) 0x2d, (byte) 0x2d, (byte) 0x0a, (byte) 0x90,
        (byte) 0xcf, (byte) 0x1a, (byte) 0x5a, (byte) 0x4c, (byte) 0x5d,
        (byte) 0xb0, (byte) 0x2d, (byte) 0x56, (byte) 0xec, (byte) 0xc4,
        (byte) 0xc5, (byte) 0xbf
    };

    /** Test the example from RFC 5869. */
    @Test
    public void hkdf_derivesKeyMaterial() throws Exception {
        byte[] result = Hkdf.hkdf(HKDF_CASE1_IKM, HKDF_CASE1_SALT, HKDF_CASE1_INFO);

        assertThat(result).isEqualTo(HKDF_CASE1_OKM);
    }

    /** Providing a key that is null should throw a {@link java.lang.NullPointerException}. */
    @Test
    public void hkdf_withNullKey_throwsNullPointerException() throws Exception {
        assertThrows(
                NullPointerException.class,
                () -> Hkdf.hkdf(null, HKDF_CASE1_SALT, HKDF_CASE1_INFO));
    }

    /** Providing a salt that is null should throw a {@link java.lang.NullPointerException}. */
    @Test
    public void hkdf_withNullSalt_throwsNullPointerException() throws Exception {
        assertThrows(
                NullPointerException.class, () -> Hkdf.hkdf(HKDF_CASE1_IKM, null, HKDF_CASE1_INFO));
    }

    /** Providing data that is null should throw a {@link java.lang.NullPointerException}. */
    @Test
    public void hkdf_withNullData_throwsNullPointerException() throws Exception {
        assertThrows(
                NullPointerException.class, () -> Hkdf.hkdf(HKDF_CASE1_IKM, HKDF_CASE1_SALT, null));
    }
}
