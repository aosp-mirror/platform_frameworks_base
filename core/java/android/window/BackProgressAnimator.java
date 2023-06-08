/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.util.FloatProperty;

import com.android.internal.dynamicanimation.animation.DynamicAnimation;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;

/**
 * An animator that drives the predictive back progress with a spring.
 *
 * The back gesture's latest touch point and committal state determines the final position of
 * the spring. The continuous movement of the spring is used to produce {@link BackEvent}s with
 * smoothly transitioning progress values.
 *
 * @hide
 */
public class BackProgressAnimator {
    /**
     *  A factor to scale the input progress by, so that it works better with the spring.
     *  We divide the output progress by this value before sending it to apps, so that apps
     *  always receive progress values in [0, 1].
     */
    private static final float SCALE_FACTOR = 100f;
    private final SpringAnimation mSpring;
    private ProgressCallback mCallback;
    private float mProgress = 0;
    private BackMotionEvent mLastBackEvent;
    private boolean mStarted = false;

    private void setProgress(float progress) {
        mProgress = progress;
    }

    private float getProgress() {
        return mProgress;
    }

    private static final FloatProperty<BackProgressAnimator> PROGRESS_PROP =
            new FloatProperty<BackProgressAnimator>("progress") {
                @Override
                public void setValue(BackProgressAnimator animator, float value) {
                    animator.setProgress(value);
                    animator.updateProgressValue(value);
                }

                @Override
                public Float get(BackProgressAnimator object) {
                    return object.getProgress();
                }
            };


    /** A callback to be invoked when there's a progress value update from the animator. */
    public interface ProgressCallback {
        /** Called when there's a progress value update. */
        void onProgressUpdate(BackEvent event);
    }

    public BackProgressAnimator() {
        mSpring = new SpringAnimation(this, PROGRESS_PROP);
        mSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
    }

    /**
     * Sets a new target position for the back progress.
     *
     * @param event the {@link BackMotionEvent} containing the latest target progress.
     */
    public void onBackProgressed(BackMotionEvent event) {
        if (!mStarted) {
            return;
        }
        mLastBackEvent = event;
        mSpring.animateToFinalPosition(event.getProgress() * SCALE_FACTOR);
    }

    /**
     * Starts the back progress animation.
     *
     * @param event the {@link BackMotionEvent} that started the gesture.
     * @param callback the back callback to invoke for the gesture. It will receive back progress
     *                 dispatches as the progress animation updates.
     */
    public void onBackStarted(BackMotionEvent event, ProgressCallback callback) {
        reset();
        mLastBackEvent = event;
        mCallback = callback;
        mStarted = true;
    }

    /**
     * Resets the back progress animation. This should be called when back is invoked or cancelled.
     */
    public void reset() {
        mSpring.animateToFinalPosition(0);
        if (mSpring.canSkipToEnd()) {
            mSpring.skipToEnd();
        } else {
            // Should never happen.
            mSpring.cancel();
        }
        mStarted = false;
        mLastBackEvent = null;
        mCallback = null;
        mProgress = 0;
    }

    /**
     * Animate the back progress animation from current progress to start position.
     * This should be called when back is cancelled.
     *
     * @param finishCallback the callback to be invoked when the progress is reach to 0.
     */
    public void onBackCancelled(@NonNull Runnable finishCallback) {
        final DynamicAnimation.OnAnimationEndListener listener =
                new DynamicAnimation.OnAnimationEndListener() {
            @Override
            public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                    float velocity) {
                mSpring.removeEndListener(this);
                finishCallback.run();
                reset();
            }
        };
        mSpring.addEndListener(listener);
        mSpring.animateToFinalPosition(0);
    }

    private void updateProgressValue(float progress) {
        if (mLastBackEvent == null || mCallback == null || !mStarted) {
            return;
        }
        mCallback.onProgressUpdate(
                new BackEvent(mLastBackEvent.getTouchX(), mLastBackEvent.getTouchY(),
                        progress / SCALE_FACTOR, mLastBackEvent.getSwipeEdge()));
    }

}
