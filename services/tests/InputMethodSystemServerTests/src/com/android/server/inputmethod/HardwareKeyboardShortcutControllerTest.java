/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class HardwareKeyboardShortcutControllerTest {

    @Test
    public void testForwardRotation() {
        final List<String> handles = Arrays.asList("0", "1", "2", "3");
        assertEquals("2", HardwareKeyboardShortcutController.getNeighborItem(handles, "1", true));
        assertEquals("3", HardwareKeyboardShortcutController.getNeighborItem(handles, "2", true));
        assertEquals("0", HardwareKeyboardShortcutController.getNeighborItem(handles, "3", true));
    }

    @Test
    public void testBackwardRotation() {
        final List<String> handles = Arrays.asList("0", "1", "2", "3");
        assertEquals("0", HardwareKeyboardShortcutController.getNeighborItem(handles, "1", false));
        assertEquals("3", HardwareKeyboardShortcutController.getNeighborItem(handles, "0", false));
        assertEquals("2", HardwareKeyboardShortcutController.getNeighborItem(handles, "3", false));
    }

    @Test
    public void testNotMatching() {
        final List<String> handles = Arrays.asList("0", "1", "2", "3");
        assertNull(HardwareKeyboardShortcutController.getNeighborItem(handles, "X", true));
        assertNull(HardwareKeyboardShortcutController.getNeighborItem(handles, "X", false));
    }
}
