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
 * limitations under the License
 */

package com.android.server.inputmethod;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodManagerServiceTests {
    static final int SYSTEM_DECORATION_SUPPORT_DISPLAY_ID = 2;
    static final int NO_SYSTEM_DECORATION_SUPPORT_DISPLAY_ID = 3;

    static InputMethodManagerService.ImeDisplayValidator sChecker =
            (displayId) -> {
                switch (displayId) {
                    case SYSTEM_DECORATION_SUPPORT_DISPLAY_ID:
                        return true;
                    case NO_SYSTEM_DECORATION_SUPPORT_DISPLAY_ID:
                        return false;
                    default:
                        throw new IllegalArgumentException("Unknown displayId=" + displayId);
                }
            };

    static InputMethodManagerService.ImeDisplayValidator sMustNotBeCalledChecker =
            (displayId) -> {
                fail("Should not pass to display config check for this test case.");
                return false;
            };

    @Test
    public void testComputeImeDisplayId_defaultDisplayId() {
        // Make sure that there is a short-circuit for DEFAULT_DISPLAY.
        assertEquals(DEFAULT_DISPLAY,
                InputMethodManagerService.computeImeDisplayIdForTarget(
                        DEFAULT_DISPLAY, sMustNotBeCalledChecker));
    }

    @Test
    public void testComputeImeDisplayId_InvalidDisplayId() {
        // Make sure that there is a short-circuit for INVALID_DISPLAY.
        assertEquals(DEFAULT_DISPLAY,
                InputMethodManagerService.computeImeDisplayIdForTarget(
                        INVALID_DISPLAY, sMustNotBeCalledChecker));
    }

    @Test
    public void testComputeImeDisplayId_noSystemDecorationSupportDisplay() {
        // Presume display didn't support system decoration.
        // Make sure IME displayId is DEFAULT_DISPLAY.
        assertEquals(DEFAULT_DISPLAY,
                InputMethodManagerService.computeImeDisplayIdForTarget(
                        NO_SYSTEM_DECORATION_SUPPORT_DISPLAY_ID, sChecker));
    }

    @Test
    public void testComputeImeDisplayId_withSystemDecorationSupportDisplay() {
        // Presume display support system decoration.
        // Make sure IME displayId is the same display.
        assertEquals(SYSTEM_DECORATION_SUPPORT_DISPLAY_ID,
                InputMethodManagerService.computeImeDisplayIdForTarget(
                        SYSTEM_DECORATION_SUPPORT_DISPLAY_ID, sChecker));
    }
}
