/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.testapp;

import com.android.databinding.testapp.generated.BindToFinalBinder;
import com.android.databinding.testapp.generated.BindToFinalObservableBinder;
import com.android.databinding.testapp.vo.PublicFinalTestVo;
import com.android.databinding.testapp.vo.PublicFinalWithObservableTestVo;

import android.test.UiThreadTest;
import android.widget.TextView;

public class BindToFinalObservableFieldTest extends BaseDataBinderTest<BindToFinalObservableBinder>{

    public BindToFinalObservableFieldTest() {
        super(BindToFinalObservableBinder.class, R.layout.bind_to_final_observable);
    }

    @UiThreadTest
    public void testSimple() {
        final PublicFinalWithObservableTestVo vo = new PublicFinalWithObservableTestVo(R.string.app_name);
        mBinder.setObj(vo);
        mBinder.rebindDirty();
        final TextView textView = (TextView) mBinder.getRoot().findViewById(R.id.text_view);
        assertEquals(getActivity().getResources().getString(R.string.app_name), textView.getText().toString());
        vo.myFinalVo.setVal(R.string.rain);
        mBinder.rebindDirty();
        assertEquals("The field should be observed and its notify event should've invalidated"
                        + " binder flags.", getActivity().getResources().getString(R.string.rain),
                textView.getText().toString());
    }


}
