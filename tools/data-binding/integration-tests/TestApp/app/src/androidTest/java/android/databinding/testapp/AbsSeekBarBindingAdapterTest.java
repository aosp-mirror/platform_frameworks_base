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

import android.databinding.testapp.databinding.AbsSeekBarAdapterTestBinding;
import android.databinding.testapp.vo.AbsSeekBarBindingObject;

import android.os.Build;
import android.widget.SeekBar;

public class AbsSeekBarBindingAdapterTest
        extends BindingAdapterTestBase<AbsSeekBarAdapterTestBinding, AbsSeekBarBindingObject> {

    SeekBar mView;

    public AbsSeekBarBindingAdapterTest() {
        super(AbsSeekBarAdapterTestBinding.class, AbsSeekBarBindingObject.class,
                R.layout.abs_seek_bar_adapter_test);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mView = mBinder.view;
    }

    public void testThumbTint() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            assertEquals(mBindingObject.getThumbTint(), mView.getThumbTintList().getDefaultColor());

            changeValues();

            assertEquals(mBindingObject.getThumbTint(), mView.getThumbTintList().getDefaultColor());
        }
    }
}
