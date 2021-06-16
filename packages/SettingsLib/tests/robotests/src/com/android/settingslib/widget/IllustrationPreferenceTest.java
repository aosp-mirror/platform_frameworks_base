/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.airbnb.lottie.LottieAnimationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
public class IllustrationPreferenceTest {

    @Mock
    LottieAnimationView mAnimationView;

    private Context mContext;
    private IllustrationPreference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;
        final AttributeSet attributeSet = Robolectric.buildAttributeSet().build();
        mPreference = new IllustrationPreference(mContext, attributeSet);
        ReflectionHelpers.setField(mPreference, "mIllustrationView", mAnimationView);
    }

    @Test
    public void setMiddleGroundView_middleGroundView_shouldVisible() {
        final View view = new View(mContext);
        final FrameLayout layout = new FrameLayout(mContext);
        layout.setVisibility(View.GONE);
        ReflectionHelpers.setField(mPreference, "mMiddleGroundView", view);
        ReflectionHelpers.setField(mPreference, "mMiddleGroundLayout", layout);

        mPreference.setMiddleGroundView(view);

        assertThat(layout.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void enableAnimationAutoScale_shouldChangeScaleType() {
        final LottieAnimationView animationView = new LottieAnimationView(mContext);
        ReflectionHelpers.setField(mPreference, "mIllustrationView", animationView);

        mPreference.enableAnimationAutoScale(true);

        assertThat(animationView.getScaleType()).isEqualTo(ImageView.ScaleType.CENTER_CROP);
    }
}
