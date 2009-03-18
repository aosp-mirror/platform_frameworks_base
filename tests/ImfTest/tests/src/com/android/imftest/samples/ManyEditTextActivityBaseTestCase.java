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

import android.app.Activity;
import android.widget.EditText;


public abstract class ManyEditTextActivityBaseTestCase<T extends Activity> extends ImfBaseTestCase<T> {
  
    public ManyEditTextActivityBaseTestCase(Class<T> activityClass){
        super(activityClass);
    }

    public abstract void testAllEditTextsAdjust();

    public void verifyAllEditTextAdjustment(int numEditTexts, int rootViewHeight) {

        for (int i = 0; i < numEditTexts; i++) {
            final EditText lastEditText = (EditText) mTargetActivity.findViewById(i);
            verifyEditTextAdjustment(lastEditText, rootViewHeight);
        }

    }

}
