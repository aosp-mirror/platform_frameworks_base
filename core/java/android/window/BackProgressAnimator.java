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
import android.annotation.Nullable;
import android.util.FloatProperty;
import android.util.TimeUtils;
import android.view.Choreographer;

import com.android.internal.dynamicanimation.animation.DynamicAnimation;
import com.android.internal.dynamicanimation.animation.FlingAnimation;
import com.android.internal.dynamicanimation.animation.FloatValueHolder;
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
public class BackProgressAnimator implements DynamicAnimation.OnAnimationUpdateListener {
    /**
     *  A factor to scale the input progress by, so that it works better with the spring.
     *  We divide the output progress by this value before sending it to apps, so that apps
     *  always receive progress values in [0, 1].
     */
    private static final float SCALE_FACTOR = 100f;
    private static final float FLING_FRICTION = 8f;
    private final SpringAnimation mSpring;
    private ProgressCallback mCallback;
    private float mProgress = 0;
    private float mVelocity = 0;
    private BackMotionEvent mLastBackEvent;
    private boolean mBackAnimationInProgress = false;
    @Nullable
    private Runnable mBackCancelledFinishRunnable;
    @Nullable
    private Runnable mBackInvokedFinishRunnable;
    private FlingAnimation mBackInvokedFlingAnim;
    private final DynamicAnimation.OnAnimationEndListener mOnAnimationEndListener =
            (animation, canceled, value, velocity) -> {
                if (mBackCancelledFinishRunnable != null) invokeBackCancelledRunnable();
                if (mBackInvokedFinishRunnable != null) invokeBackInvokedRunnable();
                reset();
            };
    private final DynamicAnimation.OnAnimationUpdateListener mOnBackInvokedFlingUpdateListener =
            (animation, progress, velocity) -> updateProgressValue(progress, velocity);


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
                }

                @Override
                public Float get(BackProgressAnimator object) {
                    return object.getProgress();
                }
            };

    @Override
    public void onAnimationUpdate(DynamicAnimation animation, float value, float velocity) {
        if (mBackInvokedFinishRunnable == null) updateProgressValue(value, velocity);
    }


    /** A callback to be invoked when there's a progress value update from the animator. */
    public interface ProgressCallback {
        /** Called when there's a progress value update. */
        void onProgressUpdate(BackEvent event);
    }

    public BackProgressAnimator() {
        mSpring = new SpringAnimation(this, PROGRESS_PROP);
        mSpring.addUpdateListener(this);
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
        if (!mBackAnimationInProgress) {
            return;
        }
        mLastBackEvent = event;
        if (mSpring == null) {
            return;
        }
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
        mLastBackEvent = event;
        mCallback = callback;
        mBackAnimationInProgress = true;
        updateProgressValue(0, 0);
    }

    /**
     * Resets the back progress animation. This should be called when back is invoked or cancelled.
     */
    public void reset() {
        if (mBackCancelledFinishRunnable != null) {
            // Ensure that last progress value that apps see is 0
            updateProgressValue(0, 0);
            invokeBackCancelledRunnable();
        } else if (mBackInvokedFinishRunnable != null) {
            invokeBackInvokedRunnable();
        }
        if (mBackInvokedFlingAnim != null) {
            mBackInvokedFlingAnim.cancel();
            mBackInvokedFlingAnim = null;
        }
        mSpring.animateToFinalPosition(0);
        if (mSpring.canSkipToEnd()) {
            mSpring.skipToEnd();
        } else {
            // Should never happen.
            mSpring.cancel();
        }
        mBackAnimationInProgress = false;
        mLastBackEvent = null;
        mCallback = null;
        mProgress = 0;
    }

    /**
     * Animate the back progress animation a bit further with a high friction considering the
     * current progress and velocity.
     *
     * @param finishCallback the callback to be invoked when the final destination is reached
     */
    public void onBackInvoked(@NonNull Runnable finishCallback) {
        mBackInvokedFinishRunnable = finishCallback;
        mSpring.animateToFinalPosition(0);

        mBackInvokedFlingAnim = new FlingAnimation(new FloatValueHolder())
                .setStartValue(mProgress)
                .setFriction(FLING_FRICTION)
                .setStartVelocity(mVelocity)
                .setMinValue(0)
                .setMaxValue(SCALE_FACTOR);
        mBackInvokedFlingAnim.addUpdateListener(mOnBackInvokedFlingUpdateListener);
        mBackInvokedFlingAnim.addEndListener(mOnAnimationEndListener);
        mBackInvokedFlingAnim.start();
        // do an animation-frame immediately to prevent idle frame
        mBackInvokedFlingAnim.doAnimationFrame(
                Choreographer.getInstance().getLastFrameTimeNanos() / TimeUtils.NANOS_PER_MS);
    }

    /**
     * Animate the back progress animation from current progress to start position.
     * This should be called when back is cancelled.
     *
     * @param finishCallback the callback to be invoked when the progress is reach to 0.
     */
    public void onBackCancelled(@NonNull Runnable finishCallback) {
        mBackCancelledFinishRunnable = finishCallback;
        mSpring.addEndListener(mOnAnimationEndListener);
        mSpring.animateToFinalPosition(0);
    }

    /**
     * Removes the finishCallback passed into {@link #onBackCancelled}
     */
    public void removeOnBackCancelledFinishCallback() {
        mSpring.removeEndListener(mOnAnimationEndListener);
        mBackCancelledFinishRunnable = null;
    }

    /** Returns true if the back animation is in progress. */
    boolean isBackAnimationInProgress() {
        return mBackAnimationInProgress;
    }

    /**
     * @return The last recorded velocity. Unit: change in progress per second
     */
    public float getVelocity() {
        return mVelocity / SCALE_FACTOR;
    }

    private void updateProgressValue(float progress, float velocity) {
        mVelocity = velocity;
        if (mLastBackEvent == null || mCallback == null || !mBackAnimationInProgress) {
            return;
        }
        mCallback.onProgressUpdate(
                new BackEvent(mLastBackEvent.getTouchX(), mLastBackEvent.getTouchY(),
                        progress / SCALE_FACTOR, mLastBackEvent.getSwipeEdge()));
    }

    private void invokeBackCancelledRunnable() {
        mSpring.removeEndListener(mOnAnimationEndListener);
        mBackCancelledFinishRunnable.run();
        mBackCancelledFinishRunnable = null;
    }

    private void invokeBackInvokedRunnable() {
        mBackInvokedFlingAnim.removeUpdateListener(mOnBackInvokedFlingUpdateListener);
        mBackInvokedFlingAnim.removeEndListener(mOnAnimationEndListener);
        mBackInvokedFinishRunnable.run();
        mBackInvokedFinishRunnable = null;
    }

}