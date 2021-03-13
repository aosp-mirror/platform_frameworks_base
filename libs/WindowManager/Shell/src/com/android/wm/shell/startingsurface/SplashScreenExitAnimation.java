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
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
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

    private static final Interpolator ICON_EXIT_INTERPOLATOR = new PathInterpolator(1f, 0f, 1f, 1f);
    private static final Interpolator APP_EXIT_INTERPOLATOR = new PathInterpolator(0f, 0f, 0f, 1f);

    private static final int EXTRA_REVEAL_DELAY = 133;
    private final Matrix mTmpTransform = new Matrix();
    private final float[] mTmpFloat9 = new float[9];
    private SurfaceControl mFirstWindowSurface;
    private final Rect mFirstWindowFrame = new Rect();
    private final SplashScreenView mSplashScreenView;
    private final int mMainWindowShiftLength;
    private final int mIconShiftLength;
    private final int mAppDuration;
    private final int mIconDuration;
    private final TransactionPool mTransactionPool;

    private ValueAnimator mMainAnimator;
    private Animation mShiftUpAnimation;
    private AnimationSet mIconAnimationSet;
    private Runnable mFinishCallback;

    SplashScreenExitAnimation(SplashScreenView view, SurfaceControl leash, Rect frame,
            int appDuration, int iconDuration, int mainWindowShiftLength, int iconShiftLength,
            TransactionPool pool, Runnable handleFinish) {
        mSplashScreenView = view;
        mFirstWindowSurface = leash;
        if (frame != null) {
            mFirstWindowFrame.set(frame);
        }
        mAppDuration = appDuration;
        mIconDuration = iconDuration;
        mMainWindowShiftLength = mainWindowShiftLength;
        mIconShiftLength = iconShiftLength;
        mFinishCallback = handleFinish;
        mTransactionPool = pool;
    }

    void prepareAnimations() {
        prepareRevealAnimation();
        prepareShiftAnimation();
    }

    void startAnimations() {
        if (mIconAnimationSet != null) {
            mIconAnimationSet.start();
        }
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

        final int startDelay = mIconDuration + EXTRA_REVEAL_DELAY;
        final float transparentRatio = 0.95f;
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
        mMainAnimator.setStartDelay(startDelay);

        if (mFirstWindowSurface != null) {
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

            mShiftUpAnimation = new ShiftUpAnimation(0, -mMainWindowShiftLength);
            mShiftUpAnimation.setDuration(mAppDuration);
            mShiftUpAnimation.setInterpolator(APP_EXIT_INTERPOLATOR);
            mShiftUpAnimation.setStartOffset(startDelay);

            occludeHoleView.setAnimation(mShiftUpAnimation);
        }
    }

    // shift down icon and branding view
    private void prepareShiftAnimation() {
        final View iconView = mSplashScreenView.getIconView();
        if (iconView == null) {
            return;
        }
        if (mIconShiftLength > 0) {
            mIconAnimationSet = new AnimationSet(true /* shareInterpolator */);
            if (DEBUG_EXIT_ANIMATION) {
                Slog.v(TAG, "first exit animation, shift length: " + mIconShiftLength);
            }
            mIconAnimationSet.addAnimation(new TranslateYAnimation(0, mIconShiftLength));
            mIconAnimationSet.addAnimation(new AlphaAnimation(1, 0));
            mIconAnimationSet.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {

                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    if (DEBUG_EXIT_ANIMATION) {
                        Slog.v(TAG, "first exit animation finished");
                    }
                    iconView.post(() -> iconView.setVisibility(GONE));
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                    // ignore
                }
            });
            mIconAnimationSet.setDuration(mIconDuration);
            mIconAnimationSet.setInterpolator(ICON_EXIT_INTERPOLATOR);
            iconView.setAnimation(mIconAnimationSet);
            final View brandingView = mSplashScreenView.getBrandingView();
            if (brandingView != null) {
                brandingView.setAnimation(mIconAnimationSet);
            }
        }
    }

    private static class RadialVanishAnimation extends View {
        private SplashScreenView mView;
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
                mView.postInvalidate();
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
        ShiftUpAnimation(float fromYDelta, float toYDelta) {
            super(fromYDelta, toYDelta);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            super.applyTransformation(interpolatedTime, t);

            if (mFirstWindowSurface == null) {
                return;
            }
            mTmpTransform.set(t.getMatrix());
            final SurfaceControl.Transaction tx = mTransactionPool.acquire();
            mTmpTransform.postTranslate(mFirstWindowFrame.left,
                    mFirstWindowFrame.top + mMainWindowShiftLength);
            tx.setMatrix(mFirstWindowSurface, mTmpTransform, mTmpFloat9);
            // TODO set the vsyncId to ensure the transaction doesn't get applied too early.
            //  Additionally, do you want to have this synchronized with your view animations?
            //  If so, you'll need to use SyncRtSurfaceTransactionApplier
            tx.apply();
            mTransactionPool.release(tx);
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
        if (mFirstWindowSurface != null) {
            final SurfaceControl.Transaction tx = mTransactionPool.acquire();
            tx.setWindowCrop(mFirstWindowSurface, null);
            tx.apply();
            mFirstWindowSurface.release();
            mFirstWindowSurface = null;
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
