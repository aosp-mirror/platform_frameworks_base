/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.touchmode;

import android.test.ActivityInstrumentationTestCase2;
import android.test.TouchUtils;
import android.widget.layout.linear.LLOfTwoFocusableInTouchMode;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class FocusableInTouchModeClickTest extends ActivityInstrumentationTestCase2<LLOfTwoFocusableInTouchMode> {

    public FocusableInTouchModeClickTest() {
        super("com.android.frameworks.coretests", LLOfTwoFocusableInTouchMode.class);
    }

    protected void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(true);
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("should start in touch mode", getActivity().getButton1().isInTouchMode());
        assertTrue(getActivity().getButton1().isFocused());
    }

    @LargeTest
    public void testClickGivesFocusNoClickFired() {
        TouchUtils.clickView(this, getActivity().getButton2());
        assertTrue("click should give focusable in touch mode focus",
                getActivity().getButton2().isFocused());
        assertFalse("getting focus should result in no on click",
                getActivity().isB2Fired());

        TouchUtils.clickView(this, getActivity().getButton2());
        assertTrue("subsequent click while focused should fire on click",
                getActivity().isB2Fired());
    }

    @MediumTest
    public void testTapGivesFocusNoClickFired() {
        TouchUtils.touchAndCancelView(this, getActivity().getButton2());
        assertFalse("button shouldn't have fired click", getActivity().isB2Fired());
        assertFalse("button shouldn't have focus", getActivity().getButton2().isFocused());
    }


}
