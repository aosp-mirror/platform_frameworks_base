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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;

import android.view.WindowManager.LayoutParams;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class InputMethodDebugTest {
    @Test
    public void testStartInputReasonToString() {
        // TODO: Use reflection to make sure that all the constants defined in StartInputReason are
        // covered.
        assertEquals("UNSPECIFIED",
                InputMethodDebug.startInputReasonToString(StartInputReason.UNSPECIFIED));
    }

    @Test
    public void testUnbindReasonToString() {
        // TODO: Use reflection to make sure that all the constants defined in UnbindReason are
        // covered.
        assertEquals("UNSPECIFIED",
                InputMethodDebug.startInputReasonToString(UnbindReason.UNSPECIFIED));
    }

    @Test
    public void testSoftInputModeToString() {
        // TODO: add more tests
        assertEquals("STATE_UNCHANGED|ADJUST_RESIZE|IS_FORWARD_NAVIGATION",
                InputMethodDebug.softInputModeToString(
                        LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                                | LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION));
    }

    @Test
    public void testStartInputFlagsToString() {
        // TODO: add more tests
        assertEquals("(none)", InputMethodDebug.startInputFlagsToString(0));
        assertEquals("IS_TEXT_EDITOR",
                InputMethodDebug.startInputFlagsToString(StartInputFlags.IS_TEXT_EDITOR));
        assertEquals("VIEW_HAS_FOCUS|INITIAL_CONNECTION",
                InputMethodDebug.startInputFlagsToString(
                        StartInputFlags.VIEW_HAS_FOCUS | StartInputFlags.INITIAL_CONNECTION));
    }

    @Test
    public void testSoftInputDisplayReasonToString() {
        // TODO: add more tests
        assertEquals("HIDE_REMOVE_CLIENT",
                InputMethodDebug.softInputDisplayReasonToString(
                        SoftInputShowHideReason.HIDE_REMOVE_CLIENT));
    }
}
