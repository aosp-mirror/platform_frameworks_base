/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.keys;

import static com.android.server.backup.testing.CryptoTestUtils.generateAesKey;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.security.InvalidKeyException;

import javax.crypto.SecretKey;

/** Key wrapping tests */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class KeyWrapUtilsTest {
    private static final int KEY_SIZE_BITS = 256;
    private static final int BITS_PER_BYTE = 8;
    private static final int GCM_NONCE_LENGTH_BYTES = 16;
    private static final int GCM_TAG_LENGTH_BYTES = 16;

    /** Test a wrapped key has metadata */
    @Test
    public void wrap_addsMetadata() throws Exception {
        WrappedKeyProto.WrappedKey wrappedKey =
                KeyWrapUtils.wrap(
                        /*secondaryKey=*/ generateAesKey(), /*tertiaryKey=*/ generateAesKey());
        assertThat(wrappedKey.metadata).isNotNull();
        assertThat(wrappedKey.metadata.type).isEqualTo(WrappedKeyProto.KeyMetadata.AES_256_GCM);
    }

    /** Test a wrapped key has an algorithm specified */
    @Test
    public void wrap_addsWrapAlgorithm() throws Exception {
        WrappedKeyProto.WrappedKey wrappedKey =
                KeyWrapUtils.wrap(
                        /*secondaryKey=*/ generateAesKey(), /*tertiaryKey=*/ generateAesKey());
        assertThat(wrappedKey.wrapAlgorithm).isEqualTo(WrappedKeyProto.WrappedKey.AES_256_GCM);
    }

    /** Test a wrapped key haas an nonce of the right length */
    @Test
    public void wrap_addsNonceOfAppropriateLength() throws Exception {
        WrappedKeyProto.WrappedKey wrappedKey =
                KeyWrapUtils.wrap(
                        /*secondaryKey=*/ generateAesKey(), /*tertiaryKey=*/ generateAesKey());
        assertThat(wrappedKey.nonce).hasLength(GCM_NONCE_LENGTH_BYTES);
    }

    /** Test a wrapped key has a key of the right length */
    @Test
    public void wrap_addsTagOfAppropriateLength() throws Exception {
        WrappedKeyProto.WrappedKey wrappedKey =
                KeyWrapUtils.wrap(
                        /*secondaryKey=*/ generateAesKey(), /*tertiaryKey=*/ generateAesKey());
        assertThat(wrappedKey.key).hasLength(KEY_SIZE_BITS / BITS_PER_BYTE + GCM_TAG_LENGTH_BYTES);
    }

    /** Ensure a key can be wrapped and unwrapped again */
    @Test
    public void unwrap_unwrapsEncryptedKey() throws Exception {
        SecretKey secondaryKey = generateAesKey();
        SecretKey tertiaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedKey = KeyWrapUtils.wrap(secondaryKey, tertiaryKey);
        SecretKey unwrappedKey = KeyWrapUtils.unwrap(secondaryKey, wrappedKey);
        assertThat(unwrappedKey).isEqualTo(tertiaryKey);
    }

    /** Ensure the unwrap method rejects keys with bad algorithms */
    @Test(expected = InvalidKeyException.class)
    public void unwrap_throwsForBadWrapAlgorithm() throws Exception {
        SecretKey secondaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedKey = KeyWrapUtils.wrap(secondaryKey, generateAesKey());
        wrappedKey.wrapAlgorithm = WrappedKeyProto.WrappedKey.UNKNOWN;

        KeyWrapUtils.unwrap(secondaryKey, wrappedKey);
    }

    /** Ensure the unwrap method rejects metadata indicating the encryption type is unknown */
    @Test(expected = InvalidKeyException.class)
    public void unwrap_throwsForBadKeyAlgorithm() throws Exception {
        SecretKey secondaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedKey = KeyWrapUtils.wrap(secondaryKey, generateAesKey());
        wrappedKey.metadata.type = WrappedKeyProto.KeyMetadata.UNKNOWN;

        KeyWrapUtils.unwrap(secondaryKey, wrappedKey);
    }

    /** Ensure the unwrap method rejects wrapped keys missing the metadata */
    @Test(expected = InvalidKeyException.class)
    public void unwrap_throwsForMissingMetadata() throws Exception {
        SecretKey secondaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedKey = KeyWrapUtils.wrap(secondaryKey, generateAesKey());
        wrappedKey.metadata = null;

        KeyWrapUtils.unwrap(secondaryKey, wrappedKey);
    }

    /** Ensure unwrap rejects invalid secondary keys */
    @Test(expected = InvalidKeyException.class)
    public void unwrap_throwsForBadSecondaryKey() throws Exception {
        WrappedKeyProto.WrappedKey wrappedKey =
                KeyWrapUtils.wrap(
                        /*secondaryKey=*/ generateAesKey(), /*tertiaryKey=*/ generateAesKey());

        KeyWrapUtils.unwrap(generateAesKey(), wrappedKey);
    }

    /** Ensure rewrap can rewrap keys */
    @Test
    public void rewrap_canBeUnwrappedWithNewSecondaryKey() throws Exception {
        SecretKey tertiaryKey = generateAesKey();
        SecretKey oldSecondaryKey = generateAesKey();
        SecretKey newSecondaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedWithOld = KeyWrapUtils.wrap(oldSecondaryKey, tertiaryKey);

        WrappedKeyProto.WrappedKey wrappedWithNew =
                KeyWrapUtils.rewrap(oldSecondaryKey, newSecondaryKey, wrappedWithOld);

        assertThat(KeyWrapUtils.unwrap(newSecondaryKey, wrappedWithNew)).isEqualTo(tertiaryKey);
    }

    /** Ensure rewrap doesn't create something decryptable by an old key */
    @Test(expected = InvalidKeyException.class)
    public void rewrap_cannotBeUnwrappedWithOldSecondaryKey() throws Exception {
        SecretKey tertiaryKey = generateAesKey();
        SecretKey oldSecondaryKey = generateAesKey();
        SecretKey newSecondaryKey = generateAesKey();
        WrappedKeyProto.WrappedKey wrappedWithOld = KeyWrapUtils.wrap(oldSecondaryKey, tertiaryKey);

        WrappedKeyProto.WrappedKey wrappedWithNew =
                KeyWrapUtils.rewrap(oldSecondaryKey, newSecondaryKey, wrappedWithOld);

        KeyWrapUtils.unwrap(oldSecondaryKey, wrappedWithNew);
    }
}
