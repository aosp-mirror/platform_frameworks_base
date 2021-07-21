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

package com.android.internal.widget;


import static com.google.common.truth.Truth.assertThat;

import android.test.AndroidTestCase;

import java.util.Arrays;


public class LockscreenCredentialTest extends AndroidTestCase {

    public void testEmptyCredential() {
        LockscreenCredential empty = LockscreenCredential.createNone();

        assertTrue(empty.isNone());
        assertEquals(0, empty.size());
        assertNotNull(empty.getCredential());

        assertFalse(empty.isPin());
        assertFalse(empty.isPassword());
        assertFalse(empty.isPattern());
    }

    public void testPinCredential() {
        LockscreenCredential pin = LockscreenCredential.createPin("3456");

        assertTrue(pin.isPin());
        assertEquals(4, pin.size());
        assertTrue(Arrays.equals("3456".getBytes(), pin.getCredential()));

        assertFalse(pin.isNone());
        assertFalse(pin.isPassword());
        assertFalse(pin.isPattern());
    }

    public void testPasswordCredential() {
        LockscreenCredential password = LockscreenCredential.createPassword("password");

        assertTrue(password.isPassword());
        assertEquals(8, password.size());
        assertTrue(Arrays.equals("password".getBytes(), password.getCredential()));

        assertFalse(password.isNone());
        assertFalse(password.isPin());
        assertFalse(password.isPattern());
    }

    public void testPatternCredential() {
        LockscreenCredential pattern = LockscreenCredential.createPattern(Arrays.asList(
                LockPatternView.Cell.of(0, 0),
                LockPatternView.Cell.of(0, 1),
                LockPatternView.Cell.of(0, 2),
                LockPatternView.Cell.of(1, 2),
                LockPatternView.Cell.of(2, 2)
                ));

        assertTrue(pattern.isPattern());
        assertEquals(5, pattern.size());
        assertTrue(Arrays.equals("12369".getBytes(), pattern.getCredential()));

        assertFalse(pattern.isNone());
        assertFalse(pattern.isPin());
        assertFalse(pattern.isPassword());
    }

    public void testPasswordOrNoneCredential() {
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPasswordOrNone(null));
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPasswordOrNone(""));
        assertEquals(LockscreenCredential.createPassword("abcd"),
                LockscreenCredential.createPasswordOrNone("abcd"));
    }

    public void testPinOrNoneCredential() {
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPinOrNone(null));
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPinOrNone(""));
        assertEquals(LockscreenCredential.createPin("1357"),
                LockscreenCredential.createPinOrNone("1357"));
    }

    public void testSanitize() {
        LockscreenCredential password = LockscreenCredential.createPassword("password");
        password.zeroize();

        try {
            password.isNone();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.isPattern();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.isPin();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.isPassword();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.size();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.getCredential();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
    }

    public void testEquals() {
        assertEquals(LockscreenCredential.createNone(), LockscreenCredential.createNone());
        assertEquals(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPassword("1234"));
        assertEquals(LockscreenCredential.createPin("4321"),
                LockscreenCredential.createPin("4321"));
        assertEquals(createPattern("1234"), createPattern("1234"));

        assertNotSame(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createNone());
        assertNotSame(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPassword("4321"));
        assertNotSame(LockscreenCredential.createPassword("1234"),
                createPattern("1234"));
        assertNotSame(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPin("1234"));

        assertNotSame(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createNone());
        assertNotSame(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createPin("2222"));
        assertNotSame(LockscreenCredential.createPin("1111"),
                createPattern("1111"));
        assertNotSame(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createPassword("1111"));

        assertNotSame(createPattern("5678"),
                LockscreenCredential.createNone());
        assertNotSame(createPattern("5678"),
                createPattern("1234"));
        assertNotSame(createPattern("5678"),
                LockscreenCredential.createPassword("5678"));
        assertNotSame(createPattern("5678"),
                LockscreenCredential.createPin("5678"));
    }

    public void testDuplicate() {
        LockscreenCredential credential;

        credential = LockscreenCredential.createNone();
        assertEquals(credential, credential.duplicate());
        credential = LockscreenCredential.createPassword("abcd");
        assertEquals(credential, credential.duplicate());
        credential = LockscreenCredential.createPin("1234");
        assertEquals(credential, credential.duplicate());
        credential = createPattern("5678");
        assertEquals(credential, credential.duplicate());
    }

    public void testPasswordToHistoryHash() {
        String password = "1234";
        LockscreenCredential credential = LockscreenCredential.createPassword(password);
        String hashFactor = "6637D20C0798382D9F1304861C81DE222BC6CB7183623C67DA99B115A7AF702D";
        String salt = "6d5331dd120077a0";
        String expectedHash = "BCFB17409F2CD0A00D8627F76D080FB547B0B6A30CB7A375A34720D2312EDAC7";

        assertThat(
                credential.passwordToHistoryHash(salt.getBytes(), hashFactor.getBytes()))
                .isEqualTo(expectedHash);
        assertThat(
                LockscreenCredential.passwordToHistoryHash(
                        password.getBytes(), salt.getBytes(), hashFactor.getBytes()))
                .isEqualTo(expectedHash);
    }

    public void testPasswordToHistoryHashInvalidInput() {
        String password = "1234";
        LockscreenCredential credential = LockscreenCredential.createPassword(password);
        String hashFactor = "6637D20C0798382D9F1304861C81DE222BC6CB7183623C67DA99B115A7AF702D";
        String salt = "6d5331dd120077a0";

        assertThat(
                credential.passwordToHistoryHash(/* salt= */ null, hashFactor.getBytes()))
                .isNull();
        assertThat(
                LockscreenCredential.passwordToHistoryHash(
                        password.getBytes(), /* salt= */ null, hashFactor.getBytes()))
                .isNull();

        assertThat(
                credential.passwordToHistoryHash(salt.getBytes(), /* hashFactor= */ null))
                .isNull();
        assertThat(
                LockscreenCredential.passwordToHistoryHash(
                        password.getBytes(), salt.getBytes(), /* hashFactor= */ null))
                .isNull();

        assertThat(
                LockscreenCredential.passwordToHistoryHash(
                        /* password= */ null, salt.getBytes(), hashFactor.getBytes()))
                .isNull();
    }

    public void testLegacyPasswordToHash() {
        String password = "1234";
        LockscreenCredential credential = LockscreenCredential.createPassword(password);
        String salt = "6d5331dd120077a0";
        String expectedHash =
                "2DD04348ADBF8F4CABD7F722DC2E2887FAD4B6020A0C3E02C831E09946F0554FDC13B155";

        assertThat(
                credential.legacyPasswordToHash(salt.getBytes()))
                .isEqualTo(expectedHash);
        assertThat(
                LockscreenCredential.legacyPasswordToHash(
                        password.getBytes(), salt.getBytes()))
                .isEqualTo(expectedHash);
    }

    public void testLegacyPasswordToHashInvalidInput() {
        String password = "1234";
        LockscreenCredential credential = LockscreenCredential.createPassword(password);
        String salt = "6d5331dd120077a0";

        assertThat(credential.legacyPasswordToHash(/* salt= */ null)).isNull();
        assertThat(LockscreenCredential.legacyPasswordToHash(
                password.getBytes(), /* salt= */ null)).isNull();

        assertThat(
                LockscreenCredential.legacyPasswordToHash(
                        /* password= */ null, salt.getBytes()))
                .isNull();
    }

    private LockscreenCredential createPattern(String patternString) {
        return LockscreenCredential.createPattern(LockPatternUtils.byteArrayToPattern(
                patternString.getBytes()));
    }
}
