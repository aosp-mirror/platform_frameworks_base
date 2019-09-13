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

package com.android.systemui.assist;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.os.Handler;
import android.util.Log;
import android.util.MathUtils;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.CornerHandleView;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.NavigationBarTransitions;

/**
 * A class for managing Assistant handle show, hide and animation.
 */
public class AssistHandleViewController implements NavigationBarTransitions.DarkIntensityListener {

    private static final boolean DEBUG = false;
    private static final String TAG = "AssistHandleViewController";

    private Handler mHandler;
    private CornerHandleView mAssistHintLeft;
    private CornerHandleView mAssistHintRight;

    @VisibleForTesting
    boolean mAssistHintVisible;
    @VisibleForTesting
    boolean mAssistHintBlocked = false;

    public AssistHandleViewController(Handler handler, View navBar) {
        mHandler = handler;
        mAssistHintLeft = navBar.findViewById(R.id.assist_hint_left);
        mAssistHintRight = navBar.findViewById(R.id.assist_hint_right);
    }

    @Override
    public void onDarkIntensity(float darkIntensity) {
        mAssistHintLeft.updateDarkness(darkIntensity);
        mAssistHintRight.updateDarkness(darkIntensity);
    }

    /**
     * Controls the visibility of the assist gesture handles.
     *
     * @param visible whether the handles should be shown
     */
    public void setAssistHintVisible(boolean visible) {
        if (!mHandler.getLooper().isCurrentThread()) {
            mHandler.post(() -> setAssistHintVisible(visible));
            return;
        }

        if (mAssistHintBlocked && visible) {
            if (DEBUG) {
                Log.v(TAG, "Assist hint blocked, cannot make it visible");
            }
            return;
        }

        if (mAssistHintVisible != visible) {
            mAssistHintVisible = visible;
            fade(mAssistHintLeft, mAssistHintVisible, /* isLeft = */ true);
            fade(mAssistHintRight, mAssistHintVisible, /* isLeft = */ false);
        }
    }

    /**
     * Prevents the assist hint from becoming visible even if `mAssistHintVisible` is true.
     */
    public void setAssistHintBlocked(boolean blocked) {
        if (!mHandler.getLooper().isCurrentThread()) {
            mHandler.post(() -> setAssistHintBlocked(blocked));
            return;
        }

        mAssistHintBlocked = blocked;
        if (mAssistHintVisible && mAssistHintBlocked) {
            hideAssistHandles();
        }
    }

    private void hideAssistHandles() {
        mAssistHintLeft.setVisibility(View.GONE);
        mAssistHintRight.setVisibility(View.GONE);
        mAssistHintVisible = false;
    }

    /**
     * Returns an animator that animates the given view from start to end over durationMs. Start and
     * end represent total animation progress: 0 is the start, 1 is the end, 1.1 would be an
     * overshoot.
     */
    Animator getHandleAnimator(View view, float start, float end, boolean isLeft, long durationMs,
            Interpolator interpolator) {
        // Note that lerp does allow overshoot, in cases where start and end are outside of [0,1].
        float scaleStart = MathUtils.lerp(2f, 1f, start);
        float scaleEnd = MathUtils.lerp(2f, 1f, end);
        Animator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, scaleStart, scaleEnd);
        Animator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, scaleStart, scaleEnd);
        float translationStart = MathUtils.lerp(0.2f, 0f, start);
        float translationEnd = MathUtils.lerp(0.2f, 0f, end);
        int xDirection = isLeft ? -1 : 1;
        Animator translateX = ObjectAnimator.ofFloat(view, View.TRANSLATION_X,
                xDirection * translationStart * view.getWidth(),
                xDirection * translationEnd * view.getWidth());
        Animator translateY = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y,
                translationStart * view.getHeight(), translationEnd * view.getHeight());

        AnimatorSet set = new AnimatorSet();
        set.play(scaleX).with(scaleY);
        set.play(scaleX).with(translateX);
        set.play(scaleX).with(translateY);
        set.setDuration(durationMs);
        set.setInterpolator(interpolator);
        return set;
    }

    private void fade(View view, boolean fadeIn, boolean isLeft) {
        if (fadeIn) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setVisibility(View.VISIBLE);

            // A piecewise spring-like interpolation.
            // End value in one animator call must match the start value in the next, otherwise
            // there will be a discontinuity.
            AnimatorSet anim = new AnimatorSet();
            Animator first = getHandleAnimator(view, 0, 1.1f, isLeft, 750,
                    new PathInterpolator(0, 0.45f, .67f, 1f));
            Interpolator secondInterpolator = new PathInterpolator(0.33f, 0, 0.67f, 1f);
            Animator second = getHandleAnimator(view, 1.1f, 0.97f, isLeft, 400,
                    secondInterpolator);
            Animator third = getHandleAnimator(view, 0.97f, 1.02f, isLeft, 400,
                    secondInterpolator);
            Animator fourth = getHandleAnimator(view, 1.02f, 1f, isLeft, 400,
                    secondInterpolator);
            anim.play(first).before(second);
            anim.play(second).before(third);
            anim.play(third).before(fourth);
            anim.start();
        } else {
            view.animate().cancel();
            view.animate()
                .setInterpolator(new AccelerateInterpolator(1.5f))
                .setDuration(250)
                .alpha(0f);
        }

    }
}
