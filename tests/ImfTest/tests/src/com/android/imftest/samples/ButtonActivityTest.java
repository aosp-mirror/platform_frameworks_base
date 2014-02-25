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

package com.android.imftest.samples;

import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;
import android.widget.Button;


public class ButtonActivityTest extends ImfBaseTestCase<ButtonActivity> {

    final public String TAG = "ButtonActivityTest";
    
    public ButtonActivityTest() {
        super(ButtonActivity.class);
    }

    @LargeTest
    public void testButtonActivatesIme() {
       
        final Button button = (Button) mTargetActivity.findViewById(ButtonActivity.BUTTON_ID);
        
        // Push button
        // Bring the target EditText into focus.
        mTargetActivity.runOnUiThread(new Runnable() {
            public void run() {
                button.requestFocus();
            }
        });
        
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        
        // Give it a couple seconds
        pause(2000);
        
        // We should have initialized imm.mServedView and imm.mCurrentTextBoxAttribute
        assertTrue(mImm.isActive());
        // imm.mServedInputConnection should be null since Button doesn't override onCreateInputConnection().
        assertFalse(mImm.isAcceptingText());
        
        destructiveCheckImeInitialState(mTargetActivity.getRootView(), button);
        
    }
}
