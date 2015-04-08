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
package android.databinding.testapp;

import android.databinding.testapp.databinding.AutoCompleteTextViewAdapterTestBinding;
import android.databinding.testapp.vo.AutoCompleteTextViewBindingObject;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.widget.AutoCompleteTextView;

public class AutoCompleteTextViewBindingAdapterTest extends
        BindingAdapterTestBase<AutoCompleteTextViewAdapterTestBinding,
                AutoCompleteTextViewBindingObject> {

    AutoCompleteTextView mView;

    public AutoCompleteTextViewBindingAdapterTest() {
        super(AutoCompleteTextViewAdapterTestBinding.class, AutoCompleteTextViewBindingObject.class,
                R.layout.auto_complete_text_view_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testCompletionThreshold() throws Throwable {
        assertEquals(mBindingObject.getCompletionThreshold(), mView.getThreshold());

        changeValues();

        assertEquals(mBindingObject.getCompletionThreshold(), mView.getThreshold());
    }

    public void testPopupBackground() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            assertEquals(mBindingObject.getPopupBackground(),
                    ((ColorDrawable) mView.getDropDownBackground()).getColor());

            changeValues();

            assertEquals(mBindingObject.getPopupBackground(),
                    ((ColorDrawable) mView.getDropDownBackground()).getColor());
        }
    }
}
