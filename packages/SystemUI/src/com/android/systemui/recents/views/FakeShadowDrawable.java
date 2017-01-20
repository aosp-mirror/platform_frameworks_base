/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.recents.views;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;

/**
 * A rounded rectangle drawable which also includes a shadow around. This is mostly copied from
 * frameworks/support/v7/cardview/eclair-mr1/android/support/v7/widget/
 * RoundRectDrawableWithShadow.java revision c42ba8c000d1e6ce85e152dfc17089a0a69e739f with a few
 * modifications to suit our needs in SystemUI.
 */
class FakeShadowDrawable extends Drawable {
    // used to calculate content padding
    final static double COS_45 = Math.cos(Math.toRadians(45));

    final static float SHADOW_MULTIPLIER = 1.5f;

    final float mInsetShadow; // extra shadow to avoid gaps between card and shadow

    Paint mCornerShadowPaint;

    Paint mEdgeShadowPaint;

    final RectF mCardBounds;

    float mCornerRadius;

    Path mCornerShadowPath;

    // updated value with inset
    float mMaxShadowSize;

    // actual value set by developer
    float mRawMaxShadowSize;

    // multiplied value to account for shadow offset
    float mShadowSize;

    // actual value set by developer
    float mRawShadowSize;

    private boolean mDirty = true;

    private final int mShadowStartColor;

    private final int mShadowEndColor;

    private boolean mAddPaddingForCorners = true;

    /**
     * If shadow size is set to a value above max shadow, we print a warning
     */
    private boolean mPrintedShadowClipWarning = false;

    public FakeShadowDrawable(Resources resources, RecentsConfiguration config) {
        mShadowStartColor = resources.getColor(R.color.fake_shadow_start_color);
        mShadowEndColor = resources.getColor(R.color.fake_shadow_end_color);
        mInsetShadow = resources.getDimension(R.dimen.fake_shadow_inset);
        setShadowSize(resources.getDimensionPixelSize(R.dimen.fake_shadow_size),
                resources.getDimensionPixelSize(R.dimen.fake_shadow_size));
        mCornerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        mCornerShadowPaint.setStyle(Paint.Style.FILL);
        mCornerShadowPaint.setDither(true);
        mCornerRadius = Recents.getConfiguration().isGridEnabled ?
                resources.getDimensionPixelSize(
                    R.dimen.recents_grid_task_view_rounded_corners_radius) :
                resources.getDimensionPixelSize(R.dimen.recents_task_view_rounded_corners_radius);
        mCardBounds = new RectF();
        mEdgeShadowPaint = new Paint(mCornerShadowPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mCornerShadowPaint.setAlpha(alpha);
        mEdgeShadowPaint.setAlpha(alpha);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        mDirty = true;
    }

    void setShadowSize(float shadowSize, float maxShadowSize) {
        if (shadowSize < 0 || maxShadowSize < 0) {
            throw new IllegalArgumentException("invalid shadow size");
        }
        if (shadowSize > maxShadowSize) {
            shadowSize = maxShadowSize;
            if (!mPrintedShadowClipWarning) {
                Log.w("CardView", "Shadow size is being clipped by the max shadow size. See "
                        + "{CardView#setMaxCardElevation}.");
                mPrintedShadowClipWarning = true;
            }
        }
        if (mRawShadowSize == shadowSize && mRawMaxShadowSize == maxShadowSize) {
            return;
        }
        mRawShadowSize = shadowSize;
        mRawMaxShadowSize = maxShadowSize;
        mShadowSize = shadowSize * SHADOW_MULTIPLIER + mInsetShadow;
        mMaxShadowSize = maxShadowSize + mInsetShadow;
        mDirty = true;
        invalidateSelf();
    }

    @Override
    public boolean getPadding(Rect padding) {
        int vOffset = (int) Math.ceil(calculateVerticalPadding(mRawMaxShadowSize, mCornerRadius,
                mAddPaddingForCorners));
        int hOffset = (int) Math.ceil(calculateHorizontalPadding(mRawMaxShadowSize, mCornerRadius,
                mAddPaddingForCorners));
        padding.set(hOffset, vOffset, hOffset, vOffset);
        return true;
    }

    static float calculateVerticalPadding(float maxShadowSize, float cornerRadius,
            boolean addPaddingForCorners) {
        if (addPaddingForCorners) {
            return (float) (maxShadowSize * SHADOW_MULTIPLIER + (1 - COS_45) * cornerRadius);
        } else {
            return maxShadowSize * SHADOW_MULTIPLIER;
        }
    }

    static float calculateHorizontalPadding(float maxShadowSize, float cornerRadius,
            boolean addPaddingForCorners) {
        if (addPaddingForCorners) {
            return (float) (maxShadowSize + (1 - COS_45) * cornerRadius);
        } else {
            return maxShadowSize;
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mCornerShadowPaint.setColorFilter(colorFilter);
        mEdgeShadowPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mDirty) {
            buildComponents(getBounds());
            mDirty = false;
        }
        canvas.translate(0, mRawShadowSize / 4);
        drawShadow(canvas);
        canvas.translate(0, -mRawShadowSize / 4);
    }

    private void drawShadow(Canvas canvas) {
        final float edgeShadowTop = -mCornerRadius - mShadowSize;
        final float inset = mCornerRadius + mInsetShadow + mRawShadowSize / 2;
        final boolean drawHorizontalEdges = mCardBounds.width() - 2 * inset > 0;
        final boolean drawVerticalEdges = mCardBounds.height() - 2 * inset > 0;
        // LT
        int saved = canvas.save();
        canvas.translate(mCardBounds.left + inset, mCardBounds.top + inset);
        canvas.drawPath(mCornerShadowPath, mCornerShadowPaint);
        if (drawHorizontalEdges) {
            canvas.drawRect(0, edgeShadowTop,
                    mCardBounds.width() - 2 * inset, -mCornerRadius,
                    mEdgeShadowPaint);
        }
        canvas.restoreToCount(saved);
        // RB
        saved = canvas.save();
        canvas.translate(mCardBounds.right - inset, mCardBounds.bottom - inset);
        canvas.rotate(180f);
        canvas.drawPath(mCornerShadowPath, mCornerShadowPaint);
        if (drawHorizontalEdges) {
            canvas.drawRect(0, edgeShadowTop,
                    mCardBounds.width() - 2 * inset, -mCornerRadius + mShadowSize,
                    mEdgeShadowPaint);
        }
        canvas.restoreToCount(saved);
        // LB
        saved = canvas.save();
        canvas.translate(mCardBounds.left + inset, mCardBounds.bottom - inset);
        canvas.rotate(270f);
        canvas.drawPath(mCornerShadowPath, mCornerShadowPaint);
        if (drawVerticalEdges) {
            canvas.drawRect(0, edgeShadowTop,
                    mCardBounds.height() - 2 * inset, -mCornerRadius, mEdgeShadowPaint);
        }
        canvas.restoreToCount(saved);
        // RT
        saved = canvas.save();
        canvas.translate(mCardBounds.right - inset, mCardBounds.top + inset);
        canvas.rotate(90f);
        canvas.drawPath(mCornerShadowPath, mCornerShadowPaint);
        if (drawVerticalEdges) {
            canvas.drawRect(0, edgeShadowTop,
                    mCardBounds.height() - 2 * inset, -mCornerRadius, mEdgeShadowPaint);
        }
        canvas.restoreToCount(saved);
    }

    private void buildShadowCorners() {
        RectF innerBounds = new RectF(-mCornerRadius, -mCornerRadius, mCornerRadius, mCornerRadius);
        RectF outerBounds = new RectF(innerBounds);
        outerBounds.inset(-mShadowSize, -mShadowSize);

        if (mCornerShadowPath == null) {
            mCornerShadowPath = new Path();
        } else {
            mCornerShadowPath.reset();
        }
        mCornerShadowPath.setFillType(Path.FillType.EVEN_ODD);
        mCornerShadowPath.moveTo(-mCornerRadius, 0);
        mCornerShadowPath.rLineTo(-mShadowSize, 0);
        // outer arc
        mCornerShadowPath.arcTo(outerBounds, 180f, 90f, false);
        // inner arc
        mCornerShadowPath.arcTo(innerBounds, 270f, -90f, false);
        mCornerShadowPath.close();

        float startRatio = mCornerRadius / (mCornerRadius + mShadowSize);
        mCornerShadowPaint.setShader(new RadialGradient(0, 0, mCornerRadius + mShadowSize,
                new int[]{mShadowStartColor, mShadowStartColor, mShadowEndColor},
                new float[]{0f, startRatio, 1f}
                , Shader.TileMode.CLAMP));

        // we offset the content shadowSize/2 pixels up to make it more realistic.
        // this is why edge shadow shader has some extra space
        // When drawing bottom edge shadow, we use that extra space.
        mEdgeShadowPaint.setShader(new LinearGradient(0, -mCornerRadius + mShadowSize, 0,
                -mCornerRadius - mShadowSize,
                new int[]{mShadowStartColor, mShadowStartColor, mShadowEndColor},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
    }

    private void buildComponents(Rect bounds) {
        // Card is offset SHADOW_MULTIPLIER * maxShadowSize to account for the shadow shift.
        // We could have different top-bottom offsets to avoid extra gap above but in that case
        // center aligning Views inside the CardView would be problematic.
        final float verticalOffset = mMaxShadowSize * SHADOW_MULTIPLIER;
        mCardBounds.set(bounds.left + mMaxShadowSize, bounds.top + verticalOffset,
                bounds.right - mMaxShadowSize, bounds.bottom - verticalOffset);
        buildShadowCorners();
    }

    float getMinWidth() {
        final float content = 2 *
                Math.max(mRawMaxShadowSize, mCornerRadius + mInsetShadow + mRawMaxShadowSize / 2);
        return content + (mRawMaxShadowSize + mInsetShadow) * 2;
    }

    float getMinHeight() {
        final float content = 2 * Math.max(mRawMaxShadowSize, mCornerRadius + mInsetShadow
                        + mRawMaxShadowSize * SHADOW_MULTIPLIER / 2);
        return content + (mRawMaxShadowSize * SHADOW_MULTIPLIER + mInsetShadow) * 2;
    }
}