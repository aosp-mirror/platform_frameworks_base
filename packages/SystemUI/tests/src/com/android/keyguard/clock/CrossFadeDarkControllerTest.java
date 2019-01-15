/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class CrossFadeDarkControllerTest extends SysuiTestCase {

    private View mViewFadeIn;
    private View mViewFadeOut;
    private CrossFadeDarkController mDarkController;

    @Before
    public void setUp() {
        mViewFadeIn = new TextView(getContext());
        mViewFadeIn.setVisibility(View.VISIBLE);
        mViewFadeIn.setAlpha(1f);
        mViewFadeOut = new TextView(getContext());
        mViewFadeOut.setVisibility(View.VISIBLE);
        mViewFadeOut.setAlpha(1f);

        mDarkController = new CrossFadeDarkController(mViewFadeIn, mViewFadeOut);
    }

    @Test
    public void setDarkAmount_fadeIn() {
        // WHEN dark amount corresponds to AOD
        mDarkController.setDarkAmount(1f);
        // THEN fade in view should be faded in and fade out view faded out.
        assertThat(mViewFadeIn.getAlpha()).isEqualTo(1f);
        assertThat(mViewFadeIn.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewFadeOut.getAlpha()).isEqualTo(0f);
        assertThat(mViewFadeOut.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setDarkAmount_fadeOut() {
        // WHEN dark amount corresponds to lock screen
        mDarkController.setDarkAmount(0f);
        // THEN fade out view should bed faded out and fade in view faded in.
        assertThat(mViewFadeIn.getAlpha()).isEqualTo(0f);
        assertThat(mViewFadeIn.getVisibility()).isEqualTo(View.GONE);
        assertThat(mViewFadeOut.getAlpha()).isEqualTo(1f);
        assertThat(mViewFadeOut.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void setDarkAmount_partialFadeIn() {
        // WHEN dark amount corresponds to a partial transition
        mDarkController.setDarkAmount(0.9f);
        // THEN views should have intermediate alpha value.
        assertThat(mViewFadeIn.getAlpha()).isGreaterThan(0f);
        assertThat(mViewFadeIn.getAlpha()).isLessThan(1f);
        assertThat(mViewFadeIn.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void setDarkAmount_partialFadeOut() {
        // WHEN dark amount corresponds to a partial transition
        mDarkController.setDarkAmount(0.1f);
        // THEN views should have intermediate alpha value.
        assertThat(mViewFadeOut.getAlpha()).isGreaterThan(0f);
        assertThat(mViewFadeOut.getAlpha()).isLessThan(1f);
        assertThat(mViewFadeOut.getVisibility()).isEqualTo(View.VISIBLE);
    }
}
