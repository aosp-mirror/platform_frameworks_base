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
import com.android.databinding.testapp.BaseLandDataBinderTest;
import com.android.databinding.testapp.R;
import com.android.databinding.testapp.generated.BasicBindingBinder;
import com.android.databinding.testapp.generated.ConditionalBindingBinder;
import com.android.databinding.testapp.generated.IncludedLayoutBinder;
import com.android.databinding.testapp.generated.MultiResLayoutBinder;
import com.android.databinding.testapp.vo.NotBindableVo;

import android.view.View;
import android.widget.TextView;

public class LandscapeConfigTest extends BaseLandDataBinderTest<MultiResLayoutBinder> {

    public LandscapeConfigTest() {
        super(MultiResLayoutBinder.class, R.layout.multi_res_layout);
    }

    public void testSharedViewIdAndVariableInheritance()
            throws InterruptedException, NoSuchMethodException, NoSuchFieldException {
        assertEquals("MultiResLayoutBinderLandImpl", mBinder.getClass().getSimpleName());
        assertMethod(TextView.class, "getObjectInLandTextView");
        assertMethod(TextView.class, "getObjectInDefaultTextView");
        assertMethod(View.class, "getObjectInDefaultTextView2");

        assertField(TextView.class, "mObjectInLandTextView");
        assertField(TextView.class, "mObjectInDefaultTextView");
        assertField(TextView.class, "mObjectInDefaultTextView2");

        assertField(NotBindableVo.class, "mObjectInLand");
        assertField(NotBindableVo.class, "mObjectInDefault");

        // includes
        assertMethod(IViewDataBinder.class, "getIncludedLayoutConflict");
        assertMethod(BasicBindingBinder.class, "getIncludedLayoutShared");
        assertMethod(ConditionalBindingBinder.class, "getIncludedLayoutPort");
        assertMethod(ConditionalBindingBinder.class, "getIncludedLayoutLand");

        assertField(IncludedLayoutBinder.class, "mIncludedLayoutConflict");
        assertField(BasicBindingBinder.class, "mIncludedLayoutShared");
        assertField(ConditionalBindingBinder.class, "mIncludedLayoutLand");

        assertNoField("mIncludedLayoutPort");
    }
}
