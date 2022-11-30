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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;

import com.airbnb.lottie.LottieAnimationView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class IllustrationPreferenceTest {

    @Mock
    private ViewGroup mRootView;
    private Uri mImageUri;
    private ImageView mBackgroundView;
    private LottieAnimationView mAnimationView;
    private IllustrationPreference mPreference;
    private PreferenceViewHolder mViewHolder;
    private FrameLayout mMiddleGroundLayout;
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private IllustrationPreference.OnBindListener mOnBindListener;
    private LottieAnimationView mOnBindListenerAnimationView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mImageUri = new Uri.Builder().build();
        mBackgroundView = new ImageView(mContext);
        mAnimationView = spy(new LottieAnimationView(mContext));
        mMiddleGroundLayout = new FrameLayout(mContext);
        final FrameLayout illustrationFrame = new FrameLayout(mContext);
        illustrationFrame.setLayoutParams(
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        doReturn(mMiddleGroundLayout).when(mRootView).findViewById(R.id.middleground_layout);
        doReturn(mBackgroundView).when(mRootView).findViewById(R.id.background_view);
        doReturn(mAnimationView).when(mRootView).findViewById(R.id.lottie_view);
        doReturn(illustrationFrame).when(mRootView).findViewById(R.id.illustration_frame);
        mViewHolder = spy(PreferenceViewHolder.createInstanceForTests(mRootView));

        final AttributeSet attributeSet = Robolectric.buildAttributeSet().build();
        mPreference = new IllustrationPreference(mContext, attributeSet);
        mOnBindListener = new IllustrationPreference.OnBindListener() {
            @Override
            public void onBind(LottieAnimationView animationView) {
                mOnBindListenerAnimationView = animationView;
            }
        };
    }

    @Test
    public void setMiddleGroundView_middleGroundView_shouldVisible() {
        final View view = new View(mContext);
        mMiddleGroundLayout.setVisibility(View.GONE);

        mPreference.setMiddleGroundView(view);
        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mMiddleGroundLayout.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void enableAnimationAutoScale_shouldChangeScaleType() {
        mPreference.enableAnimationAutoScale(true);
        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mAnimationView.getScaleType()).isEqualTo(ImageView.ScaleType.CENTER_CROP);
    }

    @Test
    public void playAnimationWithUri_animatedImageDrawable_success() {
        final AnimatedImageDrawable drawable = mock(AnimatedImageDrawable.class);
        doReturn(drawable).when(mAnimationView).getDrawable();

        mPreference.setImageUri(mImageUri);
        mPreference.onBindViewHolder(mViewHolder);

        verify(drawable).start();
    }

    @Test
    public void playAnimationWithUri_animatedVectorDrawable_success() {
        final AnimatedVectorDrawable drawable = mock(AnimatedVectorDrawable.class);
        doReturn(drawable).when(mAnimationView).getDrawable();

        mPreference.setImageUri(mImageUri);
        mPreference.onBindViewHolder(mViewHolder);

        verify(drawable).start();
    }

    @Test
    public void playAnimationWithUri_animationDrawable_success() {
        final AnimationDrawable drawable = mock(AnimationDrawable.class);
        doReturn(drawable).when(mAnimationView).getDrawable();

        mPreference.setImageUri(mImageUri);
        mPreference.onBindViewHolder(mViewHolder);

        verify(drawable).start();
    }

    @Test
    public void playLottieAnimationWithUri_verifyFailureListener() {
        doReturn(null).when(mAnimationView).getDrawable();

        mPreference.setImageUri(mImageUri);
        mPreference.onBindViewHolder(mViewHolder);

        verify(mAnimationView).setFailureListener(any());
    }

    @Test
    public void playLottieAnimationWithResource_verifyFailureListener() {
        // fake the valid lottie image
        final int fakeValidResId = 111;
        doNothing().when(mAnimationView).setImageResource(fakeValidResId);
        doReturn(null).when(mAnimationView).getDrawable();

        mPreference.setLottieAnimationResId(fakeValidResId);
        mPreference.onBindViewHolder(mViewHolder);

        verify(mAnimationView).setFailureListener(any());
    }

    @Test
    public void setMaxHeight_smallerThanRestrictedHeight_matchResult() {
        final int restrictedHeight =
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.settingslib_illustration_height);
        final int maxHeight = restrictedHeight - 200;

        mPreference.setMaxHeight(maxHeight);
        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mBackgroundView.getMaxHeight()).isEqualTo(maxHeight);
        assertThat(mAnimationView.getMaxHeight()).isEqualTo(maxHeight);
    }

    @Test
    public void setMaxHeight_largerThanRestrictedHeight_specificHeight() {
        final int restrictedHeight =
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.settingslib_illustration_height);
        final int maxHeight = restrictedHeight + 200;

        mPreference.setMaxHeight(maxHeight);
        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mBackgroundView.getMaxHeight()).isEqualTo(restrictedHeight);
        assertThat(mAnimationView.getMaxHeight()).isEqualTo(restrictedHeight);
    }

    @Test
    public void setOnBindListener_isNotified() {
        mOnBindListenerAnimationView = null;
        mPreference.setOnBindListener(mOnBindListener);

        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mOnBindListenerAnimationView).isNotNull();
        assertThat(mOnBindListenerAnimationView).isEqualTo(mAnimationView);
    }

    @Test
    public void setOnBindListener_notNotified() {
        mOnBindListenerAnimationView = null;
        mPreference.setOnBindListener(null);

        mPreference.onBindViewHolder(mViewHolder);

        assertThat(mOnBindListenerAnimationView).isNull();
    }
}
