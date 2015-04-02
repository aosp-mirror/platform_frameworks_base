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

import android.databinding.testapp.databinding.RadioGroupAdapterTestBinding;
import android.databinding.testapp.vo.RadioGroupBindingObject;

import android.widget.RadioGroup;

public class RadioGroupBindingAdapterTest
        extends BindingAdapterTestBase<RadioGroupAdapterTestBinding, RadioGroupBindingObject> {

    RadioGroup mView;

    public RadioGroupBindingAdapterTest() {
        super(RadioGroupAdapterTestBinding.class, RadioGroupBindingObject.class,
                R.layout.radio_group_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testRadioGroup() throws Throwable {
        assertEquals(mBindingObject.getCheckedButton(), mView.getCheckedRadioButtonId());

        changeValues();

        assertEquals(mBindingObject.getCheckedButton(), mView.getCheckedRadioButtonId());
    }
}
