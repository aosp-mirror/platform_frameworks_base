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
import static android.window.BackEvent.EDGE_RIGHT;

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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.window.BackEvent;
import android.window.BackMotionEvent;
import android.window.BackProgressAnimator;
import android.window.IOnBackInvokedCallback;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.annotations.ShellMainThread;

/**
 * Controls the animation of swiping back and returning to another task.
 *
 * This is a two part animation. The first part is an animation that tracks gesture location to
 * scale and move the closing and entering app windows.
 * Once the gesture is committed, the second part remains the closing window in place.
 * The entering window plays the rest of app opening transition to enter full screen.
 *
 * This animation is used only for apps that enable back dispatching via
 * {@link android.window.OnBackInvokedDispatcher}. The controller registers
 * an {@link IOnBackInvokedCallback} with WM Shell and receives back dispatches when a back
 * navigation to launcher starts.
 */
@ShellMainThread
class CrossTaskBackAnimation {
    private static final int BACKGROUNDCOLOR = 0x43433A;

    /**
     * Minimum scale of the entering window.
     */
    private static final float ENTERING_MIN_WINDOW_SCALE = 0.85f;

    /**
     * Minimum scale of the closing window.
     */
    private static final float CLOSING_MIN_WINDOW_SCALE = 0.75f;

    /**
     * Minimum color scale of the closing window.
     */
    private static final float CLOSING_MIN_WINDOW_COLOR_SCALE = 0.1f;

    /**
     * The margin between the entering window and the closing window
     */
    private static final int WINDOW_MARGIN = 35;

    /** Max window translation in the Y axis. */
    private static final int WINDOW_MAX_DELTA_Y = 160;

    private final Rect mStartTaskRect = new Rect();
    private final float mCornerRadius;

    // The closing window properties.
    private final RectF mClosingCurrentRect = new RectF();

    // The entering window properties.
    private final Rect mEnteringStartRect = new Rect();
    private final RectF mEnteringCurrentRect = new RectF();

    private final PointF mInitialTouchPos = new PointF();
    private final Interpolator mInterpolator = new AccelerateDecelerateInterpolator();

    private final Matrix mTransformMatrix = new Matrix();

    private final float[] mTmpFloat9 = new float[9];
    private final float[] mTmpTranslate = {0, 0, 0};

    private RemoteAnimationTarget mEnteringTarget;
    private RemoteAnimationTarget mClosingTarget;
    private SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    private boolean mBackInProgress = false;

    private boolean mIsRightEdge;
    private float mProgress = 0;
    private PointF mTouchPos = new PointF();
    private IRemoteAnimationFinishedCallback mFinishCallback;
    private BackProgressAnimator mProgressAnimator = new BackProgressAnimator();
    final BackAnimationRunner mBackAnimationRunner;

    private final BackAnimationBackground mBackground;

    CrossTaskBackAnimation(Context context, BackAnimationBackground background) {
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(context);
        mBackAnimationRunner = new BackAnimationRunner(new Callback(), new Runner());
        mBackground = background;
    }

    private float getInterpolatedProgress(float backProgress) {
        return 1 - (1 - backProgress) * (1 - backProgress) * (1 - backProgress);
    }

    private void startBackAnimation() {
        if (mEnteringTarget == null || mClosingTarget == null) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Entering target or closing target is null.");
            return;
        }

        // Offset start rectangle to align task bounds.
        mStartTaskRect.set(mClosingTarget.windowConfiguration.getBounds());
        mStartTaskRect.offsetTo(0, 0);

        // Draw background.
        mBackground.ensureBackground(BACKGROUNDCOLOR, mTransaction);
    }

    private void updateGestureBackProgress(float progress, BackEvent event) {
        if (mEnteringTarget == null || mClosingTarget == null) {
            return;
        }

        float touchX = event.getTouchX();
        float touchY = event.getTouchY();
        float dX = Math.abs(touchX - mInitialTouchPos.x);

        // The 'follow width' is the width of the window if it completely matches
        // the gesture displacement.
        final int width = mStartTaskRect.width();
        final int height = mStartTaskRect.height();

        // The 'progress width' is the width of the window if it strictly linearly interpolates
        // to minimum scale base on progress.
        float enteringScale = mapRange(progress, 1, ENTERING_MIN_WINDOW_SCALE);
        float closingScale = mapRange(progress, 1, CLOSING_MIN_WINDOW_SCALE);
        float closingColorScale = mapRange(progress, 1, CLOSING_MIN_WINDOW_COLOR_SCALE);

        // The final width is derived from interpolating between the follow with and progress width
        // using gesture progress.
        float enteringWidth = enteringScale * width;
        float closingWidth = closingScale * width;
        float enteringHeight = (float) height / width * enteringWidth;
        float closingHeight = (float) height / width * closingWidth;

        float deltaYRatio = (touchY - mInitialTouchPos.y) / height;
        // Base the window movement in the Y axis on the touch movement in the Y axis.
        float deltaY = (float) Math.sin(deltaYRatio * Math.PI * 0.5f) * WINDOW_MAX_DELTA_Y;
        // Move the window along the Y axis.
        float closingTop = (height - closingHeight) * 0.5f + deltaY;
        float enteringTop = (height - enteringHeight) * 0.5f + deltaY;
        // Move the window along the X axis.
        float right = width - (progress * WINDOW_MARGIN);
        float left = right - closingWidth;

        mClosingCurrentRect.set(left, closingTop, right, closingTop + closingHeight);
        mEnteringCurrentRect.set(left - enteringWidth - WINDOW_MARGIN, enteringTop,
                left - WINDOW_MARGIN, enteringTop + enteringHeight);

        applyTransform(mClosingTarget.leash, mClosingCurrentRect, mCornerRadius);
        applyColorTransform(mClosingTarget.leash, closingColorScale);
        applyTransform(mEnteringTarget.leash, mEnteringCurrentRect, mCornerRadius);
        mTransaction.apply();
    }

    private void updatePostCommitClosingAnimation(float progress) {
        mTransaction.setLayer(mClosingTarget.leash, 0);
        float alpha = mapRange(progress, 1, 0);
        mTransaction.setAlpha(mClosingTarget.leash, alpha);
    }

    private void updatePostCommitEnteringAnimation(float progress) {
        float left = mapRange(progress, mEnteringStartRect.left, mStartTaskRect.left);
        float top = mapRange(progress, mEnteringStartRect.top, mStartTaskRect.top);
        float width = mapRange(progress, mEnteringStartRect.width(), mStartTaskRect.width());
        float height = mapRange(progress, mEnteringStartRect.height(), mStartTaskRect.height());

        mEnteringCurrentRect.set(left, top, left + width, top + height);
        applyTransform(mEnteringTarget.leash, mEnteringCurrentRect, mCornerRadius);
    }

    /** Transform the target window to match the target rect. */
    private void applyTransform(SurfaceControl leash, RectF targetRect, float cornerRadius) {
        if (leash == null) {
            return;
        }

        final float scale = targetRect.width() / mStartTaskRect.width();
        mTransformMatrix.reset();
        mTransformMatrix.setScale(scale, scale);
        mTransformMatrix.postTranslate(targetRect.left, targetRect.top);
        mTransaction.setMatrix(leash, mTransformMatrix, mTmpFloat9)
                .setWindowCrop(leash, mStartTaskRect)
                .setCornerRadius(leash, cornerRadius);
    }

    private void applyColorTransform(SurfaceControl leash, float colorScale) {
        if (leash == null) {
            return;
        }
        computeScaleTransformMatrix(colorScale, mTmpFloat9);
        mTransaction.setColorTransform(leash, mTmpFloat9, mTmpTranslate);
    }

    static void computeScaleTransformMatrix(float scale, float[] matrix) {
        matrix[0] = scale;
        matrix[1] = 0;
        matrix[2] = 0;
        matrix[3] = 0;
        matrix[4] = scale;
        matrix[5] = 0;
        matrix[6] = 0;
        matrix[7] = 0;
        matrix[8] = scale;
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
        mClosingCurrentRect.setEmpty();
        mInitialTouchPos.set(0, 0);

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
            mIsRightEdge = backEvent.getSwipeEdge() == EDGE_RIGHT;
            mBackInProgress = true;
        }
        mProgress = backEvent.getProgress();
        mTouchPos.set(backEvent.getTouchX(), backEvent.getTouchY());
        updateGestureBackProgress(getInterpolatedProgress(mProgress), backEvent);
    }

    private void onGestureCommitted() {
        if (mEnteringTarget == null || mClosingTarget == null) {
            finishAnimation();
            return;
        }

        // We enter phase 2 of the animation, the starting coordinates for phase 2 are the current
        // coordinate of the gesture driven phase.
        mEnteringCurrentRect.round(mEnteringStartRect);

        ValueAnimator valueAnimator = ValueAnimator.ofFloat(1f, 0f).setDuration(300);
        valueAnimator.setInterpolator(mInterpolator);
        valueAnimator.addUpdateListener(animation -> {
            float progress = animation.getAnimatedFraction();
            updatePostCommitEnteringAnimation(progress);
            updatePostCommitClosingAnimation(progress);
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

    private static float mapRange(float value, float min, float max) {
        return min + (value * (max - min));
    }

    private final class Callback extends IOnBackInvokedCallback.Default  {
        @Override
        public void onBackStarted(BackMotionEvent backEvent) {
            mProgressAnimator.onBackStarted(backEvent,
                    CrossTaskBackAnimation.this::onGestureProgress);
        }

        @Override
        public void onBackProgressed(@NonNull BackMotionEvent backEvent) {
            mProgressAnimator.onBackProgressed(backEvent);
        }

        @Override
        public void onBackCancelled() {
            mProgressAnimator.onBackCancelled(CrossTaskBackAnimation.this::finishAnimation);
        }

        @Override
        public void onBackInvoked() {
            mProgressAnimator.reset();
            onGestureCommitted();
        }
    };

    private final class Runner extends IRemoteAnimationRunner.Default {
        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) {
            ProtoLog.d(WM_SHELL_BACK_PREVIEW, "Start back to task animation.");
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
    };
}
