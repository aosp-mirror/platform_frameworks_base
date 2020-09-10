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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.Presubmit;

import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.security.InvalidKeyException;
import java.security.KeyException;
import java.security.SecureRandom;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Test the restore key fetcher */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class RestoreKeyFetcherTest {

    private static final String KEY_GENERATOR_ALGORITHM = "AES";

    private static final String TEST_SECONDARY_KEY_ALIAS = "test_2ndary_key";
    private static final byte[] TEST_SECONDARY_KEY_BYTES = new byte[256 / Byte.SIZE];

    @Mock private RecoverableKeyStoreSecondaryKeyManager mSecondaryKeyManager;

    /** Initialise the mocks **/
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /** Ensure the unwrap method works as expected */
    @Test
    public void unwrapTertiaryKey_returnsUnwrappedKey() throws Exception {
        RecoverableKeyStoreSecondaryKey secondaryKey = createSecondaryKey();
        SecretKey tertiaryKey = createTertiaryKey();
        WrappedKeyProto.WrappedKey wrappedTertiaryKey =
                KeyWrapUtils.wrap(secondaryKey.getSecretKey(), tertiaryKey);
        when(mSecondaryKeyManager.get(TEST_SECONDARY_KEY_ALIAS))
                .thenReturn(Optional.of(secondaryKey));

        SecretKey actualTertiaryKey =
                RestoreKeyFetcher.unwrapTertiaryKey(
                        () -> mSecondaryKeyManager,
                        TEST_SECONDARY_KEY_ALIAS,
                        wrappedTertiaryKey);

        assertThat(actualTertiaryKey).isEqualTo(tertiaryKey);
    }

    /** Ensure that missing secondary keys are detected and an appropriate exception is thrown */
    @Test
    public void unwrapTertiaryKey_missingSecondaryKey_throwsSpecificException() throws Exception {
        WrappedKeyProto.WrappedKey wrappedTertiaryKey =
                KeyWrapUtils.wrap(createSecondaryKey().getSecretKey(), createTertiaryKey());
        when(mSecondaryKeyManager.get(TEST_SECONDARY_KEY_ALIAS)).thenReturn(Optional.empty());

        assertThrows(
                KeyException.class,
                () ->
                        RestoreKeyFetcher.unwrapTertiaryKey(
                                () -> mSecondaryKeyManager,
                                TEST_SECONDARY_KEY_ALIAS,
                                wrappedTertiaryKey));
    }

    /** Ensure that invalid secondary keys are detected and an appropriate exception is thrown */
    @Test
    public void unwrapTertiaryKey_badSecondaryKey_throws() throws Exception {
        RecoverableKeyStoreSecondaryKey badSecondaryKey =
                new RecoverableKeyStoreSecondaryKey(
                        TEST_SECONDARY_KEY_ALIAS,
                        new SecretKeySpec(new byte[] {0, 1}, KEY_GENERATOR_ALGORITHM));

        WrappedKeyProto.WrappedKey wrappedTertiaryKey =
                KeyWrapUtils.wrap(createSecondaryKey().getSecretKey(), createTertiaryKey());
        when(mSecondaryKeyManager.get(TEST_SECONDARY_KEY_ALIAS))
                .thenReturn(Optional.of(badSecondaryKey));

        assertThrows(
                InvalidKeyException.class,
                () ->
                        RestoreKeyFetcher.unwrapTertiaryKey(
                                () -> mSecondaryKeyManager,
                                TEST_SECONDARY_KEY_ALIAS,
                                wrappedTertiaryKey));
    }

    private static RecoverableKeyStoreSecondaryKey createSecondaryKey() {
        return new RecoverableKeyStoreSecondaryKey(
                TEST_SECONDARY_KEY_ALIAS,
                new SecretKeySpec(TEST_SECONDARY_KEY_BYTES, KEY_GENERATOR_ALGORITHM));
    }

    private static SecretKey createTertiaryKey() {
        return new TertiaryKeyGenerator(new SecureRandom(new byte[] {0})).generate();
    }
}
