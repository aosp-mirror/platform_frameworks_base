/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.content.Context;
import android.content.res.Resources;
import android.os.RemoteException;
import android.util.Log;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.animation.AccelerateInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides same functionality of {@link WindowMagnificationController}. Some methods run with
 * the animation.
 */
class WindowMagnificationAnimationController implements ValueAnimator.AnimatorUpdateListener,
        Animator.AnimatorListener {

    private static final String TAG = "WindowMagnificationAnimationController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_DISABLED, STATE_ENABLED, STATE_DISABLING, STATE_ENABLING})
    @interface MagnificationState {}

    // The window magnification is disabled.
    private static final int STATE_DISABLED = 0;
    // The window magnification is enabled.
    private static final int STATE_ENABLED = 1;
    // The window magnification is going to be disabled when the animation is end.
    private static final int STATE_DISABLING = 2;
    // The animation is running for enabling the window magnification.
    private static final int STATE_ENABLING = 3;

    private WindowMagnificationController mController;
    private final ValueAnimator mValueAnimator;
    private final AnimationSpec mStartSpec = new AnimationSpec();
    private final AnimationSpec mEndSpec = new AnimationSpec();
    private float mMagnificationFrameOffsetRatioX = 0f;
    private float mMagnificationFrameOffsetRatioY = 0f;
    private final Context mContext;
    // Called when the animation is ended successfully without cancelling or mStartSpec and
    // mEndSpec are equal.
    private IRemoteMagnificationAnimationCallback mAnimationCallback;
    // The flag to ignore the animation end callback.
    private boolean mEndAnimationCanceled = false;
    @MagnificationState
    private int mState = STATE_DISABLED;

    WindowMagnificationAnimationController(@UiContext Context context) {
        this(context, newValueAnimator(context.getResources()));
    }

    @VisibleForTesting
    WindowMagnificationAnimationController(Context context, ValueAnimator valueAnimator) {
        mContext = context;
        mValueAnimator = valueAnimator;
        mValueAnimator.addUpdateListener(this);
        mValueAnimator.addListener(this);
    }

    void setWindowMagnificationController(@NonNull WindowMagnificationController controller) {
        mController = controller;
    }

    /**
     * Wraps {@link WindowMagnificationController#enableWindowMagnification(float, float, float,
     * float, float, IRemoteMagnificationAnimationCallback)}
     * with transition animation. If the window magnification is not enabled, the scale will start
     * from 1.0 and the center won't be changed during the animation. If {@link #mState} is
     * {@code STATE_DISABLING}, the animation runs in reverse.
     *
     * @param scale   The target scale, or {@link Float#NaN} to leave unchanged.
     * @param centerX The screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param animationCallback Called when the transition is complete, the given arguments
     *                          are as same as current values, or the transition is interrupted
     *                          due to the new transition request.
     *
     * @see #onAnimationUpdate(ValueAnimator)
     */
    void enableWindowMagnification(float scale, float centerX, float centerY,
            @Nullable IRemoteMagnificationAnimationCallback animationCallback) {
        enableWindowMagnification(scale, centerX, centerY, 0f, 0f, animationCallback);
    }

    /**
     * Wraps {@link WindowMagnificationController#enableWindowMagnification(float, float, float,
     * float, float, IRemoteMagnificationAnimationCallback)}
     * with transition animation. If the window magnification is not enabled, the scale will start
     * from 1.0 and the center won't be changed during the animation. If {@link #mState} is
     * {@code STATE_DISABLING}, the animation runs in reverse.
     *
     * @param scale   The target scale, or {@link Float#NaN} to leave unchanged.
     * @param centerX The screen-relative X coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY The screen-relative Y coordinate around which to center for magnification,
     *                or {@link Float#NaN} to leave unchanged.
     * @param magnificationFrameOffsetRatioX Indicate the X coordinate offset between
     *                                       frame position X and centerX
     * @param magnificationFrameOffsetRatioY Indicate the Y coordinate offset between
     *                                       frame position Y and centerY
     * @param animationCallback Called when the transition is complete, the given arguments
     *                          are as same as current values, or the transition is interrupted
     *                          due to the new transition request.
     *
     * @see #onAnimationUpdate(ValueAnimator)
     */
    void enableWindowMagnification(float scale, float centerX, float centerY,
            float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
            @Nullable IRemoteMagnificationAnimationCallback animationCallback) {
        if (mController == null) {
            return;
        }
        sendAnimationCallback(false);
        mMagnificationFrameOffsetRatioX = magnificationFrameOffsetRatioX;
        mMagnificationFrameOffsetRatioY = magnificationFrameOffsetRatioY;

        // Enable window magnification without animation immediately.
        if (animationCallback == null) {
            if (mState == STATE_ENABLING || mState == STATE_DISABLING) {
                mValueAnimator.cancel();
            }
            mController.enableWindowMagnificationInternal(scale, centerX, centerY,
                    mMagnificationFrameOffsetRatioX, mMagnificationFrameOffsetRatioY);
            setState(STATE_ENABLED);
            return;
        }
        mAnimationCallback = animationCallback;
        setupEnableAnimationSpecs(scale, centerX, centerY);

        if (mEndSpec.equals(mStartSpec)) {
            if (mState == STATE_DISABLED) {
                mController.enableWindowMagnificationInternal(scale, centerX, centerY,
                        mMagnificationFrameOffsetRatioX, mMagnificationFrameOffsetRatioY);
            } else if (mState == STATE_ENABLING || mState == STATE_DISABLING) {
                mValueAnimator.cancel();
            }
            sendAnimationCallback(true);
            setState(STATE_ENABLED);
        } else {
            if (mState == STATE_DISABLING) {
                mValueAnimator.reverse();
            } else {
                if (mState == STATE_ENABLING) {
                    mValueAnimator.cancel();
                }
                mValueAnimator.start();
            }
            setState(STATE_ENABLING);
        }
    }

    void moveWindowMagnifierToPosition(float centerX, float centerY,
            IRemoteMagnificationAnimationCallback callback) {
        if (mState == STATE_ENABLED) {
            // We set the animation duration to shortAnimTime which would be reset at the end.
            mValueAnimator.setDuration(mContext.getResources()
                    .getInteger(com.android.internal.R.integer.config_shortAnimTime));
            enableWindowMagnification(Float.NaN, centerX, centerY,
                    /* magnificationFrameOffsetRatioX */ Float.NaN,
                    /* magnificationFrameOffsetRatioY */ Float.NaN, callback);
        } else if (mState == STATE_ENABLING) {
            sendAnimationCallback(false);
            mAnimationCallback = callback;
            mValueAnimator.setDuration(mContext.getResources()
                    .getInteger(com.android.internal.R.integer.config_shortAnimTime));
            setupEnableAnimationSpecs(Float.NaN, centerX, centerY);
        }
    }

    private void setupEnableAnimationSpecs(float scale, float centerX, float centerY) {
        if (mController == null) {
            return;
        }
        final float currentScale = mController.getScale();
        final float currentCenterX = mController.getCenterX();
        final float currentCenterY = mController.getCenterY();

        if (mState == STATE_DISABLED) {
            // We don't need to offset the center during the animation.
            mStartSpec.set(/* scale*/ 1.0f, centerX, centerY);
            mEndSpec.set(Float.isNaN(scale) ? mContext.getResources().getInteger(
                    R.integer.magnification_default_scale) : scale, centerX, centerY);
        } else {
            mStartSpec.set(currentScale, currentCenterX, currentCenterY);

            final float endScale = (mState == STATE_ENABLING ? mEndSpec.mScale : currentScale);
            final float endCenterX =
                    (mState == STATE_ENABLING ? mEndSpec.mCenterX : currentCenterX);
            final float endCenterY =
                    (mState == STATE_ENABLING ? mEndSpec.mCenterY : currentCenterY);

            mEndSpec.set(Float.isNaN(scale) ? endScale : scale,
                    Float.isNaN(centerX) ? endCenterX : centerX,
                    Float.isNaN(centerY) ? endCenterY : centerY);
        }
        if (DEBUG) {
            Log.d(TAG, "SetupEnableAnimationSpecs : mStartSpec = " + mStartSpec + ", endSpec = "
                    + mEndSpec);
        }
    }

    /** Returns {@code true} if the animator is running. */
    boolean isAnimating() {
        return mValueAnimator.isRunning();
    }

    /**
     * Wraps {@link WindowMagnificationController#deleteWindowMagnification()}} with transition
     * animation. If the window magnification is enabling, it runs the animation in reverse.
     *
     * @param animationCallback Called when the transition is complete, the given arguments
     *                          are as same as current values, or the transition is interrupted
     *                          due to the new transition request.
     */
    void deleteWindowMagnification(
            @Nullable IRemoteMagnificationAnimationCallback animationCallback) {
        if (mController == null) {
            return;
        }
        sendAnimationCallback(false);
        // Delete window magnification without animation.
        if (animationCallback == null) {
            if (mState == STATE_ENABLING || mState == STATE_DISABLING) {
                mValueAnimator.cancel();
            }
            mController.deleteWindowMagnification();
            setState(STATE_DISABLED);
            return;
        }

        mAnimationCallback = animationCallback;
        if (mState == STATE_DISABLED || mState == STATE_DISABLING) {
            if (mState == STATE_DISABLED) {
                sendAnimationCallback(true);
            }
            return;
        }
        mStartSpec.set(/* scale*/ 1.0f, Float.NaN, Float.NaN);
        mEndSpec.set(/* scale*/ mController.getScale(), Float.NaN, Float.NaN);

        mValueAnimator.reverse();
        setState(STATE_DISABLING);
    }

    private void setState(@MagnificationState int state) {
        if (DEBUG) {
            Log.d(TAG, "setState from " + mState + " to " + state);
        }
        mState = state;
    }

    @Override
    public void onAnimationStart(Animator animation) {
        mEndAnimationCanceled = false;
    }

    @Override
    public void onAnimationEnd(Animator animation, boolean isReverse) {
        if (mEndAnimationCanceled || mController == null) {
            return;
        }
        if (Float.isNaN(mController.getScale())) {
            setState(STATE_DISABLED);
        } else {
            setState(STATE_ENABLED);
        }
        sendAnimationCallback(true);
        // We reset the duration to config_longAnimTime
        mValueAnimator.setDuration(mContext.getResources()
                .getInteger(com.android.internal.R.integer.config_longAnimTime));
    }

    @Override
    public void onAnimationEnd(Animator animation) {
    }

    @Override
    public void onAnimationCancel(Animator animation) {
        mEndAnimationCanceled = true;
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
    }

    private void sendAnimationCallback(boolean success) {
        if (mAnimationCallback != null) {
            try {
                mAnimationCallback.onResult(success);
                if (DEBUG) {
                    Log.d(TAG, "sendAnimationCallback success = " + success);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "sendAnimationCallback failed : " + e);
            }
            mAnimationCallback = null;
        }
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        if (mController == null) {
            return;
        }
        final float fract = animation.getAnimatedFraction();
        final float sentScale = mStartSpec.mScale + (mEndSpec.mScale - mStartSpec.mScale) * fract;
        final float centerX =
                mStartSpec.mCenterX + (mEndSpec.mCenterX - mStartSpec.mCenterX) * fract;
        final float centerY =
                mStartSpec.mCenterY + (mEndSpec.mCenterY - mStartSpec.mCenterY) * fract;
        mController.enableWindowMagnificationInternal(sentScale, centerX, centerY,
                mMagnificationFrameOffsetRatioX, mMagnificationFrameOffsetRatioY);
    }

    private static ValueAnimator newValueAnimator(Resources resource) {
        final ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(
                resource.getInteger(com.android.internal.R.integer.config_longAnimTime));
        valueAnimator.setInterpolator(new AccelerateInterpolator(2.5f));
        valueAnimator.setFloatValues(0.0f, 1.0f);
        return valueAnimator;
    }

    private static class AnimationSpec {
        private float mScale = Float.NaN;
        private float mCenterX = Float.NaN;
        private float mCenterY = Float.NaN;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (other == null || getClass() != other.getClass()) {
                return false;
            }

            final AnimationSpec s = (AnimationSpec) other;
            return mScale == s.mScale && mCenterX == s.mCenterX && mCenterY == s.mCenterY;
        }

        @Override
        public int hashCode() {
            int result = (mScale != +0.0f ? Float.floatToIntBits(mScale) : 0);
            result = 31 * result + (mCenterX != +0.0f ? Float.floatToIntBits(mCenterX) : 0);
            result = 31 * result + (mCenterY != +0.0f ? Float.floatToIntBits(mCenterY) : 0);
            return result;
        }

        void set(float scale, float centerX, float centerY) {
            mScale = scale;
            mCenterX = centerX;
            mCenterY = centerY;
        }

        @Override
        public String toString() {
            return "AnimationSpec{"
                    + "mScale=" + mScale
                    + ", mCenterX=" + mCenterX
                    + ", mCenterY=" + mCenterY
                    + '}';
        }
    }
}
