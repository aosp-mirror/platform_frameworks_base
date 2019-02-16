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

package com.android.systemui.glwallpaper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

import com.android.systemui.Interpolators;

/**
 * Use ValueAnimator and appropriate interpolator to control the progress of reveal transition.
 * The transition will happen while getting awake and quit events.
 */
class ImageRevealHelper {
    private static final String TAG = ImageRevealHelper.class.getSimpleName();
    private static final float MAX_REVEAL = 0f;
    private static final float MIN_REVEAL = 1f;

    private final ValueAnimator mAnimator;
    private final RevealStateListener mRevealListener;
    private float mReveal = MAX_REVEAL;
    private boolean mAwake = false;

    ImageRevealHelper(RevealStateListener listener) {
        mRevealListener = listener;
        mAnimator = ValueAnimator.ofFloat();
        mAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addUpdateListener(animator -> {
            mReveal = (float) animator.getAnimatedValue();
            if (mRevealListener != null) {
                mRevealListener.onRevealStateChanged();
            }
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mIsCanceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mIsCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mIsCanceled) {
                    mAwake = !mAwake;
                }
                mIsCanceled = false;
            }
        });
    }

    private void animate() {
        mAnimator.cancel();
        mAnimator.setFloatValues(mReveal, !mAwake ? MIN_REVEAL : MAX_REVEAL);
        mAnimator.start();
    }

    public float getReveal() {
        return mReveal;
    }

    public boolean isAwake() {
        return mAwake;
    }

    void updateAwake(boolean awake, long duration) {
        mAwake = awake;
        mAnimator.setDuration(duration);
        animate();
    }

    /**
     * A listener to trace value changes of reveal.
     */
    public interface RevealStateListener {

        /**
         * Called back while reveal status changes.
         */
        void onRevealStateChanged();
    }
}
