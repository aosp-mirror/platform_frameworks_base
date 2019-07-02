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

import static android.util.TouchModeFlexibleAsserts.assertNotInTouchModeAfterKey;

import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.layout.linear.LLEditTextThenButton;

import androidx.test.filters.MediumTest;

public class StartInTouchWithViewInFocusTest extends 
        ActivityInstrumentationTestCase2<LLEditTextThenButton> {

    private EditText mEditText;

    private Button mButton;

    public StartInTouchWithViewInFocusTest() {
        super("com.android.frameworks.coretests", LLEditTextThenButton.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.setActivityInitialTouchMode(true);
        mEditText = getActivity().getEditText();
        mButton = getActivity().getButton();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("should start in touch mode", mEditText.isInTouchMode());
        assertTrue("edit text is focusable in touch mode, should have focus", mEditText.isFocused());
    }

    // TODO: reenable when more reliable
    public void DISABLE_testKeyDownLeavesTouchModeAndGoesToNextView() {
        assertNotInTouchModeAfterKey(this, KeyEvent.KEYCODE_DPAD_DOWN, mEditText);
        assertFalse("should have left touch mode", mEditText.isInTouchMode());
        assertTrue("should have given focus to next view", mButton.isFocused());
    }

    // TODO: reenable when more reliable
    public void DISABLE_testNonDirectionalKeyExitsTouchMode() {
        assertNotInTouchModeAfterKey(this, KeyEvent.KEYCODE_A, mEditText);
        assertFalse("should have left touch mode", mEditText.isInTouchMode());
        assertTrue("edit text should still have focus", mEditText.isFocused());        
    }

}
