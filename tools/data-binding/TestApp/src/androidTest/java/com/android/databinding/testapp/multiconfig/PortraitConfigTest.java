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

package com.android.databinding.testapp.multiconfig;

import com.android.databinding.library.IViewDataBinder;
import com.android.databinding.testapp.BaseDataBinderTest;
import com.android.databinding.testapp.R;
import com.android.databinding.testapp.generated.BasicBindingBinder;
import com.android.databinding.testapp.generated.ConditionalBindingBinder;
import com.android.databinding.testapp.generated.IncludedLayoutBinder;
import com.android.databinding.testapp.generated.MultiResLayoutBinder;
import com.android.databinding.testapp.vo.NotBindableVo;

import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

public class PortraitConfigTest extends BaseDataBinderTest<MultiResLayoutBinder> {
    public PortraitConfigTest() {
        super(MultiResLayoutBinder.class, R.layout.multi_res_layout, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    public void testSharedViewIdAndVariableInheritance()
            throws InterruptedException, NoSuchMethodException, NoSuchFieldException {
        assertEquals("MultiResLayoutBinderImpl", mBinder.getClass().getSimpleName());
        assertEquals("MultiResLayoutBinderImpl", mBinder.getClass().getSimpleName());
        assertMethod(TextView.class, "getObjectInLandTextView");
        assertMethod(TextView.class, "getObjectInDefaultTextView");
        assertMethod(View.class, "getObjectInDefaultTextView2");

        assertNoField("mObjectInLandTextView");
        assertField(TextView.class, "mObjectInDefaultTextView");
        assertField(EditText.class, "mObjectInDefaultTextView2");

        assertNoField("mObjectInLand");
        assertField(NotBindableVo.class, "mObjectInDefault");


        // includes
        assertMethod(IViewDataBinder.class, "getIncludedLayoutConflict");
        assertMethod(BasicBindingBinder.class, "getIncludedLayoutShared");
        assertMethod(ConditionalBindingBinder.class, "getIncludedLayoutPort");
        assertMethod(ConditionalBindingBinder.class, "getIncludedLayoutLand");

        assertField(BasicBindingBinder.class, "mIncludedLayoutConflict");
        assertField(BasicBindingBinder.class, "mIncludedLayoutShared");
        assertField(ConditionalBindingBinder.class, "mIncludedLayoutPort");

        assertNoField("mIncludedLayoutLand");
    }
}
