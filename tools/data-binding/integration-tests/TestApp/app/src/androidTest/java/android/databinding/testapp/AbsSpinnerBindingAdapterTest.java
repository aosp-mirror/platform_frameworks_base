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

import android.databinding.testapp.databinding.AbsSpinnerAdapterTestBinding;
import android.databinding.testapp.vo.AbsSpinnerBindingObject;

import android.os.Build;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

public class AbsSpinnerBindingAdapterTest
        extends BindingAdapterTestBase<AbsSpinnerAdapterTestBinding, AbsSpinnerBindingObject> {

    Spinner mView;

    public AbsSpinnerBindingAdapterTest() {
        super(AbsSpinnerAdapterTestBinding.class, AbsSpinnerBindingObject.class,
                R.layout.abs_spinner_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testEntries() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            validateEntries();

            changeValues();

            validateEntries();
        }
    }

    private void validateEntries() {
        assertEquals(mBindingObject.getEntries().length, mView.getAdapter().getCount());
        CharSequence[] entries = mBindingObject.getEntries();
        SpinnerAdapter adapter = mView.getAdapter();
        for (int i = 0; i < entries.length; i++) {
            assertEquals(adapter.getItem(i), entries[i]);
        }
    }
}
