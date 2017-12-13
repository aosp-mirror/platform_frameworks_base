/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncUtilsTest {
    private static final int RECOVERY_KEY_LENGTH_BITS = 256;
    private static final int THM_KF_HASH_SIZE = 256;
    private static final String SHA_256_ALGORITHM = "SHA-256";

    @Test
    public void calculateThmKfHash_isShaOfLockScreenHashWithPrefix() throws Exception {
        byte[] lockScreenHash = utf8Bytes("012345678910");

        byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(lockScreenHash);

        assertArrayEquals(calculateSha256(utf8Bytes("THM_KF_hash012345678910")), thmKfHash);
    }

    @Test
    public void calculateThmKfHash_is256BitsLong() throws Exception {
        byte[] thmKfHash = KeySyncUtils.calculateThmKfHash(utf8Bytes("1234"));

        assertEquals(THM_KF_HASH_SIZE / Byte.SIZE, thmKfHash.length);
    }

    @Test
    public void generateRecoveryKey_returnsA256BitKey() throws Exception {
        SecretKey key = KeySyncUtils.generateRecoveryKey();

        assertEquals(RECOVERY_KEY_LENGTH_BITS / Byte.SIZE, key.getEncoded().length);
    }

    @Test
    public void generateRecoveryKey_generatesANewKeyEachTime() throws Exception {
        SecretKey a = KeySyncUtils.generateRecoveryKey();
        SecretKey b = KeySyncUtils.generateRecoveryKey();

        assertFalse(Arrays.equals(a.getEncoded(), b.getEncoded()));
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] calculateSha256(byte[] bytes) throws Exception {
        MessageDigest messageDigest = MessageDigest.getInstance(SHA_256_ALGORITHM);
        messageDigest.update(bytes);
        return messageDigest.digest();
    }
}
