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

package com.android.systemui.privacy.television;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.MathUtils;
import android.view.Gravity;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * Drawable that can go from being the background of the privacy icons to a small dot.
 * The icons are not included.
 */
public class PrivacyChipDrawable extends Drawable {
    private static final String TAG = PrivacyChipDrawable.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Paint mChipPaint;
    private final Paint mBgPaint;
    private final Rect mTmpRect = new Rect();
    private final Rect mBgRect = new Rect();
    private final RectF mTmpRectF = new RectF();
    private final Path mPath = new Path();
    private final Animator mCollapse;
    private final Animator mExpand;
    private final int mLayoutDirection;
    private final int mBgWidth;
    private final int mBgHeight;
    private final int mBgRadius;
    private final int mDotSize;
    private final float mExpandedChipRadius;
    private final float mCollapsedChipRadius;

    private final boolean mCollapseToDot;

    private boolean mIsExpanded = true;
    private float mCollapseProgress = 0f;

    public PrivacyChipDrawable(Context context, int chipColorRes, boolean collapseToDot) {
        mCollapseToDot = collapseToDot;

        mChipPaint = new Paint();
        mChipPaint.setStyle(Paint.Style.FILL);
        mChipPaint.setColor(context.getColor(chipColorRes));
        mChipPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        mBgPaint = new Paint();
        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setColor(context.getColor(R.color.privacy_chip_dot_bg_tint));
        mBgPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        Resources res = context.getResources();
        mLayoutDirection = res.getConfiguration().getLayoutDirection();
        mBgWidth = res.getDimensionPixelSize(R.dimen.privacy_chip_dot_bg_width);
        mBgHeight = res.getDimensionPixelSize(R.dimen.privacy_chip_dot_bg_height);
        mBgRadius = res.getDimensionPixelSize(R.dimen.privacy_chip_dot_bg_radius);
        mDotSize = res.getDimensionPixelSize(R.dimen.privacy_chip_dot_size);

        mExpandedChipRadius = res.getDimensionPixelSize(R.dimen.privacy_chip_radius);
        mCollapsedChipRadius = res.getDimensionPixelSize(R.dimen.privacy_chip_dot_radius);

        mExpand = AnimatorInflater.loadAnimator(context, R.anim.tv_privacy_chip_expand);
        mExpand.setTarget(this);
        mCollapse = AnimatorInflater.loadAnimator(context, R.anim.tv_privacy_chip_collapse);
        mCollapse.setTarget(this);
    }

    /**
     * @return how far the chip is currently collapsed.
     * @see #setCollapseProgress(float)
     */
    @Keep
    public float getCollapseProgress() {
        return mCollapseProgress;
    }

    /**
     * Sets the collapsing progress of the chip to its collapsed state.
     * @param pct How far the chip is collapsed, in the range 0-1.
     *            0=fully expanded, 1=fully collapsed.
     */
    @Keep
    public void setCollapseProgress(float pct) {
        mCollapseProgress = pct;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mCollapseProgress > 0f) {
            // draw background
            getBackgroundBounds(mBgRect);
            mTmpRectF.set(mBgRect);
            canvas.drawRoundRect(mTmpRectF, mBgRadius, mBgRadius, mBgPaint);
        }

        getForegroundBounds(mTmpRectF);
        float radius = MathUtils.lerp(
                mExpandedChipRadius,
                mCollapseToDot ? mCollapsedChipRadius : mBgRadius,
                mCollapseProgress);

        canvas.drawRoundRect(mTmpRectF, radius, radius, mChipPaint);
    }

    private void getBackgroundBounds(Rect out) {
        Rect bounds = getBounds();
        Gravity.apply(Gravity.END, mBgWidth, mBgHeight, bounds, out, mLayoutDirection);
    }

    private void getCollapsedForegroundBounds(Rect out) {
        Rect bounds = getBounds();
        getBackgroundBounds(mBgRect);
        if (mCollapseToDot) {
            Gravity.apply(Gravity.CENTER, mDotSize, mDotSize, mBgRect, out);
        } else {
            out.set(bounds.left, mBgRect.top, bounds.right, mBgRect.bottom);
        }
    }

    private void getForegroundBounds(RectF out) {
        Rect bounds = getBounds();
        getCollapsedForegroundBounds(mTmpRect);
        lerpRect(bounds, mTmpRect, mCollapseProgress, out);
    }

    private void lerpRect(Rect start, Rect stop, float amount, RectF out) {
        float left = MathUtils.lerp(start.left, stop.left, amount);
        float top = MathUtils.lerp(start.top, stop.top, amount);
        float right = MathUtils.lerp(start.right, stop.right, amount);
        float bottom = MathUtils.lerp(start.bottom, stop.bottom, amount);
        out.set(left, top, right, bottom);
    }

    /**
     * Clips the given canvas to this chip's foreground shape.
     * @param canvas Canvas to clip.
     */
    public void clipToForeground(Canvas canvas) {
        getForegroundBounds(mTmpRectF);
        float radius = MathUtils.lerp(
                mExpandedChipRadius,
                mCollapseToDot ? mCollapsedChipRadius : mBgRadius,
                mCollapseProgress);

        mPath.reset();
        mPath.addRoundRect(mTmpRectF, radius, radius, Path.Direction.CW);
        canvas.clipPath(mPath);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        mChipPaint.setAlpha(alpha);
        mBgPaint.setAlpha(alpha);
    }

    /**
     * Transitions to a full chip.
     *
     * @param animate Whether to animate the change to a full chip, or expand instantly.
     */
    public void expand(boolean animate) {
        if (DEBUG) Log.d(TAG, "expanding");
        if (mIsExpanded) {
            return;
        }
        mIsExpanded = true;
        if (animate) {
            mCollapse.cancel();
            mExpand.start();
        } else {
            mCollapseProgress = 0f;
            invalidateSelf();
        }
    }

    /**
     * Starts the animation to a dot.
     */
    public void collapse() {
        if (DEBUG) Log.d(TAG, "collapsing");
        if (!mIsExpanded) {
            return;
        }
        mIsExpanded = false;
        mExpand.cancel();
        mCollapse.start();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // no-op
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
