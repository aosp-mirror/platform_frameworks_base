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

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM;

import android.annotation.NonNull;
import android.view.SurfaceControl;
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
    private SurfaceControl mFadeInParent;
    private SurfaceControl mFadeOutParent;
    private boolean mPlaySequentially = false;

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

    @Override
    protected FadeAnimationAdapter createAdapter(LocalAnimationAdapter.AnimationSpec animationSpec,
            boolean show, WindowToken windowToken) {
        return new NavFadeAnimationAdapter(
                animationSpec, windowToken.getSurfaceAnimationRunner(), show, windowToken,
                show ? mFadeInParent : mFadeOutParent);
    }

    /**
     * Run the fade-in/out animation for the navigation bar.
     *
     * @param show true for fade-in, otherwise for fade-out.
     */
    public void fadeWindowToken(boolean show) {
        final AsyncRotationController controller =
                mDisplayContent.getAsyncRotationController();
        final Runnable fadeAnim = () -> fadeWindowToken(show, mNavigationBar.mToken,
                ANIMATION_TYPE_TOKEN_TRANSFORM);
        if (controller == null) {
            fadeAnim.run();
        } else if (!controller.isTargetToken(mNavigationBar.mToken)) {
            // If fade rotation animation is running and the nav bar is not controlled by it:
            // - For fade-in animation, defer the animation until fade rotation animation finishes.
            // - For fade-out animation, just play the animation.
            if (show) {
                controller.setOnShowRunnable(fadeAnim);
            } else {
                fadeAnim.run();
            }
        }
    }

    void fadeOutAndInSequentially(long totalDuration, SurfaceControl fadeOutParent,
            SurfaceControl fadeInParent) {
        mPlaySequentially = true;
        if (totalDuration > 0) {
            // The animation duration of each animation varies so we set the fade-out duration to
            // 1/3 of the total app transition duration and set the fade-in duration to 2/3 of it.
            final long fadeInDuration = totalDuration * 2L / 3L;
            mFadeOutAnimation.setDuration(totalDuration - fadeInDuration);
            mFadeInAnimation.setDuration(fadeInDuration);
        }
        mFadeOutParent = fadeOutParent;
        mFadeInParent = fadeInParent;
        fadeWindowToken(false);
    }

    /**
     * The animation adapter that is capable of playing fade-out and fade-in sequentially and
     * reparenting the navigation bar to a specified SurfaceControl when fade animation starts.
     */
    protected class NavFadeAnimationAdapter extends FadeAnimationAdapter {
        private SurfaceControl mParent;

        NavFadeAnimationAdapter(AnimationSpec windowAnimationSpec,
                SurfaceAnimationRunner surfaceAnimationRunner, boolean show,
                WindowToken token, SurfaceControl parent) {
            super(windowAnimationSpec, surfaceAnimationRunner, show, token);
            mParent = parent;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                int type, @NonNull SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
            super.startAnimation(animationLeash, t, type, finishCallback);
            if (mParent != null && mParent.isValid()) {
                t.reparent(animationLeash, mParent);
                // Place the nav bar on top of anything else (e.g. ime and starting window) in the
                // parent.
                t.setLayer(animationLeash, Integer.MAX_VALUE);
            }
        }

        @Override
        public boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            if (mPlaySequentially) {
                if (!mShow) {
                    fadeWindowToken(true);
                }
                return false;
            } else {
                return super.shouldDeferAnimationFinish(endDeferFinishCallback);
            }
        }
    }
}
