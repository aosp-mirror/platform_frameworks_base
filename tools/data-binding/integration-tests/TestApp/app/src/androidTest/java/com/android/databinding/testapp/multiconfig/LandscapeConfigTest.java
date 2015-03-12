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

import com.android.databinding.library.ViewDataBinding;
import com.android.databinding.testapp.BaseLandDataBinderTest;
import com.android.databinding.testapp.R;
import com.android.databinding.testapp.generated.BasicBindingBinding;
import com.android.databinding.testapp.generated.ConditionalBindingBinding;
import com.android.databinding.testapp.generated.IncludedLayoutBinding;
import com.android.databinding.testapp.generated.MultiResLayoutBinding;
import com.android.databinding.testapp.vo.NotBindableVo;

import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.TextView;

public class LandscapeConfigTest extends BaseLandDataBinderTest<MultiResLayoutBinding> {

    public LandscapeConfigTest() {
        super(MultiResLayoutBinding.class, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    public void testSharedViewIdAndVariableInheritance()
            throws InterruptedException, NoSuchMethodException, NoSuchFieldException {
        assertEquals("MultiResLayoutBindingLandImpl", mBinder.getClass().getSimpleName());
        assertMethod(TextView.class, "getObjectInLandTextView");
        assertMethod(TextView.class, "getObjectInDefaultTextView");
        assertMethod(View.class, "getObjectInDefaultTextView2");

        assertField(TextView.class, "mObjectInLandTextView");
        assertField(TextView.class, "mObjectInDefaultTextView");
        assertField(TextView.class, "mObjectInDefaultTextView2");

        assertField(NotBindableVo.class, "mObjectInLand");
        assertField(NotBindableVo.class, "mObjectInDefault");

        // includes
        assertMethod(ViewDataBinding.class, "getIncludedLayoutConflict");
        assertMethod(BasicBindingBinding.class, "getIncludedLayoutShared");
        assertMethod(ConditionalBindingBinding.class, "getIncludedLayoutPort");
        assertMethod(ConditionalBindingBinding.class, "getIncludedLayoutLand");

        assertField(IncludedLayoutBinding.class, "mIncludedLayoutConflict");
        assertField(BasicBindingBinding.class, "mIncludedLayoutShared");
        assertField(ConditionalBindingBinding.class, "mIncludedLayoutLand");

        assertNoField("mIncludedLayoutPort");
    }
}
