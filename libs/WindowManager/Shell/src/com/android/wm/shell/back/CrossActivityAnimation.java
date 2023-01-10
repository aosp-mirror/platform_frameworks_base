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
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackProgressAnimator;
import android.window.IOnBackInvokedCallback;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.annotations.ShellMainThread;

/** Class that defines cross-activity animation. */
@ShellMainThread
class CrossActivityAnimation {
    /**
     * Minimum scale of the entering/closing window.
     */
    private static final float MIN_WINDOW_SCALE = 0.9f;

    /**
     * Minimum alpha of the closing/entering window.
     */
    private static final float CLOSING_MIN_WINDOW_ALPHA = 0.5f;

    /**
     * Progress value to fly out closing window and fly in entering window.
     */
    private static final float SWITCH_ENTERING_WINDOW_PROGRESS = 0.5f;

    /** Max window translation in the Y axis. */
    private static final int WINDOW_MAX_DELTA_Y = 160;

    /** Duration of fade in/out entering window. */
    private static final int FADE_IN_DURATION = 100;
    /** Duration of post animation after gesture committed. */
    private static final int POST_ANIMATION_DURATION = 350;
    private static final Interpolator INTERPOLATOR = Interpolators.EMPHASIZED;

    private final Rect mStartTaskRect = new Rect();
    private final float mCornerRadius;

    // The closing window properties.
    private final RectF mClosingRect = new RectF();

    // The entering window properties.
    private final Rect mEnteringStartRect = new Rect();
    private final RectF mEnteringRect = new RectF();

    private float mCurrentAlpha = 1.0f;

    private float mEnteringMargin = 0;
    private ValueAnimator mEnteringAnimator;
    private boolean mEnteringWindowShow = false;

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
    final BackAnimationRunner mBackAnimationRunner;

    private final BackAnimationBackground mBackground;

    CrossActivityAnimation(Context context, BackAnimationBackground background) {
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mBackAnimationRunner = new BackAnimationRunner(new Callback(), new Runner());
        mBackground = background;
    }

    private static float mapRange(float value, float min, float max) {
        return min + (value * (max - min));
    }

    private float getInterpolatedProgress(float backProgress) {
        return INTERPOLATOR.getInterpolation(backProgress);
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
        mBackground.ensureBackground(
                mEnteringTarget.taskInfo.taskDescription.getBackgroundColor(), mTransaction);
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
        mEnteringWindowShow = false;
        mEnteringMargin = 0;
        mEnteringAnimator = null;

        if (mFinishCallback != null) {
            try {
                mFinishCallback.onAnimationFinished();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mFinishCallback = null;
        }
    }

    private void onGestureProgress(@NonNull BackEvent backEvent) {
        if (!mBackInProgress) {
            mInitialTouchPos.set(backEvent.getTouchX(), backEvent.getTouchY());
            mBackInProgress = true;
        }
        mTouchPos.set(backEvent.getTouchX(), backEvent.getTouchY());

        if (mEnteringTarget == null || mClosingTarget == null) {
            return;
        }

        final float progress = getInterpolatedProgress(backEvent.getProgress());
        final float touchY = mTouchPos.y;

        final int width = mStartTaskRect.width();
        final int height = mStartTaskRect.height();

        final float closingScale = mapRange(progress, 1, MIN_WINDOW_SCALE);

        final float closingWidth = closingScale * width;
        final float closingHeight = (float) height / width * closingWidth;

        // Move the window along the X axis.
        final float closingLeft = mStartTaskRect.left + (width - closingWidth) / 2;

        // Move the window along the Y axis.
        final float deltaYRatio = (touchY - mInitialTouchPos.y) / height;
        final float deltaY = (float) Math.sin(deltaYRatio * Math.PI * 0.5f) * WINDOW_MAX_DELTA_Y;
        final float closingTop = (height - closingHeight) * 0.5f + deltaY;
        mClosingRect.set(
                closingLeft, closingTop, closingLeft + closingWidth, closingTop + closingHeight);
        mEnteringRect.set(mClosingRect);

        // Switch closing/entering targets while reach to the threshold progress.
        if (showEnteringWindow(progress > SWITCH_ENTERING_WINDOW_PROGRESS)) {
            return;
        }

        // Present windows and update the alpha.
        mCurrentAlpha = Math.max(mapRange(progress, 1.0f, 0), CLOSING_MIN_WINDOW_ALPHA);
        mClosingRect.offset(mEnteringMargin, 0);
        mEnteringRect.offset(mEnteringMargin - width, 0);

        applyTransform(
                mClosingTarget.leash, mClosingRect, mEnteringWindowShow ? 0.01f : mCurrentAlpha);
        applyTransform(
                mEnteringTarget.leash, mEnteringRect, mEnteringWindowShow ? mCurrentAlpha : 0.01f);
        mTransaction.apply();
    }

    private boolean showEnteringWindow(boolean show) {
        if (mEnteringAnimator == null) {
            mEnteringAnimator = ValueAnimator.ofFloat(1f, 0f).setDuration(FADE_IN_DURATION);
            mEnteringAnimator.setInterpolator(new AccelerateInterpolator());
            mEnteringAnimator.addUpdateListener(animation -> {
                float progress = animation.getAnimatedFraction();
                final int width = mStartTaskRect.width();
                mEnteringMargin = width * progress;
                // We don't animate to 0 or the surface would become invisible and lose focus.
                final float alpha = progress >= 0.5f ? 0.01f
                        : mapRange(progress * 2, mCurrentAlpha, 0.01f);
                mClosingRect.offset(mEnteringMargin, 0);
                mEnteringRect.offset(mEnteringMargin - width, 0);

                applyTransform(mClosingTarget.leash, mClosingRect, alpha);
                applyTransform(mEnteringTarget.leash, mEnteringRect, mCurrentAlpha);
                mTransaction.apply();
            });
        }

        if (mEnteringAnimator.isRunning()) {
            return true;
        }

        if (mEnteringWindowShow == show) {
            return false;
        }

        mEnteringWindowShow = show;
        if (show) {
            mEnteringAnimator.start();
        } else {
            mEnteringAnimator.reverse();
        }
        return true;
    }

    private void onGestureCommitted() {
        if (mEnteringTarget == null || mClosingTarget == null) {
            finishAnimation();
            return;
        }

        // End the fade in animation.
        if (mEnteringAnimator != null && mEnteringAnimator.isRunning()) {
            mEnteringAnimator.cancel();
        }

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
            mTransaction.apply();
        });

        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
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
        float alpha = mapRange(progress, mCurrentAlpha, 1.0f);

        mEnteringRect.set(left, top, left + width, top + height);
        applyTransform(mEnteringTarget.leash, mEnteringRect, alpha);
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
            // End the fade in animation.
            if (mEnteringAnimator != null && mEnteringAnimator.isRunning()) {
                mEnteringAnimator.cancel();
            }
            mProgressAnimator.onBackCancelled(CrossActivityAnimation.this::finishAnimation);
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
        public void onAnimationCancelled(boolean isKeyguardOccluded) {
            finishAnimation();
        }
    }
}
