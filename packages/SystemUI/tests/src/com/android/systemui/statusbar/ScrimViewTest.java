/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertEquals;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.drawable.ScrimDrawable;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ScrimViewTest extends LeakCheckedTest {

    ScrimView mView;

    @Before
    public void setUp() {
        injectLeakCheckedDependency(ConfigurationController.class);
        mView = new ScrimView(getContext());
        mView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        mView.layout(0, 0, 1920, 1080);
    }

    @Test
    @RunWithLooper
    public void testAttachDetach() {
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testSetDrawable_UpdateDrawable() {
        Drawable drawable = new ColorDrawable(Color.GREEN);
        mView.setDrawable(drawable);
        assertEquals(drawable, mView.getDrawable());
    }

    @Test
    public void testCreation_initialColor() {
        ScrimDrawable drawable = (ScrimDrawable) mView.getDrawable();
        ColorExtractor.GradientColors colors = mView.getColors();
        assertEquals("Main color should be set upon creation",
                drawable.getMainColor(), colors.getMainColor());
    }

    @Test
    public void testSetViewAlpha_propagatesToDrawable() {
        final float alpha = 0.5f;
        mView.setViewAlpha(alpha);
        assertEquals("View alpha did not propagate to drawable", alpha, mView.getViewAlpha());
    }

    @Test
    public void setTint_set() {
        int tint = Color.BLUE;
        mView.setTint(tint);
        assertEquals(mView.getTint(), tint);
    }
}
