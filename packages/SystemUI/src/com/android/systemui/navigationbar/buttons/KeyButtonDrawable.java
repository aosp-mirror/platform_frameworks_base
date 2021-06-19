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

package com.android.systemui.navigationbar.buttons;

import android.animation.ArgbEvaluator;
import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BlurMaskFilter.Blur;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.FloatProperty;
import android.view.View;

import com.android.settingslib.Utils;
import com.android.systemui.R;

/**
 * Drawable for {@link KeyButtonView}s that supports tinting between two colors, rotation and shows
 * a shadow. AnimatedVectorDrawable will only support tinting from intensities but has no support
 * for shadows nor rotations.
 */
public class KeyButtonDrawable extends Drawable {

    public static final FloatProperty<KeyButtonDrawable> KEY_DRAWABLE_ROTATE =
        new FloatProperty<KeyButtonDrawable>("KeyButtonRotation") {
            @Override
            public void setValue(KeyButtonDrawable drawable, float degree) {
                drawable.setRotation(degree);
            }

            @Override
            public Float get(KeyButtonDrawable drawable) {
                return drawable.getRotation();
            }
        };

    public static final FloatProperty<KeyButtonDrawable> KEY_DRAWABLE_TRANSLATE_Y =
        new FloatProperty<KeyButtonDrawable>("KeyButtonTranslateY") {
            @Override
            public void setValue(KeyButtonDrawable drawable, float y) {
                drawable.setTranslationY(y);
            }

            @Override
            public Float get(KeyButtonDrawable drawable) {
                return drawable.getTranslationY();
            }
        };

    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ShadowDrawableState mState;
    private AnimatedVectorDrawable mAnimatedDrawable;
    private final Callback mAnimatedDrawableCallback = new Callback() {
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

    public KeyButtonDrawable(Drawable d, @ColorInt int lightColor, @ColorInt int darkColor,
            boolean horizontalFlip, Color ovalBackgroundColor) {
        this(d, new ShadowDrawableState(lightColor, darkColor,
                d instanceof AnimatedVectorDrawable, horizontalFlip, ovalBackgroundColor));
    }

    private KeyButtonDrawable(Drawable d, ShadowDrawableState state) {
        mState = state;
        if (d != null) {
            mState.mBaseHeight = d.getIntrinsicHeight();
            mState.mBaseWidth = d.getIntrinsicWidth();
            mState.mChangingConfigurations = d.getChangingConfigurations();
            mState.mChildState = d.getConstantState();
        }
        if (canAnimate()) {
            mAnimatedDrawable = (AnimatedVectorDrawable) mState.mChildState.newDrawable().mutate();
            mAnimatedDrawable.setCallback(mAnimatedDrawableCallback);
            setDrawableBounds(mAnimatedDrawable);
        }
    }

    public void setDarkIntensity(float intensity) {
        mState.mDarkIntensity = intensity;
        final int color = (int) ArgbEvaluator.getInstance()
                .evaluate(intensity, mState.mLightColor, mState.mDarkColor);
        updateShadowAlpha();
        setColorFilter(new PorterDuffColorFilter(color, Mode.SRC_ATOP));
    }

    public void setRotation(float degrees) {
        if (canAnimate()) {
            // AnimatedVectorDrawables will not support rotation
            return;
        }
        if (mState.mRotateDegrees != degrees) {
            mState.mRotateDegrees = degrees;
            invalidateSelf();
        }
    }

    public void setTranslationX(float x) {
        setTranslation(x, mState.mTranslationY);
    }

    public void setTranslationY(float y) {
        setTranslation(mState.mTranslationX, y);
    }

    public void setTranslation(float x, float y) {
        if (mState.mTranslationX != x || mState.mTranslationY != y) {
            mState.mTranslationX = x;
            mState.mTranslationY = y;
            invalidateSelf();
        }
    }

    public void setShadowProperties(int x, int y, int size, int color) {
        if (canAnimate()) {
            // AnimatedVectorDrawables will not support shadows
            return;
        }
        if (mState.mShadowOffsetX != x || mState.mShadowOffsetY != y
                || mState.mShadowSize != size || mState.mShadowColor != color) {
            mState.mShadowOffsetX = x;
            mState.mShadowOffsetY = y;
            mState.mShadowSize = size;
            mState.mShadowColor = color;
            mShadowPaint.setColorFilter(
                    new PorterDuffColorFilter(mState.mShadowColor, Mode.SRC_ATOP));
            updateShadowAlpha();
            invalidateSelf();
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (changed) {
            // End any existing animations when the visibility changes
            jumpToCurrentState();
        }
        return changed;
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.jumpToCurrentState();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mState.mAlpha = alpha;
        mIconPaint.setAlpha(alpha);
        updateShadowAlpha();
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mIconPaint.setColorFilter(colorFilter);
        if (mAnimatedDrawable != null) {
            if (hasOvalBg()) {
                mAnimatedDrawable.setColorFilter(
                        new PorterDuffColorFilter(mState.mLightColor, PorterDuff.Mode.SRC_IN));
            } else {
                mAnimatedDrawable.setColorFilter(colorFilter);
            }
        }
        invalidateSelf();
    }

    public float getDarkIntensity() {
        return mState.mDarkIntensity;
    }

    public float getRotation() {
        return mState.mRotateDegrees;
    }

    public float getTranslationX() {
        return mState.mTranslationX;
    }

    public float getTranslationY() {
        return mState.mTranslationY;
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicHeight() {
        return mState.mBaseHeight + (mState.mShadowSize + Math.abs(mState.mShadowOffsetY)) * 2;
    }

    @Override
    public int getIntrinsicWidth() {
        return mState.mBaseWidth + (mState.mShadowSize + Math.abs(mState.mShadowOffsetX)) * 2;
    }

    public boolean canAnimate() {
        return mState.mSupportsAnimation;
    }

    public void startAnimation() {
        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.start();
        }
    }

    public void resetAnimation() {
        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.reset();
        }
    }

    public void clearAnimationCallbacks() {
        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.clearAnimationCallbacks();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }

        if (mAnimatedDrawable != null) {
            mAnimatedDrawable.draw(canvas);
        } else {
            // If no cache or previous cached bitmap is hardware/software acceleration does not
            // match the current canvas on draw then regenerate
            boolean hwBitmapChanged = mState.mIsHardwareBitmap != canvas.isHardwareAccelerated();
            if (hwBitmapChanged) {
                mState.mIsHardwareBitmap = canvas.isHardwareAccelerated();
            }
            if (mState.mLastDrawnIcon == null || hwBitmapChanged) {
                regenerateBitmapIconCache();
            }
            canvas.save();
            canvas.translate(mState.mTranslationX, mState.mTranslationY);
            canvas.rotate(mState.mRotateDegrees, getIntrinsicWidth() / 2, getIntrinsicHeight() / 2);

            if (mState.mShadowSize > 0) {
                if (mState.mLastDrawnShadow == null || hwBitmapChanged) {
                    regenerateBitmapShadowCache();
                }

                // Translate (with rotation offset) before drawing the shadow
                final float radians = (float) (mState.mRotateDegrees * Math.PI / 180);
                final float shadowOffsetX = (float) (Math.sin(radians) * mState.mShadowOffsetY
                        + Math.cos(radians) * mState.mShadowOffsetX) - mState.mTranslationX;
                final float shadowOffsetY = (float) (Math.cos(radians) * mState.mShadowOffsetY
                        - Math.sin(radians) * mState.mShadowOffsetX) - mState.mTranslationY;
                canvas.drawBitmap(mState.mLastDrawnShadow, shadowOffsetX, shadowOffsetY,
                        mShadowPaint);
            }
            canvas.drawBitmap(mState.mLastDrawnIcon, null, bounds, mIconPaint);
            canvas.restore();
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mState.canApplyTheme();
    }

    @ColorInt int getDrawableBackgroundColor() {
        return mState.mOvalBackgroundColor.toArgb();
    }

    boolean hasOvalBg() {
        return mState.mOvalBackgroundColor != null;
    }

    private void regenerateBitmapIconCache() {
        final int width = getIntrinsicWidth();
        final int height = getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        // Call mutate, so that the pixel allocation by the underlying vector drawable is cleared.
        final Drawable d = mState.mChildState.newDrawable().mutate();
        setDrawableBounds(d);
        canvas.save();
        if (mState.mHorizontalFlip) {
            canvas.scale(-1f, 1f, width * 0.5f, height * 0.5f);
        }
        d.draw(canvas);
        canvas.restore();

        if (mState.mIsHardwareBitmap) {
            bitmap = bitmap.copy(Bitmap.Config.HARDWARE, false);
        }
        mState.mLastDrawnIcon = bitmap;
    }

    private void regenerateBitmapShadowCache() {
        if (mState.mShadowSize == 0) {
            // No shadow
            mState.mLastDrawnIcon = null;
            return;
        }

        final int width = getIntrinsicWidth();
        final int height = getIntrinsicHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Call mutate, so that the pixel allocation by the underlying vector drawable is cleared.
        final Drawable d = mState.mChildState.newDrawable().mutate();
        setDrawableBounds(d);
        canvas.save();
        if (mState.mHorizontalFlip) {
            canvas.scale(-1f, 1f, width * 0.5f, height * 0.5f);
        }
        d.draw(canvas);
        canvas.restore();

        // Draws the shadow from original drawable
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setMaskFilter(new BlurMaskFilter(mState.mShadowSize, Blur.NORMAL));
        int[] offset = new int[2];
        final Bitmap shadow = bitmap.extractAlpha(paint, offset);
        paint.setMaskFilter(null);
        bitmap.eraseColor(Color.TRANSPARENT);
        canvas.drawBitmap(shadow, offset[0], offset[1], paint);

        if (mState.mIsHardwareBitmap) {
            bitmap = bitmap.copy(Bitmap.Config.HARDWARE, false);
        }
        mState.mLastDrawnShadow = bitmap;
    }

    /**
     * Set the alpha of the shadow. As dark intensity increases, drop the alpha of the shadow since
     * dark color and shadow should not be visible at the same time.
     */
    private void updateShadowAlpha() {
        // Update the color from the original color's alpha as the max
        int alpha = Color.alpha(mState.mShadowColor);
        mShadowPaint.setAlpha(
                Math.round(alpha * (mState.mAlpha / 255f) * (1 - mState.mDarkIntensity)));
    }

    /**
     * Prevent shadow clipping by offsetting the drawable bounds by the shadow and its offset
     * @param d the drawable to set the bounds
     */
    private void setDrawableBounds(Drawable d) {
        final int offsetX = mState.mShadowSize + Math.abs(mState.mShadowOffsetX);
        final int offsetY = mState.mShadowSize + Math.abs(mState.mShadowOffsetY);
        d.setBounds(offsetX, offsetY, getIntrinsicWidth() - offsetX,
                getIntrinsicHeight() - offsetY);
    }

    private static class ShadowDrawableState extends ConstantState {
        int mChangingConfigurations;
        int mBaseWidth;
        int mBaseHeight;
        float mRotateDegrees;
        float mTranslationX;
        float mTranslationY;
        int mShadowOffsetX;
        int mShadowOffsetY;
        int mShadowSize;
        int mShadowColor;
        float mDarkIntensity;
        int mAlpha;
        boolean mHorizontalFlip;

        boolean mIsHardwareBitmap;
        Bitmap mLastDrawnIcon;
        Bitmap mLastDrawnShadow;
        ConstantState mChildState;

        final int mLightColor;
        final int mDarkColor;
        final boolean mSupportsAnimation;
        final Color mOvalBackgroundColor;

        public ShadowDrawableState(@ColorInt int lightColor, @ColorInt int darkColor,
                boolean animated, boolean horizontalFlip, Color ovalBackgroundColor) {
            mLightColor = lightColor;
            mDarkColor = darkColor;
            mSupportsAnimation = animated;
            mAlpha = 255;
            mHorizontalFlip = horizontalFlip;
            mOvalBackgroundColor = ovalBackgroundColor;
        }

        @Override
        public Drawable newDrawable() {
            return new KeyButtonDrawable(null, this);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        @Override
        public boolean canApplyTheme() {
            return true;
        }
    }

    /**
     * Creates a KeyButtonDrawable with a shadow given its icon. For more information, see
     * {@link #create(Context, int, boolean, boolean)}.
     */
    public static KeyButtonDrawable create(Context lightContext, Context darkContext,
            @DrawableRes int iconResId, boolean hasShadow, Color ovalBackgroundColor) {
        return create(lightContext,
            Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor),
            Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor),
            iconResId, hasShadow, ovalBackgroundColor);
    }

    /**
     * Creates a KeyButtonDrawable with a shadow given its icon. For more information, see
     * {@link #create(Context, int, boolean, boolean)}.
     */
    public static KeyButtonDrawable create(Context context, @ColorInt int lightColor,
            @ColorInt int darkColor, @DrawableRes int iconResId, boolean hasShadow,
            Color ovalBackgroundColor) {
        final Resources res = context.getResources();
        boolean isRtl = res.getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        Drawable d = context.getDrawable(iconResId);
        final KeyButtonDrawable drawable = new KeyButtonDrawable(d, lightColor, darkColor,
                isRtl && d.isAutoMirrored(), ovalBackgroundColor);
        if (hasShadow) {
            int offsetX = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_offset_x);
            int offsetY = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_offset_y);
            int radius = res.getDimensionPixelSize(R.dimen.nav_key_button_shadow_radius);
            int color = context.getColor(R.color.nav_key_button_shadow_color);
            drawable.setShadowProperties(offsetX, offsetY, radius, color);
        }
        return drawable;
    }
}
