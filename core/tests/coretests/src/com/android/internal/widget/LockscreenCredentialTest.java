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

    private LockscreenCredential createPattern(String patternString) {
        return LockscreenCredential.createPattern(LockPatternUtils.byteArrayToPattern(
                patternString.getBytes()));
    }
}
