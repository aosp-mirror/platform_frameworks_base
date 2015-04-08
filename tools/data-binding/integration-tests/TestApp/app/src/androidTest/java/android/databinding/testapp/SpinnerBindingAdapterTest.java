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

import android.databinding.testapp.databinding.SpinnerAdapterTestBinding;
import android.databinding.testapp.vo.SpinnerBindingObject;

import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.widget.Spinner;

public class SpinnerBindingAdapterTest
        extends BindingAdapterTestBase<SpinnerAdapterTestBinding, SpinnerBindingObject> {

    Spinner mView;

    public SpinnerBindingAdapterTest() {
        super(SpinnerAdapterTestBinding.class, SpinnerBindingObject.class,
                R.layout.spinner_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testSpinner() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getPopupBackground(),
                    ((ColorDrawable) mView.getPopupBackground()).getColor());

            changeValues();

            assertEquals(mBindingObject.getPopupBackground(),
                    ((ColorDrawable) mView.getPopupBackground()).getColor());
        }
    }
}
