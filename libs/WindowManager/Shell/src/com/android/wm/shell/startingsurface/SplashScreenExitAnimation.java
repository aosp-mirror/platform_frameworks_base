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
package com.android.wm.shell.startingsurface;

import static android.view.Choreographer.CALLBACK_COMMIT;
import static android.view.View.GONE;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Slog;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.Transformation;
import android.view.animation.TranslateYAnimation;
import android.window.SplashScreenView;

import com.android.wm.shell.common.TransactionPool;

/**
 * Default animation for exiting the splash screen window.
 * @hide
 */
public class SplashScreenExitAnimation implements Animator.AnimatorListener {
    private static final boolean DEBUG_EXIT_ANIMATION = false;
    private static final boolean DEBUG_EXIT_ANIMATION_BLEND = false;
    private static final String TAG = StartingSurfaceDrawer.TAG;

    private static final Interpolator APP_EXIT_INTERPOLATOR = new PathInterpolator(0f, 0f, 0f, 1f);

    private final Matrix mTmpTransform = new Matrix();
    private final SurfaceControl mFirstWindowSurface;
    private final Rect mFirstWindowFrame = new Rect();
    private final SplashScreenView mSplashScreenView;
    private final int mMainWindowShiftLength;
    private final int mAppDuration;
    private final TransactionPool mTransactionPool;

    private ValueAnimator mMainAnimator;
    private ShiftUpAnimation mShiftUpAnimation;
    private Runnable mFinishCallback;

    SplashScreenExitAnimation(SplashScreenView view, SurfaceControl leash, Rect frame,
            int appDuration, int mainWindowShiftLength, TransactionPool pool,
            Runnable handleFinish) {
        mSplashScreenView = view;
        mFirstWindowSurface = leash;
        if (frame != null) {
            mFirstWindowFrame.set(frame);
        }
        mAppDuration = appDuration;
        mMainWindowShiftLength = mainWindowShiftLength;
        mFinishCallback = handleFinish;
        mTransactionPool = pool;
    }

    void startAnimations() {
        prepareRevealAnimation();
        if (mMainAnimator != null) {
            mMainAnimator.start();
        }
        if (mShiftUpAnimation != null) {
            mShiftUpAnimation.start();
        }
    }

    // reveal splash screen, shift up main window
    private void prepareRevealAnimation() {
        // splash screen
        mMainAnimator = ValueAnimator.ofFloat(0f, 1f);
        mMainAnimator.setDuration(mAppDuration);
        mMainAnimator.setInterpolator(APP_EXIT_INTERPOLATOR);
        mMainAnimator.addListener(this);

        final float transparentRatio = 0.8f;
        final int globalHeight = mSplashScreenView.getHeight();
        final int verticalCircleCenter = 0;
        final int finalVerticalLength = globalHeight - verticalCircleCenter;
        final int halfWidth = mSplashScreenView.getWidth() / 2;
        final int endRadius = (int) (0.5 + (1f / transparentRatio * (int)
                Math.sqrt(finalVerticalLength * finalVerticalLength + halfWidth * halfWidth)));
        final RadialVanishAnimation radialVanishAnimation = new RadialVanishAnimation(
                mSplashScreenView, mMainAnimator);
        radialVanishAnimation.setCircleCenter(halfWidth, verticalCircleCenter);
        radialVanishAnimation.setRadius(0/* initRadius */, endRadius);
        final int[] colors = {Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE};
        final float[] stops = {0f, transparentRatio, 1f};
        radialVanishAnimation.setRadialPaintParam(colors, stops);
        radialVanishAnimation.setReady();

        if (mFirstWindowSurface != null && mFirstWindowSurface.isValid()) {
            // shift up main window
            View occludeHoleView = new View(mSplashScreenView.getContext());
            if (DEBUG_EXIT_ANIMATION_BLEND) {
                occludeHoleView.setBackgroundColor(Color.BLUE);
            } else {
                occludeHoleView.setBackgroundColor(mSplashScreenView.getInitBackgroundColor());
            }
            final ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, mMainWindowShiftLength);
            mSplashScreenView.addView(occludeHoleView, params);

            mShiftUpAnimation = new ShiftUpAnimation(0, -mMainWindowShiftLength, occludeHoleView);
            mShiftUpAnimation.setDuration(mAppDuration);
            mShiftUpAnimation.setInterpolator(APP_EXIT_INTERPOLATOR);

            occludeHoleView.setAnimation(mShiftUpAnimation);
        }
    }

    private static class RadialVanishAnimation extends View {
        private final SplashScreenView mView;
        private int mInitRadius;
        private int mFinishRadius;
        private boolean mReady;

        private final Point mCircleCenter = new Point();
        private final Matrix mVanishMatrix = new Matrix();
        private final Paint mVanishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RadialVanishAnimation(SplashScreenView target, ValueAnimator animator) {
            super(target.getContext());
            mView = target;
            animator.addUpdateListener((animation) -> {
                if (mVanishPaint.getShader() == null) {
                    return;
                }
                final float value = (float) animation.getAnimatedValue();
                final float scale = (mFinishRadius - mInitRadius) * value + mInitRadius;
                mVanishMatrix.setScale(scale, scale);
                mVanishMatrix.postTranslate(mCircleCenter.x, mCircleCenter.y);
                mVanishPaint.getShader().setLocalMatrix(mVanishMatrix);
                postInvalidate();
            });
            mView.addView(this);
        }

        void setRadius(int initRadius, int finishRadius) {
            if (DEBUG_EXIT_ANIMATION) {
                Slog.v(TAG, "RadialVanishAnimation setRadius init: " + initRadius
                        + " final " + finishRadius);
            }
            mInitRadius = initRadius;
            mFinishRadius = finishRadius;
        }

        void setCircleCenter(int x, int y) {
            if (DEBUG_EXIT_ANIMATION) {
                Slog.v(TAG, "RadialVanishAnimation setCircleCenter x: " + x + " y " + y);
            }
            mCircleCenter.set(x, y);
        }

        void setRadialPaintParam(int[] colors, float[] stops) {
            // setup gradient shader
            final RadialGradient rShader =
                    new RadialGradient(0, 0, 1, colors, stops, Shader.TileMode.CLAMP);
            mVanishPaint.setShader(rShader);
            if (!DEBUG_EXIT_ANIMATION_BLEND) {
                mVanishPaint.setBlendMode(BlendMode.MODULATE);
            }
        }

        void setReady() {
            mReady = true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mReady) {
                canvas.drawRect(0, 0, mView.getWidth(), mView.getHeight(), mVanishPaint);
            }
        }
    }

    private final class ShiftUpAnimation extends TranslateYAnimation {
        final SyncRtSurfaceTransactionApplier mApplier;
        ShiftUpAnimation(float fromYDelta, float toYDelta, View targetView) {
            super(fromYDelta, toYDelta);
            mApplier = new SyncRtSurfaceTransactionApplier(targetView);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            super.applyTransformation(interpolatedTime, t);

            if (mFirstWindowSurface == null || !mFirstWindowSurface.isValid()) {
                return;
            }
            mTmpTransform.set(t.getMatrix());

            // set the vsyncId to ensure the transaction doesn't get applied too early.
            final SurfaceControl.Transaction tx = mTransactionPool.acquire();
            tx.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());
            mTmpTransform.postTranslate(mFirstWindowFrame.left,
                    mFirstWindowFrame.top + mMainWindowShiftLength);

            SyncRtSurfaceTransactionApplier.SurfaceParams
                    params = new SyncRtSurfaceTransactionApplier.SurfaceParams
                    .Builder(mFirstWindowSurface)
                    .withMatrix(mTmpTransform)
                    .withMergeTransaction(tx)
                    .build();
            mApplier.scheduleApply(params);

            mTransactionPool.release(tx);
        }

        void finish() {
            if (mFirstWindowSurface == null || !mFirstWindowSurface.isValid()) {
                return;
            }
            final SurfaceControl.Transaction tx = mTransactionPool.acquire();
            tx.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());

            SyncRtSurfaceTransactionApplier.SurfaceParams
                    params = new SyncRtSurfaceTransactionApplier.SurfaceParams
                    .Builder(mFirstWindowSurface)
                    .withWindowCrop(null)
                    .withMergeTransaction(tx)
                    .build();
            mApplier.scheduleApply(params);
            mTransactionPool.release(tx);

            Choreographer.getSfInstance().postCallback(CALLBACK_COMMIT,
                    mFirstWindowSurface::release, null);
        }
    }

    private void reset() {
        if (DEBUG_EXIT_ANIMATION) {
            Slog.v(TAG, "vanish animation finished");
        }
        mSplashScreenView.post(() -> {
            mSplashScreenView.setVisibility(GONE);
            if (mFinishCallback != null) {
                mFinishCallback.run();
                mFinishCallback = null;
            }
        });
        if (mShiftUpAnimation != null) {
            mShiftUpAnimation.finish();
        }
    }

    @Override
    public void onAnimationStart(Animator animation) {
        // ignore
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        reset();
    }

    @Override
    public void onAnimationCancel(Animator animation) {
        reset();
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
        // ignore
    }
}
