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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class LockscreenCredentialTest {

    @Test
    public void testNoneCredential() {
        LockscreenCredential none = LockscreenCredential.createNone();

        assertTrue(none.isNone());
        assertEquals(0, none.size());
        assertArrayEquals(new byte[0], none.getCredential());

        assertFalse(none.isPin());
        assertFalse(none.isPassword());
        assertFalse(none.isPattern());
        assertFalse(none.hasInvalidChars());
        none.validateBasicRequirements();
    }

    @Test
    public void testPinCredential() {
        LockscreenCredential pin = LockscreenCredential.createPin("3456");

        assertTrue(pin.isPin());
        assertEquals(4, pin.size());
        assertArrayEquals("3456".getBytes(), pin.getCredential());

        assertFalse(pin.isNone());
        assertFalse(pin.isPassword());
        assertFalse(pin.isPattern());
        assertFalse(pin.hasInvalidChars());
        pin.validateBasicRequirements();
    }

    @Test
    public void testPasswordCredential() {
        LockscreenCredential password = LockscreenCredential.createPassword("password");

        assertTrue(password.isPassword());
        assertEquals(8, password.size());
        assertArrayEquals("password".getBytes(), password.getCredential());

        assertFalse(password.isNone());
        assertFalse(password.isPin());
        assertFalse(password.isPattern());
        assertFalse(password.hasInvalidChars());
        password.validateBasicRequirements();
    }

    @Test
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
        assertArrayEquals("12369".getBytes(), pattern.getCredential());

        assertFalse(pattern.isNone());
        assertFalse(pattern.isPin());
        assertFalse(pattern.isPassword());
        assertFalse(pattern.hasInvalidChars());
        pattern.validateBasicRequirements();
    }

    // Constructing a LockscreenCredential with a too-short length, even 0, should not throw an
    // exception.  This is because LockscreenCredential needs to be able to represent a request to
    // set a credential that is too short.
    @Test
    public void testZeroLengthCredential() {
        LockscreenCredential credential = LockscreenCredential.createPin("");
        assertTrue(credential.isPin());
        assertEquals(0, credential.size());

        credential = createPattern("");
        assertTrue(credential.isPattern());
        assertEquals(0, credential.size());

        credential = LockscreenCredential.createPassword("");
        assertTrue(credential.isPassword());
        assertEquals(0, credential.size());
    }

    @Test
    public void testPasswordOrNoneCredential() {
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPasswordOrNone(null));
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPasswordOrNone(""));
        assertEquals(LockscreenCredential.createPassword("abcd"),
                LockscreenCredential.createPasswordOrNone("abcd"));
    }

    @Test
    public void testPinOrNoneCredential() {
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPinOrNone(null));
        assertEquals(LockscreenCredential.createNone(),
                LockscreenCredential.createPinOrNone(""));
        assertEquals(LockscreenCredential.createPin("1357"),
                LockscreenCredential.createPinOrNone("1357"));
    }

    // Test that passwords containing invalid characters that were incorrectly allowed in
    // Android 10–14 are still interpreted in the same way, but are not allowed for new passwords.
    @Test
    public void testPasswordWithInvalidChars() {
        // ™ is U+2122, which was truncated to ASCII 0x22 which is double quote.
        String[] passwords = new String[] { "foo™", "™™™™", "™foo" };
        String[] equivalentAsciiPasswords = new String[] { "foo\"", "\"\"\"\"", "\"foo" };
        for (int i = 0; i < passwords.length; i++) {
            LockscreenCredential credential = LockscreenCredential.createPassword(passwords[i]);
            assertTrue(credential.hasInvalidChars());
            assertArrayEquals(equivalentAsciiPasswords[i].getBytes(), credential.getCredential());
            try {
                credential.validateBasicRequirements();
                fail("should not be able to set password with invalid chars");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test
    public void testPinWithInvalidChars() {
        LockscreenCredential pin = LockscreenCredential.createPin("\n\n\n\n");
        assertTrue(pin.hasInvalidChars());
        try {
            pin.validateBasicRequirements();
            fail("should not be able to set PIN with invalid chars");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
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
            password.hasInvalidChars();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
        try {
            password.getCredential();
            fail("Sanitized credential still accessible");
        } catch (IllegalStateException expected) { }
    }

    @Test
    public void testEquals() {
        assertEquals(LockscreenCredential.createNone(), LockscreenCredential.createNone());
        assertEquals(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPassword("1234"));
        assertEquals(LockscreenCredential.createPin("4321"),
                LockscreenCredential.createPin("4321"));
        assertEquals(createPattern("1234"), createPattern("1234"));

        assertNotEquals(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createNone());
        assertNotEquals(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPassword("4321"));
        assertNotEquals(LockscreenCredential.createPassword("1234"),
                createPattern("1234"));
        assertNotEquals(LockscreenCredential.createPassword("1234"),
                LockscreenCredential.createPin("1234"));

        assertNotEquals(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createNone());
        assertNotEquals(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createPin("2222"));
        assertNotEquals(LockscreenCredential.createPin("1111"),
                createPattern("1111"));
        assertNotEquals(LockscreenCredential.createPin("1111"),
                LockscreenCredential.createPassword("1111"));

        assertNotEquals(createPattern("5678"),
                LockscreenCredential.createNone());
        assertNotEquals(createPattern("5678"),
                createPattern("1234"));
        assertNotEquals(createPattern("5678"),
                LockscreenCredential.createPassword("5678"));
        assertNotEquals(createPattern("5678"),
                LockscreenCredential.createPin("5678"));

        // Test that mHasInvalidChars is compared.  To do this, compare two passwords that map to
        // the same byte[] (due to the truncation bug) but different values of mHasInvalidChars.
        assertNotEquals(LockscreenCredential.createPassword("™™™™"),
                LockscreenCredential.createPassword("\"\"\"\""));
    }

    @Test
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

        // Test that mHasInvalidChars is duplicated.
        credential = LockscreenCredential.createPassword("™™™™");
        assertEquals(credential, credential.duplicate());
    }

    @Test
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

    @Test
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

    @Test
    public void testLegacyPasswordToHash() {
        String password = "1234";
        String salt = "6d5331dd120077a0";
        String expectedHash =
                "2DD04348ADBF8F4CABD7F722DC2E2887FAD4B6020A0C3E02C831E09946F0554FDC13B155";

        assertThat(
                LockscreenCredential.legacyPasswordToHash(
                        password.getBytes(), salt.getBytes()))
                .isEqualTo(expectedHash);
    }

    @Test
    public void testLegacyPasswordToHashInvalidInput() {
        String password = "1234";
        String salt = "6d5331dd120077a0";

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
