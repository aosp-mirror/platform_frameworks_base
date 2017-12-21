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

import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PASSWORD;
import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PATTERN;
import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PIN;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncTaskTest {

    @Test
    public void isPin_isTrueForNumericString() {
        assertTrue(KeySyncTask.isPin("3298432574398654376547"));
    }

    @Test
    public void isPin_isFalseForStringContainingLetters() {
        assertFalse(KeySyncTask.isPin("398i54369548654"));
    }

    @Test
    public void isPin_isFalseForStringContainingSymbols() {
        assertFalse(KeySyncTask.isPin("-3987543643"));
    }

    @Test
    public void hashCredentials_returnsSameHashForSameCredentialsAndSalt() {
        String credentials = "password1234";
        byte[] salt = randomBytes(16);

        assertArrayEquals(
                KeySyncTask.hashCredentials(salt, credentials),
                KeySyncTask.hashCredentials(salt, credentials));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentCredentials() {
        byte[] salt = randomBytes(16);

        assertFalse(
                Arrays.equals(
                    KeySyncTask.hashCredentials(salt, "password1234"),
                    KeySyncTask.hashCredentials(salt, "password12345")));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentSalt() {
        String credentials = "wowmuch";

        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(randomBytes(64), credentials),
                        KeySyncTask.hashCredentials(randomBytes(64), credentials)));
    }

    @Test
    public void hashCredentials_returnsDifferentHashEvenIfConcatIsSame() {
        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(utf8Bytes("123"), "4567"),
                        KeySyncTask.hashCredentials(utf8Bytes("1234"), "567")));
    }

    @Test
    public void getUiFormat_returnsPinIfPin() {
        assertEquals(TYPE_PIN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234"));
    }

    @Test
    public void getUiFormat_returnsPasswordIfPassword() {
        assertEquals(TYPE_PASSWORD,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234a"));
    }

    @Test
    public void getUiFormat_returnsPatternIfPattern() {
        assertEquals(TYPE_PATTERN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PATTERN, "1234"));

    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }
}
