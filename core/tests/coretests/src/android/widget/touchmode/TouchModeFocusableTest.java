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

import static android.util.TouchModeFlexibleAsserts.assertInTouchModeAfterClick;
import static android.util.TouchModeFlexibleAsserts.assertInTouchModeAfterTap;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.Button;
import android.widget.EditText;
import android.widget.layout.linear.LLEditTextThenButton;

/**
 * Some views, like edit texts, can keep and gain focus even when in touch mode.
 */
public class TouchModeFocusableTest extends ActivityInstrumentationTestCase<LLEditTextThenButton> {
    private EditText mEditText;
    private Button mButton;


    public TouchModeFocusableTest() {
        super("com.android.frameworks.coretests", LLEditTextThenButton.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mEditText = getActivity().getEditText();
        mButton = getActivity().getButton();
    }

    @MediumTest
    public void testPreconditions() {
        assertFalse("should not be in touch mode to start off", mButton.isInTouchMode());
        assertTrue("edit text should have focus", mEditText.isFocused());
        assertTrue("edit text should be focusable in touch mode", mEditText.isFocusableInTouchMode());
    }

    @MediumTest
    public void testClickButtonEditTextKeepsFocus() {
        assertInTouchModeAfterTap(this, mButton);
        assertTrue("should be in touch mode", mButton.isInTouchMode());
        assertTrue("edit text should still have focus", mEditText.isFocused());
    }

    @LargeTest
    public void testClickEditTextGivesItFocus() {
        // go down to button
        getActivity().runOnUiThread(() -> mButton.requestFocus());
        getInstrumentation().waitForIdleSync();
        assertTrue("button should have focus", mButton.isFocused());

        assertInTouchModeAfterClick(this, mEditText);
        assertTrue("clicking edit text should have entered touch mode", mButton.isInTouchMode());
        assertTrue("clicking edit text should have given it focus", mEditText.isFocused());
    }


    // entering touch mode takes focus away from the currently focused item if it
    // isn't focusable in touch mode.
    @LargeTest
    public void testEnterTouchModeGivesFocusBackToFocusableInTouchMode() {
        getActivity().runOnUiThread(() -> mButton.requestFocus());
        getInstrumentation().waitForIdleSync();

        assertTrue("button should have focus",
                mButton.isFocused());

        assertInTouchModeAfterClick(this, mButton);
        assertTrue("should be in touch mode", mButton.isInTouchMode());
        assertNull("nothing should have focus", getActivity().getCurrentFocus());
        assertFalse("layout should not have focus",
                getActivity().getLayout().hasFocus());
    }
}
