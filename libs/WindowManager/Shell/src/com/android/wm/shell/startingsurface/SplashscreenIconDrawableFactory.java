/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Trace;
import android.util.PathParser;
import android.window.SplashScreenView;

import com.android.internal.R;

/**
 * Creating a lightweight Drawable object used for splash screen.
 * @hide
 */
public class SplashscreenIconDrawableFactory {

    static Drawable makeIconDrawable(@ColorInt int backgroundColor,
            @NonNull Drawable foregroundDrawable, int srcIconSize, int iconSize,
            Handler splashscreenWorkerHandler) {
        if (foregroundDrawable instanceof Animatable) {
            return new AnimatableIconDrawable(backgroundColor, foregroundDrawable);
        } else if (foregroundDrawable instanceof AdaptiveIconDrawable) {
            return new ImmobileIconDrawable((AdaptiveIconDrawable) foregroundDrawable,
                    srcIconSize, iconSize, splashscreenWorkerHandler);
        } else {
            // TODO for legacy icon don't use adaptive icon drawable to wrapper it
            return new ImmobileIconDrawable(new AdaptiveIconDrawable(
                    new ColorDrawable(backgroundColor), foregroundDrawable),
                    srcIconSize, iconSize, splashscreenWorkerHandler);
        }
    }

    private static class ImmobileIconDrawable extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG
                | Paint.FILTER_BITMAP_FLAG);
        private final Matrix mMatrix = new Matrix();
        private Bitmap mIconBitmap;

        ImmobileIconDrawable(AdaptiveIconDrawable drawable, int srcIconSize, int iconSize,
                Handler splashscreenWorkerHandler) {
            final float scale = (float) iconSize / srcIconSize;
            mMatrix.setScale(scale, scale);
            splashscreenWorkerHandler.post(() -> preDrawIcon(drawable, srcIconSize));
        }

        private void preDrawIcon(AdaptiveIconDrawable drawable, int size) {
            synchronized (mPaint) {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "preDrawIcon");
                mIconBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(mIconBitmap);
                drawable.setBounds(0, 0, size, size);
                drawable.draw(canvas);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            synchronized (mPaint) {
                if (mIconBitmap != null) {
                    canvas.drawBitmap(mIconBitmap, mMatrix, mPaint);
                } else {
                    // this shouldn't happen, but if it really happen, invalidate self to wait
                    // for bitmap to be ready.
                    invalidateSelf();
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return 1;
        }
    }

    /**
     * A lightweight AdaptiveIconDrawable which support foreground to be Animatable, and keep this
     * drawable masked by config_icon_mask.
     * @hide
     */
    private static class AnimatableIconDrawable extends SplashScreenView.SplashscreenIconDrawable {
        private static final float MASK_SIZE = AdaptiveIconDrawable.MASK_SIZE;
        private static final float EXTRA_INSET_PERCENTAGE = 1 / 4f;
        private static final float DEFAULT_VIEW_PORT_SCALE = 1f / (1 + 2 * EXTRA_INSET_PERCENTAGE);
        private final Rect mTmpOutRect = new Rect();
        /**
         * Clip path defined in R.string.config_icon_mask.
         */
        private static Path sMask;

        /**
         * Scaled mask based on the view bounds.
         */
        private final Path mMask;
        private final Path mMaskScaleOnly;
        private final Matrix mMaskMatrix;
        private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable mForegroundDrawable;
        private Animatable mAnimatableIcon;
        private Animator mIconAnimator;
        private boolean mAnimationTriggered;

        AnimatableIconDrawable(@ColorInt int backgroundColor, Drawable foregroundDrawable) {
            mForegroundDrawable = foregroundDrawable;
            final Resources r = Resources.getSystem();
            sMask = PathParser.createPathFromPathData(r.getString(R.string.config_icon_mask));
            mMask = new Path(sMask);
            mMaskScaleOnly = new Path(mMask);
            mMaskMatrix = new Matrix();
            mPaint.setColor(backgroundColor);
            mPaint.setStyle(Paint.Style.FILL);
            if (mForegroundDrawable != null) {
                mForegroundDrawable.setCallback(mCallback);
            }
        }

        @Override
        protected boolean prepareAnimate(long duration, Runnable startListener) {
            mAnimatableIcon = (Animatable) mForegroundDrawable;
            mIconAnimator = ValueAnimator.ofInt(0, 1);
            mIconAnimator.setDuration(duration);
            mIconAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (startListener != null) {
                        startListener.run();
                    }
                    mAnimatableIcon.start();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mAnimatableIcon.stop();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mAnimatableIcon.stop();
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                    // do not repeat
                    mAnimatableIcon.stop();
                }
            });
            return true;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (bounds.isEmpty()) {
                return;
            }
            updateLayerBounds(bounds);
        }

        private final Callback mCallback = new Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                invalidateSelf();
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                scheduleSelf(what, when);
            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
                unscheduleSelf(what);
            }
        };

        private void updateLayerBounds(Rect bounds) {
            int cX = bounds.width() / 2;
            int cY = bounds.height() / 2;

            int insetWidth = (int) (bounds.width() / (DEFAULT_VIEW_PORT_SCALE * 2));
            int insetHeight = (int) (bounds.height() / (DEFAULT_VIEW_PORT_SCALE * 2));
            final Rect outRect = mTmpOutRect;
            outRect.set(cX - insetWidth, cY - insetHeight, cX + insetWidth, cY + insetHeight);

            if (mForegroundDrawable != null) {
                mForegroundDrawable.setBounds(outRect);
            }
            // reset everything that depends on the view bounds
            mMaskMatrix.setScale(bounds.width() / MASK_SIZE, bounds.height() / MASK_SIZE);
            sMask.transform(mMaskMatrix, mMaskScaleOnly);
            invalidateSelf();
        }

        private void ensureAnimationStarted() {
            if (mAnimationTriggered) {
                return;
            }
            if (mIconAnimator != null && !mIconAnimator.isRunning()) {
                mIconAnimator.start();
            }
            mAnimationTriggered = true;
        }

        @Override
        public void draw(Canvas canvas) {
            if (mMaskScaleOnly != null) {
                canvas.drawPath(mMaskScaleOnly, mPaint);
            }
            if (mForegroundDrawable != null) {
                ensureAnimationStarted();
                mForegroundDrawable.draw(canvas);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            if (mForegroundDrawable != null) {
                mForegroundDrawable.setColorFilter(colorFilter);
            }
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

}
