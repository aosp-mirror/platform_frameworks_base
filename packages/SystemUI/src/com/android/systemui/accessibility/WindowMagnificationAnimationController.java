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

import java.io.PrintWriter;
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
    private  static final int STATE_DISABLING = 2;
    // The animation is running for enabling the window magnification.
    private static final int STATE_ENABLING = 3;

    private final WindowMagnificationController mController;
    private final ValueAnimator mValueAnimator;
    private final AnimationSpec mStartSpec = new AnimationSpec();
    private final AnimationSpec mEndSpec = new AnimationSpec();
    private final Context mContext;
    // Called when the animation is ended successfully without cancelling or mStartSpec and
    // mEndSpec are equal.
    private IRemoteMagnificationAnimationCallback mAnimationCallback;
    // The flag to ignore the animation end callback.
    private boolean mEndAnimationCanceled = false;
    @MagnificationState
    private int mState = STATE_DISABLED;

    WindowMagnificationAnimationController(@UiContext Context context,
            WindowMagnificationController controller) {
        this(context, controller, newValueAnimator(context.getResources()));
    }

    @VisibleForTesting
    WindowMagnificationAnimationController(Context context,
            WindowMagnificationController controller, ValueAnimator valueAnimator) {
        mContext = context;
        mController = controller;
        mValueAnimator = valueAnimator;
        mValueAnimator.addUpdateListener(this);
        mValueAnimator.addListener(this);
    }

    /**
     * Wraps {@link WindowMagnificationController#enableWindowMagnification(float, float, float)}
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
        sendAnimationCallback(false);
        mAnimationCallback = animationCallback;
        setupEnableAnimationSpecs(scale, centerX, centerY);
        if (mEndSpec.equals(mStartSpec)) {
            if (mState == STATE_DISABLED) {
                mController.enableWindowMagnification(scale, centerX, centerY);
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

    private void setupEnableAnimationSpecs(float scale, float centerX, float centerY) {
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
            mEndSpec.set(Float.isNaN(scale) ? currentScale : scale,
                    Float.isNaN(centerX) ? currentCenterX : centerX,
                    Float.isNaN(centerY) ? currentCenterY : centerY);
        }
        if (DEBUG) {
            Log.d(TAG, "SetupEnableAnimationSpecs : mStartSpec = " + mStartSpec + ", endSpec = "
                    + mEndSpec);
        }
    }

    /**
     * Wraps {@link WindowMagnificationController#setScale(float)}. If the animation is
     * running, it has no effect.
     */
    void setScale(float scale) {
        if (mValueAnimator.isRunning()) {
            return;
        }
        mController.setScale(scale);
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
        sendAnimationCallback(false);
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

    /**
     * Wraps {@link WindowMagnificationController#moveWindowMagnifier(float, float)}. If the
     * animation is running, it has no effect.
     * @param offsetX The amount in pixels to offset the window magnifier in the X direction, in
     *                current screen pixels.
     * @param offsetY The amount in pixels to offset the window magnifier in the Y direction, in
     *                current screen pixels.
     */
    void moveWindowMagnifier(float offsetX, float offsetY) {
        if (mValueAnimator.isRunning()) {
            return;
        }
        mController.moveWindowMagnifier(offsetX, offsetY);
    }

    void onConfigurationChanged(int configDiff) {
        mController.onConfigurationChanged(configDiff);
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
        if (mEndAnimationCanceled) {
            return;
        }
        if (isReverse) {
            mController.deleteWindowMagnification();
            setState(STATE_DISABLED);
        } else {
            setState(STATE_ENABLED);
        }
        sendAnimationCallback(true);
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
        final float fract = animation.getAnimatedFraction();
        final float sentScale = mStartSpec.mScale + (mEndSpec.mScale - mStartSpec.mScale) * fract;
        final float centerX =
                mStartSpec.mCenterX + (mEndSpec.mCenterX - mStartSpec.mCenterX) * fract;
        final float centerY =
                mStartSpec.mCenterY + (mEndSpec.mCenterY - mStartSpec.mCenterY) * fract;
        mController.enableWindowMagnification(sentScale, centerX, centerY);
    }

    public void updateSysUiStateFlag() {
        mController.updateSysUIStateFlag();
    }

    void dump(PrintWriter pw) {
        mController.dump(pw);
    }

    private static ValueAnimator newValueAnimator(Resources resources) {
        final ValueAnimator valueAnimator = new ValueAnimator();
        valueAnimator.setDuration(
                resources.getInteger(com.android.internal.R.integer.config_longAnimTime));
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
