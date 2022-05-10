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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_SPLASHSCREEN_EXIT_ANIM;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.window.SplashScreenView;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.TransactionPool;

/**
 * Default animation for exiting the splash screen window.
 * @hide
 */
public class SplashScreenExitAnimation implements Animator.AnimatorListener {
    private static final boolean DEBUG_EXIT_ANIMATION = false;
    private static final boolean DEBUG_EXIT_ANIMATION_BLEND = false;
    private static final String TAG = StartingWindowController.TAG;

    private static final Interpolator ICON_INTERPOLATOR = new PathInterpolator(0.15f, 0f, 1f, 1f);
    private static final Interpolator MASK_RADIUS_INTERPOLATOR =
            new PathInterpolator(0f, 0f, 0.4f, 1f);
    private static final Interpolator SHIFT_UP_INTERPOLATOR = new PathInterpolator(0f, 0f, 0f, 1f);

    private final SurfaceControl mFirstWindowSurface;
    private final Rect mFirstWindowFrame = new Rect();
    private final SplashScreenView mSplashScreenView;
    private final int mMainWindowShiftLength;
    private final int mIconFadeOutDuration;
    private final int mAppRevealDelay;
    private final int mAppRevealDuration;
    private final int mAnimationDuration;
    private final float mIconStartAlpha;
    private final float mBrandingStartAlpha;
    private final TransactionPool mTransactionPool;

    private ValueAnimator mMainAnimator;
    private ShiftUpAnimation mShiftUpAnimation;
    private RadialVanishAnimation mRadialVanishAnimation;
    private Runnable mFinishCallback;

    SplashScreenExitAnimation(Context context, SplashScreenView view, SurfaceControl leash,
            Rect frame, int mainWindowShiftLength, TransactionPool pool, Runnable handleFinish) {
        mSplashScreenView = view;
        mFirstWindowSurface = leash;
        if (frame != null) {
            mFirstWindowFrame.set(frame);
        }

        View iconView = view.getIconView();

        // If the icon and the background are invisible, don't animate it
        if (iconView == null || iconView.getLayoutParams().width == 0
                || iconView.getLayoutParams().height == 0) {
            mIconFadeOutDuration = 0;
            mIconStartAlpha = 0;
            mBrandingStartAlpha = 0;
            mAppRevealDelay = 0;
        } else {
            iconView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            // The branding view could only exists when the icon is present.
            final View brandingView = view.getBrandingView();
            if (brandingView != null) {
                mBrandingStartAlpha = brandingView.getAlpha();
            } else {
                mBrandingStartAlpha = 0;
            }
            mIconFadeOutDuration = context.getResources().getInteger(
                    R.integer.starting_window_app_reveal_icon_fade_out_duration);
            mAppRevealDelay = context.getResources().getInteger(
                    R.integer.starting_window_app_reveal_anim_delay);
            mIconStartAlpha = iconView.getAlpha();
        }
        mAppRevealDuration = context.getResources().getInteger(
                R.integer.starting_window_app_reveal_anim_duration);
        mAnimationDuration = Math.max(mIconFadeOutDuration, mAppRevealDelay + mAppRevealDuration);
        mMainWindowShiftLength = mainWindowShiftLength;
        mFinishCallback = handleFinish;
        mTransactionPool = pool;
    }

    void startAnimations() {
        mMainAnimator = createAnimator();
        mMainAnimator.start();
    }

    // fade out icon, reveal app, shift up main window
    private ValueAnimator createAnimator() {
        // reveal app
        final float transparentRatio = 0.8f;
        final int globalHeight = mSplashScreenView.getHeight();
        final int verticalCircleCenter = 0;
        final int finalVerticalLength = globalHeight - verticalCircleCenter;
        final int halfWidth = mSplashScreenView.getWidth() / 2;
        final int endRadius = (int) (0.5 + (1f / transparentRatio * (int)
                Math.sqrt(finalVerticalLength * finalVerticalLength + halfWidth * halfWidth)));
        final int[] colors = {Color.WHITE, Color.WHITE, Color.TRANSPARENT};
        final float[] stops = {0f, transparentRatio, 1f};

        mRadialVanishAnimation = new RadialVanishAnimation(mSplashScreenView);
        mRadialVanishAnimation.setCircleCenter(halfWidth, verticalCircleCenter);
        mRadialVanishAnimation.setRadius(0 /* initRadius */, endRadius);
        mRadialVanishAnimation.setRadialPaintParam(colors, stops);

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
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(mAnimationDuration);
        animator.setInterpolator(Interpolators.LINEAR);
        animator.addListener(this);
        animator.addUpdateListener(a -> onAnimationProgress((float) a.getAnimatedValue()));
        return animator;
    }

    private static class RadialVanishAnimation extends View {
        private final SplashScreenView mView;
        private int mInitRadius;
        private int mFinishRadius;

        private final Point mCircleCenter = new Point();
        private final Matrix mVanishMatrix = new Matrix();
        private final Paint mVanishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RadialVanishAnimation(SplashScreenView target) {
            super(target.getContext());
            mView = target;
            mView.addView(this);
            mVanishPaint.setAlpha(0);
        }

        void onAnimationProgress(float linearProgress) {
            if (mVanishPaint.getShader() == null) {
                return;
            }

            final float radiusProgress = MASK_RADIUS_INTERPOLATOR.getInterpolation(linearProgress);
            final float alphaProgress = Interpolators.ALPHA_OUT.getInterpolation(linearProgress);
            final float scale = mInitRadius + (mFinishRadius - mInitRadius) * radiusProgress;

            mVanishMatrix.setScale(scale, scale);
            mVanishMatrix.postTranslate(mCircleCenter.x, mCircleCenter.y);
            mVanishPaint.getShader().setLocalMatrix(mVanishMatrix);
            mVanishPaint.setAlpha(Math.round(0xFF * alphaProgress));

            postInvalidate();
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
                // We blend the reveal gradient with the splash screen using DST_OUT so that the
                // splash screen is fully visible when radius = 0 (or gradient opacity is 0) and
                // fully invisible when radius = finishRadius AND gradient opacity is 1.
                mVanishPaint.setBlendMode(BlendMode.DST_OUT);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, mView.getWidth(), mView.getHeight(), mVanishPaint);
        }
    }

    private final class ShiftUpAnimation {
        private final float mFromYDelta;
        private final float mToYDelta;
        private final View mOccludeHoleView;
        private final SyncRtSurfaceTransactionApplier mApplier;
        private final Matrix mTmpTransform = new Matrix();

        ShiftUpAnimation(float fromYDelta, float toYDelta, View occludeHoleView) {
            mFromYDelta = fromYDelta;
            mToYDelta = toYDelta;
            mOccludeHoleView = occludeHoleView;
            mApplier = new SyncRtSurfaceTransactionApplier(occludeHoleView);
        }

        void onAnimationProgress(float linearProgress) {
            if (mFirstWindowSurface == null || !mFirstWindowSurface.isValid()
                    || !mSplashScreenView.isAttachedToWindow()) {
                return;
            }

            final float progress = SHIFT_UP_INTERPOLATOR.getInterpolation(linearProgress);
            final float dy = mFromYDelta + (mToYDelta - mFromYDelta) * progress;

            mOccludeHoleView.setTranslationY(dy);
            mTmpTransform.setTranslate(0 /* dx */, dy);

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
            if (mSplashScreenView.isAttachedToWindow()) {
                tx.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());

                SyncRtSurfaceTransactionApplier.SurfaceParams
                        params = new SyncRtSurfaceTransactionApplier.SurfaceParams
                        .Builder(mFirstWindowSurface)
                        .withWindowCrop(null)
                        .withMergeTransaction(tx)
                        .build();
                mApplier.scheduleApply(params);
            } else {
                tx.setWindowCrop(mFirstWindowSurface, null);
                tx.apply();
            }
            mTransactionPool.release(tx);

            Choreographer.getSfInstance().postCallback(CALLBACK_COMMIT,
                    mFirstWindowSurface::release, null);
        }
    }

    private void reset() {
        if (DEBUG_EXIT_ANIMATION) {
            Slog.v(TAG, "vanish animation finished");
        }

        if (mSplashScreenView.isAttachedToWindow()) {
            mSplashScreenView.setVisibility(GONE);
            if (mFinishCallback != null) {
                mFinishCallback.run();
                mFinishCallback = null;
            }
        }
        if (mShiftUpAnimation != null) {
            mShiftUpAnimation.finish();
        }
    }

    @Override
    public void onAnimationStart(Animator animation) {
        InteractionJankMonitor.getInstance().begin(mSplashScreenView, CUJ_SPLASHSCREEN_EXIT_ANIM);
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        reset();
        InteractionJankMonitor.getInstance().end(CUJ_SPLASHSCREEN_EXIT_ANIM);
    }

    @Override
    public void onAnimationCancel(Animator animation) {
        reset();
        InteractionJankMonitor.getInstance().cancel(CUJ_SPLASHSCREEN_EXIT_ANIM);
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
        // ignore
    }

    private void onFadeOutProgress(float linearProgress) {
        final float iconProgress = ICON_INTERPOLATOR.getInterpolation(
                getProgress(linearProgress, 0 /* delay */, mIconFadeOutDuration));
        final View iconView = mSplashScreenView.getIconView();
        final View brandingView = mSplashScreenView.getBrandingView();
        if (iconView != null) {
            iconView.setAlpha(mIconStartAlpha * (1 - iconProgress));
        }
        if (brandingView != null) {
            brandingView.setAlpha(mBrandingStartAlpha * (1 - iconProgress));
        }
    }

    private void onAnimationProgress(float linearProgress) {
        onFadeOutProgress(linearProgress);

        final float revealLinearProgress = getProgress(linearProgress, mAppRevealDelay,
                mAppRevealDuration);

        if (mRadialVanishAnimation != null) {
            mRadialVanishAnimation.onAnimationProgress(revealLinearProgress);
        }

        if (mShiftUpAnimation != null) {
            mShiftUpAnimation.onAnimationProgress(revealLinearProgress);
        }
    }

    private float getProgress(float linearProgress, long delay, long duration) {
        return MathUtils.constrain(
                (linearProgress * (mAnimationDuration) - delay) / duration,
                0.0f,
                1.0f
        );
    }
}
