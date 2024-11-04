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

package com.android.settingslib.graph;

import static com.android.settingslib.flags.Flags.newStatusBarIcons;

import android.animation.ArgbEvaluator;
import android.annotation.IntRange;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.DrawableWrapper;
import android.os.Handler;
import android.telephony.CellSignalStrength;
import android.util.LayoutDirection;
import android.util.PathParser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

import java.util.Objects;

/**
 * Drawable displaying a mobile cell signal indicator.
 */
public class SignalDrawable extends DrawableWrapper {

    private static final String TAG = "SignalDrawable";

    private static final int NUM_DOTS = 3;

    private static final float VIEWPORT = 24f;
    private static final float PAD = 2f / VIEWPORT;

    private static final float DOT_SIZE = 3f / VIEWPORT;
    private static final float DOT_PADDING = 1.5f / VIEWPORT;

    // All of these are masks to push all of the drawable state into one int for easy callbacks
    // and flow through sysui.
    private static final int LEVEL_MASK = 0xff;
    private static final int NUM_LEVEL_SHIFT = 8;
    private static final int NUM_LEVEL_MASK = 0xff << NUM_LEVEL_SHIFT;
    private static final int STATE_SHIFT = 16;
    private static final int STATE_MASK = 0xff << STATE_SHIFT;
    private static final int STATE_CUT = 2;
    private static final int STATE_CARRIER_CHANGE = 3;

    private static final long DOT_DELAY = 1000;

    // Check the config for which icon we want to use
    private static final int ICON_RES = SignalDrawable.getIconRes();

    private final Paint mForegroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTransparentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mDarkModeFillColor;
    private final int mLightModeFillColor;
    private final Path mCutoutPath = new Path();
    private final Path mForegroundPath = new Path();
    private final Path mAttributionPath = new Path();
    private final Matrix mAttributionScaleMatrix = new Matrix();
    private final Path mScaledAttributionPath = new Path();
    private final Handler mHandler;
    private final float mCutoutWidthFraction;
    private final float mCutoutHeightFraction;
    private float mDarkIntensity = -1;
    private final int mIntrinsicSize;
    private boolean mAnimating;
    private int mCurrentDot;

    public SignalDrawable(Context context) {
        this(context, new Handler());
    }

    public SignalDrawable(@NonNull Context context, @NonNull Handler handler) {
        super(context.getDrawable(ICON_RES));
        final String attributionPathString = context.getString(
                com.android.internal.R.string.config_signalAttributionPath);
        mAttributionPath.set(PathParser.createPathFromPathData(attributionPathString));
        updateScaledAttributionPath();
        mCutoutWidthFraction = context.getResources().getFloat(
                com.android.internal.R.dimen.config_signalCutoutWidthFraction);
        mCutoutHeightFraction = context.getResources().getFloat(
                com.android.internal.R.dimen.config_signalCutoutHeightFraction);
        mDarkModeFillColor = Utils.getColorStateListDefaultColor(context,
                R.color.dark_mode_icon_color_single_tone);
        mLightModeFillColor = Utils.getColorStateListDefaultColor(context,
                R.color.light_mode_icon_color_single_tone);
        mIntrinsicSize = context.getResources().getDimensionPixelSize(R.dimen.signal_icon_size);
        mTransparentPaint.setColor(context.getColor(android.R.color.transparent));
        mTransparentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        mHandler = handler;
        setDarkIntensity(0);
    }

    private void updateScaledAttributionPath() {
        if (getBounds().isEmpty()) {
            mAttributionScaleMatrix.setScale(1f, 1f);
        } else {
            mAttributionScaleMatrix.setScale(
                    getBounds().width() / VIEWPORT, getBounds().height() / VIEWPORT);
        }
        mAttributionPath.transform(mAttributionScaleMatrix, mScaledAttributionPath);
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicSize;
    }

    private void updateAnimation() {
        boolean shouldAnimate = isInState(STATE_CARRIER_CHANGE) && isVisible();
        if (shouldAnimate == mAnimating) return;
        mAnimating = shouldAnimate;
        if (shouldAnimate) {
            mChangeDot.run();
        } else {
            mHandler.removeCallbacks(mChangeDot);
        }
    }

    @Override
    protected boolean onLevelChange(int packedState) {
        super.onLevelChange(unpackLevel(packedState));
        updateAnimation();
        setTintList(ColorStateList.valueOf(mForegroundPaint.getColor()));
        invalidateSelf();
        return true;
    }

    private int unpackLevel(int packedState) {
        int numBins = (packedState & NUM_LEVEL_MASK) >> NUM_LEVEL_SHIFT;
        int cutOutOffset = 0;
        int levelOffset = numBins == (CellSignalStrength.getNumSignalStrengthLevels() + 1) ? 10 : 0;
        int level = (packedState & LEVEL_MASK);

        if (newStatusBarIcons()) {
            if (isInState(STATE_CUT)) {
                cutOutOffset = 20;
            }
        }

        return level + levelOffset + cutOutOffset;
    }

    public void setDarkIntensity(float darkIntensity) {
        if (darkIntensity == mDarkIntensity) {
            return;
        }
        setTintList(ColorStateList.valueOf(getFillColor(darkIntensity)));
    }

    @Override
    public void setTintList(ColorStateList tint) {
        super.setTintList(tint);
        int colorForeground = mForegroundPaint.getColor();
        mForegroundPaint.setColor(tint.getDefaultColor());
        if (colorForeground != mForegroundPaint.getColor()) invalidateSelf();
    }

    private int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        updateScaledAttributionPath();
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.saveLayer(null, null);
        final float width = getBounds().width();
        final float height = getBounds().height();

        boolean isRtl = getLayoutDirection() == LayoutDirection.RTL;
        if (isRtl) {
            canvas.save();
            // Mirror the drawable
            canvas.translate(width, 0);
            canvas.scale(-1.0f, 1.0f);
        }
        super.draw(canvas);
        mCutoutPath.reset();
        mCutoutPath.setFillType(FillType.WINDING);

        final float padding = Math.round(PAD * width);

        if (isInState(STATE_CARRIER_CHANGE)) {
            float dotSize = (DOT_SIZE * height);
            float dotPadding = (DOT_PADDING * height);
            float dotSpacing = dotPadding + dotSize;
            float x = width - padding - dotSize;
            float y = height - padding - dotSize;
            mForegroundPath.reset();
            drawDotAndPadding(x, y, dotPadding, dotSize, 2);
            drawDotAndPadding(x - dotSpacing, y, dotPadding, dotSize, 1);
            drawDotAndPadding(x - dotSpacing * 2, y, dotPadding, dotSize, 0);
            canvas.drawPath(mCutoutPath, mTransparentPaint);
            canvas.drawPath(mForegroundPath, mForegroundPaint);
        } else if (!newStatusBarIcons() && isInState(STATE_CUT)) {
            float cutX = (mCutoutWidthFraction * width / VIEWPORT);
            float cutY = (mCutoutHeightFraction * height / VIEWPORT);
            mCutoutPath.moveTo(width, height);
            mCutoutPath.rLineTo(-cutX, 0);
            mCutoutPath.rLineTo(0, -cutY);
            mCutoutPath.rLineTo(cutX, 0);
            mCutoutPath.rLineTo(0, cutY);
            canvas.drawPath(mCutoutPath, mTransparentPaint);
            canvas.drawPath(mScaledAttributionPath, mForegroundPaint);
        }
        if (isRtl) {
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawDotAndPadding(float x, float y,
            float dotPadding, float dotSize, int i) {
        if (i == mCurrentDot) {
            // Draw dot
            mForegroundPath.addRect(x, y, x + dotSize, y + dotSize, Direction.CW);
            // Draw dot padding
            mCutoutPath.addRect(x - dotPadding, y - dotPadding, x + dotSize + dotPadding,
                    y + dotSize + dotPadding, Direction.CW);
        }
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        super.setAlpha(alpha);
        mForegroundPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        super.setColorFilter(colorFilter);
        mForegroundPaint.setColorFilter(colorFilter);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        updateAnimation();
        return changed;
    }

    private final Runnable mChangeDot = new Runnable() {
        @Override
        public void run() {
            if (++mCurrentDot == NUM_DOTS) {
                mCurrentDot = 0;
            }
            invalidateSelf();
            mHandler.postDelayed(mChangeDot, DOT_DELAY);
        }
    };

    /**
     * Returns whether this drawable is in the specified state.
     *
     * @param state must be one of {@link #STATE_CARRIER_CHANGE} or {@link #STATE_CUT}
     */
    private boolean isInState(int state) {
        return getState(getLevel()) == state;
    }

    public static int getState(int fullState) {
        return (fullState & STATE_MASK) >> STATE_SHIFT;
    }

    public static int getState(int level, int numLevels, boolean cutOut) {
        return ((cutOut ? STATE_CUT : 0) << STATE_SHIFT)
                | (numLevels << NUM_LEVEL_SHIFT)
                | level;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SignalDrawable
                && ((SignalDrawable) other).getLevel() == this.getLevel();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getLevel());
    }

    /** Returns the state representing empty mobile signal with the given number of levels. */
    public static int getEmptyState(int numLevels) {
        return getState(0, numLevels, true);
    }

    /** Returns the state representing carrier change with the given number of levels. */
    public static int getCarrierChangeState(int numLevels) {
        return (STATE_CARRIER_CHANGE << STATE_SHIFT) | (numLevels << NUM_LEVEL_SHIFT);
    }

    private static int getIconRes() {
        if (newStatusBarIcons()) {
            return R.drawable.ic_mobile_level_list;
        } else {
            return com.android.internal.R.drawable.ic_signal_cellular;
        }
    }
}
