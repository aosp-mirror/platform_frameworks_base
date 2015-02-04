/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.databinding.testapp;

import com.android.databinding.testapp.generated.CheckedTextViewAdapterTestBinder;
import com.android.databinding.testapp.vo.CheckedTextViewBindingObject;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.widget.CheckedTextView;

public class CheckedTextViewBindingAdapterTest extends
        BindingAdapterTestBase<CheckedTextViewAdapterTestBinder, CheckedTextViewBindingObject> {

    CheckedTextView mView;

    public CheckedTextViewBindingAdapterTest() {
        super(CheckedTextViewAdapterTestBinder.class, CheckedTextViewBindingObject.class,
                R.layout.checked_text_view_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.getView();
    }

    public void testView() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            assertEquals(mBindingObject.getCheckMark().getColor(),
                    ((ColorDrawable) mView.getCheckMarkDrawable()).getColor());
            assertEquals(mBindingObject.getCheckMarkTint(),
                    mView.getCheckMarkTintList().getDefaultColor());

            changeValues();

            assertEquals(mBindingObject.getCheckMark().getColor(),
                    ((ColorDrawable) mView.getCheckMarkDrawable()).getColor());
            assertEquals(mBindingObject.getCheckMarkTint(),
                    mView.getCheckMarkTintList().getDefaultColor());
        }
    }
}
