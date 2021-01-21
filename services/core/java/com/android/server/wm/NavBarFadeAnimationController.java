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

package com.android.server.wm;

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Controller to fade in and out  navigation bar during app transition when
 * config_attachNavBarToAppDuringTransition is true.
 */
public class NavBarFadeAnimationController extends FadeAnimationController{
    private static final int FADE_IN_DURATION = 266;
    private static final int FADE_OUT_DURATION = 133;
    private static final Interpolator FADE_IN_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0f, 1f);
    private static final Interpolator FADE_OUT_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 1f, 1f);

    private final WindowState mNavigationBar;
    private Animation mFadeInAnimation;
    private Animation mFadeOutAnimation;

    public NavBarFadeAnimationController(DisplayContent displayContent) {
        super(displayContent);
        mNavigationBar = displayContent.getDisplayPolicy().getNavigationBar();
        mFadeInAnimation = new AlphaAnimation(0f, 1f);
        mFadeInAnimation.setDuration(FADE_IN_DURATION);
        mFadeInAnimation.setInterpolator(FADE_IN_INTERPOLATOR);

        mFadeOutAnimation = new AlphaAnimation(1f, 0f);
        mFadeOutAnimation.setDuration(FADE_OUT_DURATION);
        mFadeOutAnimation.setInterpolator(FADE_OUT_INTERPOLATOR);
    }

    @Override
    public Animation getFadeInAnimation() {
        return mFadeInAnimation;
    }

    @Override
    public Animation getFadeOutAnimation() {
        return mFadeOutAnimation;
    }

    /**
     * Run the fade-in/out animation for the navigation bar.
     *
     * @param show true for fade-in, otherwise for fade-out.
     */
    public void fadeWindowToken(boolean show) {
        fadeWindowToken(show, mNavigationBar.mToken, ANIMATION_TYPE_APP_TRANSITION);
    }
}
