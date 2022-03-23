/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.ColorInt;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.FloatProperty;

public class SlashDrawable extends Drawable {

    public static final float CORNER_RADIUS = 1f;

    private final Path mPath = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // These values are derived in un-rotated (vertical) orientation
    private static final float SLASH_WIDTH = 1.8384776f;
    private static final float SLASH_HEIGHT = 28f;
    private static final float CENTER_X = 10.65f;
    private static final float CENTER_Y = 11.869239f;
    private static final float SCALE = 24f;

    // Bottom is derived during animation
    private static final float LEFT = (CENTER_X - (SLASH_WIDTH / 2)) / SCALE;
    private static final float TOP = (CENTER_Y - (SLASH_HEIGHT / 2)) / SCALE;
    private static final float RIGHT = (CENTER_X + (SLASH_WIDTH / 2)) / SCALE;
    // Draw the slash washington-monument style; rotate to no-u-turn style
    private static final float DEFAULT_ROTATION = -45f;

    private Drawable mDrawable;
    private final RectF mSlashRect = new RectF(0, 0, 0, 0);
    private float mRotation;
    private boolean mSlashed;
    private Mode mTintMode;
    private ColorStateList mTintList;
    private boolean mAnimationEnabled = true;

    public SlashDrawable(Drawable d) {
        mDrawable = d;
    }

    @Override
    public int getIntrinsicHeight() {
        return mDrawable != null ? mDrawable.getIntrinsicHeight(): 0;
    }

    @Override
    public int getIntrinsicWidth() {
        return mDrawable != null ? mDrawable.getIntrinsicWidth(): 0;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mDrawable.setBounds(bounds);
    }

    public void setDrawable(Drawable d) {
        mDrawable = d;
        mDrawable.setCallback(getCallback());
        mDrawable.setBounds(getBounds());
        if (mTintMode != null) mDrawable.setTintMode(mTintMode);
        if (mTintList != null) mDrawable.setTintList(mTintList);
        invalidateSelf();
    }

    public void setRotation(float rotation) {
        if (mRotation == rotation) return;
        mRotation = rotation;
        invalidateSelf();
    }

    public void setAnimationEnabled(boolean enabled) {
        mAnimationEnabled = enabled;
    }

    // Animate this value on change
    private float mCurrentSlashLength;
    private final FloatProperty mSlashLengthProp = new FloatProperty<SlashDrawable>("slashLength") {
        @Override
        public void setValue(SlashDrawable object, float value) {
            object.mCurrentSlashLength = value;
        }

        @Override
        public Float get(SlashDrawable object) {
            return object.mCurrentSlashLength;
        }
    };

    public void setSlashed(boolean slashed) {
        if (mSlashed == slashed) return;

        mSlashed = slashed;

        final float end = mSlashed ? SLASH_HEIGHT / SCALE : 0f;
        final float start = mSlashed ? 0f : SLASH_HEIGHT / SCALE;

        if (mAnimationEnabled) {
            ObjectAnimator anim = ObjectAnimator.ofFloat(this, mSlashLengthProp, start, end);
            anim.addUpdateListener((ValueAnimator valueAnimator) -> invalidateSelf());
            anim.setDuration(QS_ANIM_LENGTH);
            anim.start();
        } else {
            mCurrentSlashLength = end;
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        Matrix m = new Matrix();
        final int width = getBounds().width();
        final int height = getBounds().height();
        final float radiusX = scale(CORNER_RADIUS, width);
        final float radiusY = scale(CORNER_RADIUS, height);
        updateRect(
                scale(LEFT, width),
                scale(TOP, height),
                scale(RIGHT, width),
                scale(TOP + mCurrentSlashLength, height)
        );

        mPath.reset();
        // Draw the slash vertically
        mPath.addRoundRect(mSlashRect, radiusX, radiusY, Direction.CW);
        // Rotate -45 + desired rotation
        m.setRotate(mRotation + DEFAULT_ROTATION, width / 2, height / 2);
        mPath.transform(m);
        canvas.drawPath(mPath, mPaint);

        // Rotate back to vertical
        m.setRotate(-mRotation - DEFAULT_ROTATION, width / 2, height / 2);
        mPath.transform(m);

        // Draw another rect right next to the first, for clipping
        m.setTranslate(mSlashRect.width(), 0);
        mPath.transform(m);
        mPath.addRoundRect(mSlashRect, 1.0f * width, 1.0f * height, Direction.CW);
        m.setRotate(mRotation + DEFAULT_ROTATION, width / 2, height / 2);
        mPath.transform(m);
        canvas.clipOutPath(mPath);

        mDrawable.draw(canvas);
        canvas.restore();
    }

    private float scale(float frac, int width) {
        return frac * width;
    }

    private void updateRect(float left, float top, float right, float bottom) {
        mSlashRect.left = left;
        mSlashRect.top = top;
        mSlashRect.right = right;
        mSlashRect.bottom = bottom;
    }

    @Override
    public void setTint(@ColorInt int tintColor) {
        super.setTint(tintColor);
        mDrawable.setTint(tintColor);
        mPaint.setColor(tintColor);
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mTintList = tint;
        super.setTintList(tint);
        setDrawableTintList(tint);
        mPaint.setColor(tint.getDefaultColor());
        invalidateSelf();
    }

    protected void setDrawableTintList(@Nullable ColorStateList tint) {
        mDrawable.setTintList(tint);
    }

    @Override
    public void setTintMode(@NonNull Mode tintMode) {
        mTintMode = tintMode;
        super.setTintMode(tintMode);
        mDrawable.setTintMode(tintMode);
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        mDrawable.setAlpha(alpha);
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mDrawable.setColorFilter(colorFilter);
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 255;
    }
}
