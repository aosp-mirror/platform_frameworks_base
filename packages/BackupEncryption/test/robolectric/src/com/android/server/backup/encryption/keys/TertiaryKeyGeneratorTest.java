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

package com.android.server.backup.encryption.keys;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.security.SecureRandom;

import javax.crypto.SecretKey;

/** Tests for {@link TertiaryKeyGenerator}. */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TertiaryKeyGeneratorTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    private TertiaryKeyGenerator mTertiaryKeyGenerator;

    /** Instantiate a new {@link TertiaryKeyGenerator} for use in tests. */
    @Before
    public void setUp() {
        mTertiaryKeyGenerator = new TertiaryKeyGenerator(new SecureRandom());
    }

    /** Generated keys should be AES keys. */
    @Test
    public void generate_generatesAESKeys() {
        SecretKey secretKey = mTertiaryKeyGenerator.generate();

        assertThat(secretKey.getAlgorithm()).isEqualTo(KEY_ALGORITHM);
    }

    /** Generated keys should be 256 bits in size. */
    @Test
    public void generate_generates256BitKeys() {
        SecretKey secretKey = mTertiaryKeyGenerator.generate();

        assertThat(secretKey.getEncoded()).hasLength(KEY_SIZE_BITS / 8);
    }

    /**
     * Subsequent calls to {@link TertiaryKeyGenerator#generate()} should generate different keys.
     */
    @Test
    public void generate_generatesNewKeys() {
        SecretKey key1 = mTertiaryKeyGenerator.generate();
        SecretKey key2 = mTertiaryKeyGenerator.generate();

        assertThat(key1).isNotEqualTo(key2);
    }
}
