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

import com.android.databinding.testapp.generated.CompoundButtonAdapterTestBinder;
import com.android.databinding.testapp.vo.CompoundButtonBindingObject;

import android.widget.CompoundButton;

public class CompoundButtonBindingAdapterTest extends
        BindingAdapterTestBase<CompoundButtonAdapterTestBinder, CompoundButtonBindingObject> {

    CompoundButton mView;

    public CompoundButtonBindingAdapterTest() {
        super(CompoundButtonAdapterTestBinder.class, CompoundButtonBindingObject.class,
                R.layout.compound_button_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.getView();
    }

    public void testCompoundButton() throws Throwable {
        assertEquals(mBindingObject.getButtonTint(), mView.getButtonTintList().getDefaultColor());

        changeValues();

        assertEquals(mBindingObject.getButtonTint(), mView.getButtonTintList().getDefaultColor());
    }
}
