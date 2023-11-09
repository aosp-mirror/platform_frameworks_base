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

package com.android.wm.shell.back;

import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY;
import static com.android.wm.shell.back.BackAnimationConstants.PROGRESS_COMMIT_THRESHOLD;
import static com.android.wm.shell.back.BackAnimationConstants.UPDATE_SYSUI_FLAGS_THRESHOLD;
import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_BACK_PREVIEW;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.RemoteException;
import android.util.FloatProperty;
import android.util.TypedValue;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackProgressAnimator;
import android.window.IOnBackInvokedCallback;

import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.annotations.ShellMainThread;

import javax.inject.Inject;

/** Class that defines cross-activity animation. */
@ShellMainThread
public class CrossActivityAnimation extends ShellBackAnimation {
    /**
     * Minimum scale of the entering/closing window.
     */
    private static final float MIN_WINDOW_SCALE = 0.9f;

    /** Duration of post animation after gesture committed. */
    private static final int POST_ANIMATION_DURATION = 350;
    private static final Interpolator INTERPOLATOR = new DecelerateInterpolator();
    private static final FloatProperty<CrossActivityAnimation> ENTER_PROGRESS_PROP =
            new FloatProperty<>("enter-alpha") {
                @Override
                public void setValue(CrossActivityAnimation anim, float value) {
                    anim.setEnteringProgress(value);
                }

                @Override
                public Float get(CrossActivityAnimation object) {
                    return object.getEnteringProgress();
                }
            };
    private static final FloatProperty<CrossActivityAnimation> LEAVE_PROGRESS_PROP =
            new FloatProperty<>("leave-alpha") {
                @Override
                public void setValue(CrossActivityAnimation anim, float value) {
                    anim.setLeavingProgress(value);
                }

                @Override
                public Float get(CrossActivityAnimation object) {
                    return object.getLeavingProgress();
                }
            };
    private static final float MIN_WINDOW_ALPHA = 0.01f;
    private static final float WINDOW_X_SHIFT_DP = 96;
    private static final int SCALE_FACTOR = 100;
    // TODO(b/264710590): Use the progress commit threshold from ViewConfiguration once it exists.
    private static final float TARGET_COMMIT_PROGRESS = 0.5f;
    private static final float ENTER_ALPHA_THRESHOLD = 0.22f;

    private final Rect mStartTaskRect = new Rect();
    private final float mCornerRadius;

    // The closing window properties.
    private final RectF mClosingRect = new RectF();

    // The entering window properties.
    private final Rect mEnteringStartRect = new Rect();
    private final RectF mEnteringRect = new RectF();
    private final SpringAnimation mEnteringProgressSpring;
    private final SpringAnimation mLeavingProgressSpring;
    // Max window x-shift in pixels.
    private final float mWindowXShift;
    private final BackAnimationRunner mBackAnimationRunner;

    private float mEnteringProgress = 0f;
    private float mLeavingProgress = 0f;

    private final PointF mInitialTouchPos = new PointF();

    private final Matrix mTransformMatrix = new Matrix();

    private final float[] mTmpFloat9 = new float[9];

    private RemoteAnimationTarget mEnteringTarget;
    private RemoteAnimationTarget mClosingTarget;
    private SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    private boolean mBackInProgress = false;

    private PointF mTouchPos = new PointF();
    private IRemoteAnimationFinishedCallback mFinishCallback;

    private final BackProgressAnimator mProgressAnimator = new BackProgressAnimator();

    private final BackAnimationBackground mBackground;

    @Inject
    public CrossActivityAnimation(Context context, BackAnimationBackground background) {
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mBackAnimationRunner = new BackAnimationRunner(
                new Callback(), new Runner(), context, CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY);
        mBackground = background;
        mEnteringProgressSpring = new SpringAnimation(this, ENTER_PROGRESS_PROP);
        mEnteringProgressSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        mLeavingProgressSpring = new SpringAnimation(this, LEAVE_PROGRESS_PROP);
        mLeavingProgressSpring.setSpring(new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        mWindowXShift = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, WINDOW_X_SHIFT_DP,
                context.getResources().getDisplayMetrics());
    }

    /**
     * Returns 1 if x >= edge1, 0 if x <= edge0, and a smoothed value between the two.
     * From https://en.wikipedia.org/wiki/Smoothstep
     */
    private static float smoothstep(float edge0, float edge1, float x) {
        if (x < edge0) return 0;
        if (x >= edge1) return 1;

        x = (x - edge0) / (edge1 - edge0);
        return x * x * (3 - 2 * x);
    }

    /**
     * Linearly map x from range (a1, a2) to range (b1, b2).
     */
    private static float mapLinear(float x, float a1, float a2, float b1, float b2) {
        return b1 + (x - a1) * (b2 - b1) / (a2 - a1);
    }

    /**
     * Linearly map a normalized value from (0, 1) to (min, max).
     */
    private static float mapRange(float value, float min, float max) {
        return min + (value * (max - min));
    }

    private void startBackAnimation() {
        if (mEnteringTarget == null || mClosingTarget == null) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Entering target or closing target is null.");
            return;
        }
        mTransaction.setAnimationTransaction();

        // Offset start rectangle to align task bounds.
        mStartTaskRect.set(mClosingTarget.windowConfiguration.getBounds());
        mStartTaskRect.offsetTo(0, 0);

        // Draw background with task background color.
        mBackground.ensureBackground(mClosingTarget.windowConfiguration.getBounds(),
                mEnteringTarget.taskInfo.taskDescription.getBackgroundColor(), mTransaction);
        setEnteringProgress(0);
        setLeavingProgress(0);
    }

    private void applyTransform(SurfaceControl leash, RectF targetRect, float targetAlpha) {
        final float scale = targetRect.width() / mStartTaskRect.width();
        mTransformMatrix.reset();
        mTransformMatrix.setScale(scale, scale);
        mTransformMatrix.postTranslate(targetRect.left, targetRect.top);
        mTransaction.setAlpha(leash, targetAlpha)
                .setMatrix(leash, mTransformMatrix, mTmpFloat9)
                .setWindowCrop(leash, mStartTaskRect)
                .setCornerRadius(leash, mCornerRadius);
    }

    private void finishAnimation() {
        if (mEnteringTarget != null) {
            mEnteringTarget.leash.release();
            mEnteringTarget = null;
        }
        if (mClosingTarget != null) {
            mClosingTarget.leash.release();
            mClosingTarget = null;
        }
        if (mBackground != null) {
            mBackground.removeBackground(mTransaction);
        }

        mTransaction.apply();
        mBackInProgress = false;
        mTransformMatrix.reset();
        mInitialTouchPos.set(0, 0);

        if (mFinishCallback != null) {
            try {
                mFinishCallback.onAnimationFinished();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mFinishCallback = null;
        }
        mEnteringProgressSpring.animateToFinalPosition(0);
        mEnteringProgressSpring.skipToEnd();
        mLeavingProgressSpring.animateToFinalPosition(0);
        mLeavingProgressSpring.skipToEnd();
    }

    private void onGestureProgress(@NonNull BackEvent backEvent) {
        if (!mBackInProgress) {
            mInitialTouchPos.set(backEvent.getTouchX(), backEvent.getTouchY());
            mBackInProgress = true;
        }
        mTouchPos.set(backEvent.getTouchX(), backEvent.getTouchY());

        float progress = backEvent.getProgress();
        float springProgress = (progress > PROGRESS_COMMIT_THRESHOLD
                ? mapLinear(progress, 0.1f, 1, TARGET_COMMIT_PROGRESS, 1)
                : mapLinear(progress, 0, 1f, 0, TARGET_COMMIT_PROGRESS)) * SCALE_FACTOR;
        mLeavingProgressSpring.animateToFinalPosition(springProgress);
        mEnteringProgressSpring.animateToFinalPosition(springProgress);
        mBackground.onBackProgressed(progress);
    }

    private void onGestureCommitted() {
        if (mEnteringTarget == null || mClosingTarget == null) {
            finishAnimation();
            return;
        }
        // End the fade animations
        mLeavingProgressSpring.cancel();
        mEnteringProgressSpring.cancel();

        // We enter phase 2 of the animation, the starting coordinates for phase 2 are the current
        // coordinate of the gesture driven phase.
        mEnteringRect.round(mEnteringStartRect);
        mTransaction.hide(mClosingTarget.leash);

        ValueAnimator valueAnimator =
                ValueAnimator.ofFloat(1f, 0f).setDuration(POST_ANIMATION_DURATION);
        valueAnimator.setInterpolator(new DecelerateInterpolator());
        valueAnimator.addUpdateListener(animation -> {
            float progress = animation.getAnimatedFraction();
            updatePostCommitEnteringAnimation(progress);
            if (progress > 1 - UPDATE_SYSUI_FLAGS_THRESHOLD) {
                mBackground.resetStatusBarCustomization();
            }
            mTransaction.apply();
        });

        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackground.resetStatusBarCustomization();
                finishAnimation();
            }
        });
        valueAnimator.start();
    }

    private void updatePostCommitEnteringAnimation(float progress) {
        float left = mapRange(progress, mEnteringStartRect.left, mStartTaskRect.left);
        float top = mapRange(progress, mEnteringStartRect.top, mStartTaskRect.top);
        float width = mapRange(progress, mEnteringStartRect.width(), mStartTaskRect.width());
        float height = mapRange(progress, mEnteringStartRect.height(), mStartTaskRect.height());
        float alpha = mapRange(progress, mEnteringProgress, 1.0f);

        mEnteringRect.set(left, top, left + width, top + height);
        applyTransform(mEnteringTarget.leash, mEnteringRect, alpha);
    }

    private float getEnteringProgress() {
        return mEnteringProgress * SCALE_FACTOR;
    }

    private void setEnteringProgress(float value) {
        mEnteringProgress = value / SCALE_FACTOR;
        if (mEnteringTarget != null && mEnteringTarget.leash != null) {
            transformWithProgress(
                    mEnteringProgress,
                    Math.max(
                            smoothstep(ENTER_ALPHA_THRESHOLD, 1, mEnteringProgress),
                            MIN_WINDOW_ALPHA),  /* alpha */
                    mEnteringTarget.leash,
                    mEnteringRect,
                    -mWindowXShift,
                    0
            );
        }
    }

    private float getLeavingProgress() {
        return mLeavingProgress * SCALE_FACTOR;
    }

    private void setLeavingProgress(float value) {
        mLeavingProgress = value / SCALE_FACTOR;
        if (mClosingTarget != null && mClosingTarget.leash != null) {
            transformWithProgress(
                    mLeavingProgress,
                    Math.max(
                            1 - smoothstep(0, ENTER_ALPHA_THRESHOLD, mLeavingProgress),
                            MIN_WINDOW_ALPHA),
                    mClosingTarget.leash,
                    mClosingRect,
                    0,
                    mWindowXShift
            );
        }
    }

    private void transformWithProgress(float progress, float alpha, SurfaceControl surface,
            RectF targetRect, float deltaXMin, float deltaXMax) {
        final float touchY = mTouchPos.y;

        final int width = mStartTaskRect.width();
        final int height = mStartTaskRect.height();

        final float interpolatedProgress = INTERPOLATOR.getInterpolation(progress);
        final float closingScale = MIN_WINDOW_SCALE
                + (1 - interpolatedProgress) * (1 - MIN_WINDOW_SCALE);
        final float closingWidth = closingScale * width;
        final float closingHeight = (float) height / width * closingWidth;

        // Move the window along the X axis.
        float closingLeft = mStartTaskRect.left + (width - closingWidth) / 2;
        closingLeft += mapRange(interpolatedProgress, deltaXMin, deltaXMax);

        // Move the window along the Y axis.
        final float closingTop = (height - closingHeight) * 0.5f;
        targetRect.set(
                closingLeft, closingTop, closingLeft + closingWidth, closingTop + closingHeight);

        applyTransform(surface, targetRect, Math.max(alpha, MIN_WINDOW_ALPHA));
        mTransaction.apply();
    }

    @Override
    public BackAnimationRunner getRunner() {
        return mBackAnimationRunner;
    }

    private final class Callback extends IOnBackInvokedCallback.Default {
        @Override
        public void onBackStarted(BackMotionEvent backEvent) {
            mProgressAnimator.onBackStarted(backEvent,
                    CrossActivityAnimation.this::onGestureProgress);
        }

        @Override
        public void onBackProgressed(@NonNull BackMotionEvent backEvent) {
            mProgressAnimator.onBackProgressed(backEvent);
        }

        @Override
        public void onBackCancelled() {
            mProgressAnimator.onBackCancelled(() -> {
                // mProgressAnimator can reach finish stage earlier than mLeavingProgressSpring,
                // and if we release all animation leash first, the leavingProgressSpring won't
                // able to update the animation anymore, which cause flicker.
                // Here should force update the closing animation target to the final stage before
                // release it.
                setLeavingProgress(0);
                finishAnimation();
            });
        }

        @Override
        public void onBackInvoked() {
            mProgressAnimator.reset();
            onGestureCommitted();
        }
    }

    private final class Runner extends IRemoteAnimationRunner.Default {
        @Override
        public void onAnimationStart(
                int transit,
                RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Start back to activity animation.");
            for (RemoteAnimationTarget a : apps) {
                if (a.mode == MODE_CLOSING) {
                    mClosingTarget = a;
                }
                if (a.mode == MODE_OPENING) {
                    mEnteringTarget = a;
                }
            }

            startBackAnimation();
            mFinishCallback = finishedCallback;
        }

        @Override
        public void onAnimationCancelled() {
            finishAnimation();
        }
    }
}
