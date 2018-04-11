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

import android.animation.ArgbEvaluator;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.Path.Op;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.LayoutDirection;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

public class SignalDrawable extends Drawable {

    private static final String TAG = "SignalDrawable";

    private static final int NUM_DOTS = 3;

    private static final float VIEWPORT = 24f;
    private static final float PAD = 2f / VIEWPORT;
    private static final float CUT_OUT = 7.9f / VIEWPORT;

    private static final float DOT_SIZE = 3f / VIEWPORT;
    private static final float DOT_PADDING = 1f / VIEWPORT;
    private static final float DOT_CUT_WIDTH = (DOT_SIZE * 3) + (DOT_PADDING * 5);
    private static final float DOT_CUT_HEIGHT = (DOT_SIZE * 1) + (DOT_PADDING * 1);

    private static final float[] FIT = {2.26f, -3.02f, 1.76f};

    // All of these are masks to push all of the drawable state into one int for easy callbacks
    // and flow through sysui.
    private static final int LEVEL_MASK = 0xff;
    private static final int NUM_LEVEL_SHIFT = 8;
    private static final int NUM_LEVEL_MASK = 0xff << NUM_LEVEL_SHIFT;
    private static final int STATE_SHIFT = 16;
    private static final int STATE_MASK = 0xff << STATE_SHIFT;
    private static final int STATE_NONE = 0;
    private static final int STATE_EMPTY = 1;
    private static final int STATE_CUT = 2;
    private static final int STATE_CARRIER_CHANGE = 3;
    private static final int STATE_AIRPLANE = 4;

    private static final long DOT_DELAY = 1000;

    private static float[][] X_PATH = new float[][]{
            {21.9f / VIEWPORT, 17.0f / VIEWPORT},
            {-1.1f / VIEWPORT, -1.1f / VIEWPORT},
            {-1.9f / VIEWPORT, 1.9f / VIEWPORT},
            {-1.9f / VIEWPORT, -1.9f / VIEWPORT},
            {-1.1f / VIEWPORT, 1.1f / VIEWPORT},
            {1.9f / VIEWPORT, 1.9f / VIEWPORT},
            {-1.9f / VIEWPORT, 1.9f / VIEWPORT},
            {1.1f / VIEWPORT, 1.1f / VIEWPORT},
            {1.9f / VIEWPORT, -1.9f / VIEWPORT},
            {1.9f / VIEWPORT, 1.9f / VIEWPORT},
            {1.1f / VIEWPORT, -1.1f / VIEWPORT},
            {-1.9f / VIEWPORT, -1.9f / VIEWPORT},
    };

    // Rounded corners are achieved by arcing a circle of radius `R` from its tangent points along
    // the curve (curve ≡ triangle). On the top and left corners of the triangle, the tangents are
    // as follows:
    //      1) Along the straight lines (y = 0 and x = width):
    //          Ps = circleOffset + R
    //      2) Along the diagonal line (y = x):
    //          Pd = √((Ps^2) / 2)
    //              or (remember: sin(π/4) ≈ 0.7071)
    //          Pd = (circleOffset + R - 0.7071, height - R - 0.7071)
    //         Where Pd is the (x,y) coords of the point that intersects the circle at the bottom
    //         left of the triangle
    private static final float RADIUS_RATIO = 0.75f / 17f;
    private static final float DIAG_OFFSET_MULTIPLIER = 0.707107f;
    // How far the circle defining the corners is inset from the edges
    private final float mAppliedCornerInset;

    private static final float INV_TAN = 1f / (float) Math.tan(Math.PI / 8f);
    private static final float CUT_WIDTH_DP = 1f / 12f;

    // Where the top and left points of the triangle would be if not for rounding
    private final PointF mVirtualTop  = new PointF();
    private final PointF mVirtualLeft = new PointF();

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mForegroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mDarkModeBackgroundColor;
    private final int mDarkModeFillColor;
    private final int mLightModeBackgroundColor;
    private final int mLightModeFillColor;
    private final Path mFullPath = new Path();
    private final Path mForegroundPath = new Path();
    private final Path mXPath = new Path();
    // Cut out when STATE_EMPTY
    private final Path mCutPath = new Path();
    // Draws the slash when in airplane mode
    private final SlashArtist mSlash = new SlashArtist();
    private final Handler mHandler;
    private float mOldDarkIntensity = -1;
    private float mNumLevels = 1;
    private int mIntrinsicSize;
    private int mLevel;
    private int mState;
    private boolean mVisible;
    private boolean mAnimating;
    private int mCurrentDot;

    public SignalDrawable(Context context) {
        mDarkModeBackgroundColor =
                Utils.getColorStateListDefaultColor(context,
                        R.color.dark_mode_icon_color_dual_tone_background);
        mDarkModeFillColor =
                Utils.getColorStateListDefaultColor(context,
                        R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                Utils.getColorStateListDefaultColor(context,
                        R.color.light_mode_icon_color_dual_tone_background);
        mLightModeFillColor =
                Utils.getColorStateListDefaultColor(context,
                        R.color.light_mode_icon_color_dual_tone_fill);
        mIntrinsicSize = context.getResources().getDimensionPixelSize(R.dimen.signal_icon_size);

        mHandler = new Handler();
        setDarkIntensity(0);

        mAppliedCornerInset = context.getResources()
                .getDimensionPixelSize(R.dimen.stat_sys_mobile_signal_circle_inset);
    }

    public void setIntrinsicSize(int size) {
        mIntrinsicSize = size;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicSize;
    }

    public void setNumLevels(int levels) {
        if (levels == mNumLevels) return;
        mNumLevels = levels;
        invalidateSelf();
    }

    private void setSignalState(int state) {
        if (state == mState) return;
        mState = state;
        updateAnimation();
        invalidateSelf();
    }

    private void updateAnimation() {
        boolean shouldAnimate = (mState == STATE_CARRIER_CHANGE) && mVisible;
        if (shouldAnimate == mAnimating) return;
        mAnimating = shouldAnimate;
        if (shouldAnimate) {
            mChangeDot.run();
        } else {
            mHandler.removeCallbacks(mChangeDot);
        }
    }

    @Override
    protected boolean onLevelChange(int state) {
        setNumLevels(getNumLevels(state));
        setSignalState(getState(state));
        int level = getLevel(state);
        if (level != mLevel) {
            mLevel = level;
            invalidateSelf();
        }
        return true;
    }

    public void setColors(int background, int foreground) {
        mPaint.setColor(background);
        mForegroundPaint.setColor(foreground);
    }

    public void setDarkIntensity(float darkIntensity) {
        if (darkIntensity == mOldDarkIntensity) {
            return;
        }
        mPaint.setColor(getBackgroundColor(darkIntensity));
        mForegroundPaint.setColor(getFillColor(darkIntensity));
        mOldDarkIntensity = darkIntensity;
        invalidateSelf();
    }

    private int getFillColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeFillColor, mDarkModeFillColor);
    }

    private int getBackgroundColor(float darkIntensity) {
        return getColorForDarkIntensity(
                darkIntensity, mLightModeBackgroundColor, mDarkModeBackgroundColor);
    }

    private int getColorForDarkIntensity(float darkIntensity, int lightColor, int darkColor) {
        return (int) ArgbEvaluator.getInstance().evaluate(darkIntensity, lightColor, darkColor);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final float width = getBounds().width();
        final float height = getBounds().height();

        boolean isRtl = getLayoutDirection() == LayoutDirection.RTL;
        if (isRtl) {
            canvas.save();
            // Mirror the drawable
            canvas.translate(width, 0);
            canvas.scale(-1.0f, 1.0f);
        }
        mFullPath.reset();
        mFullPath.setFillType(FillType.WINDING);

        final float padding = Math.round(PAD * width);
        final float cornerRadius = RADIUS_RATIO * height;
        // Offset from circle where the hypotenuse meets the circle
        final float diagOffset = DIAG_OFFSET_MULTIPLIER * cornerRadius;

        // 1 - Bottom right, above corner
        mFullPath.moveTo(width - padding, height - padding - cornerRadius);
        // 2 - Line to top right, below corner
        mFullPath.lineTo(width - padding, padding + cornerRadius + mAppliedCornerInset);
        // 3 - Arc to top right, on hypotenuse
        mFullPath.arcTo(
                width - padding - (2 * cornerRadius),
                padding + mAppliedCornerInset,
                width - padding,
                padding + mAppliedCornerInset + (2 * cornerRadius),
                0.f, -135.f, false
        );
        // 4 - Line to bottom left, on hypotenuse
        mFullPath.lineTo(padding + mAppliedCornerInset + cornerRadius - diagOffset,
                height - padding - cornerRadius - diagOffset);
        // 5 - Arc to bottom left, on leg
        mFullPath.arcTo(
                padding + mAppliedCornerInset,
                height - padding - (2 * cornerRadius),
                padding + mAppliedCornerInset + ( 2 * cornerRadius),
                height - padding,
                -135.f, -135.f, false
        );
        // 6 - Line to bottom rght, before corner
        mFullPath.lineTo(width - padding - cornerRadius, height - padding);
        // 7 - Arc to beginning (bottom right, above corner)
        mFullPath.arcTo(
                width - padding - (2 * cornerRadius),
                height - padding - (2 * cornerRadius),
                width - padding,
                height - padding,
                90.f, -90.f, false
        );

        if (mState == STATE_CARRIER_CHANGE) {
            float cutWidth = (DOT_CUT_WIDTH * width);
            float cutHeight = (DOT_CUT_HEIGHT * width);
            float dotSize = (DOT_SIZE * height);
            float dotPadding = (DOT_PADDING * height);

            mFullPath.moveTo(width - padding, height - padding);
            mFullPath.rLineTo(-cutWidth, 0);
            mFullPath.rLineTo(0, -cutHeight);
            mFullPath.rLineTo(cutWidth, 0);
            mFullPath.rLineTo(0, cutHeight);
            float dotSpacing = dotPadding * 2 + dotSize;
            float x = width - padding - dotSize;
            float y = height - padding - dotSize;
            mForegroundPath.reset();
            drawDot(mFullPath, mForegroundPath, x, y, dotSize, 2);
            drawDot(mFullPath, mForegroundPath, x - dotSpacing, y, dotSize, 1);
            drawDot(mFullPath, mForegroundPath, x - dotSpacing * 2, y, dotSize, 0);
        } else if (mState == STATE_CUT) {
            float cut = (CUT_OUT * width);
            mFullPath.moveTo(width - padding, height - padding);
            mFullPath.rLineTo(-cut, 0);
            mFullPath.rLineTo(0, -cut);
            mFullPath.rLineTo(cut, 0);
            mFullPath.rLineTo(0, cut);
        }

        if (mState == STATE_EMPTY) {
            // Where the corners would be if this were a real triangle
            mVirtualTop.set(
                    width - padding,
                    (padding + cornerRadius + mAppliedCornerInset) - (INV_TAN * cornerRadius));
            mVirtualLeft.set(
                    (padding + cornerRadius + mAppliedCornerInset) - (INV_TAN * cornerRadius),
                    height - padding);

            final float cutWidth = CUT_WIDTH_DP * height;
            final float cutDiagInset = cutWidth * INV_TAN;

            // Cut out a smaller triangle from the center of mFullPath
            mCutPath.reset();
            mCutPath.setFillType(FillType.WINDING);
            mCutPath.moveTo(width - padding - cutWidth, height - padding - cutWidth);
            mCutPath.lineTo(width - padding - cutWidth, mVirtualTop.y + cutDiagInset);
            mCutPath.lineTo(mVirtualLeft.x + cutDiagInset, height - padding - cutWidth);
            mCutPath.lineTo(width - padding - cutWidth, height - padding - cutWidth);

            // Draw empty state as only background
            mForegroundPath.reset();
            mFullPath.op(mCutPath, Path.Op.DIFFERENCE);
        } else if (mState == STATE_AIRPLANE) {
            // Airplane mode is slashed, fully drawn background
            mForegroundPath.reset();
            mSlash.draw((int) height, (int) width, canvas, mPaint);
        } else if (mState != STATE_CARRIER_CHANGE) {
            mForegroundPath.reset();
            int sigWidth = Math.round(calcFit(mLevel / (mNumLevels - 1)) * (width - 2 * padding));
            mForegroundPath.addRect(padding, padding, padding + sigWidth, height - padding,
                    Direction.CW);
            mForegroundPath.op(mFullPath, Op.INTERSECT);
        }

        canvas.drawPath(mFullPath, mPaint);
        canvas.drawPath(mForegroundPath, mForegroundPaint);
        if (mState == STATE_CUT) {
            mXPath.reset();
            mXPath.moveTo(X_PATH[0][0] * width, X_PATH[0][1] * height);
            for (int i = 1; i < X_PATH.length; i++) {
                mXPath.rLineTo(X_PATH[i][0] * width, X_PATH[i][1] * height);
            }
            canvas.drawPath(mXPath, mForegroundPaint);
        }
        if (isRtl) {
            canvas.restore();
        }
    }

    private void drawDot(Path fullPath, Path foregroundPath, float x, float y, float dotSize,
            int i) {
        Path p = (i == mCurrentDot) ? foregroundPath : fullPath;
        p.addRect(x, y, x + dotSize, y + dotSize, Direction.CW);
    }

    // This is a fit line based on previous values of provided in assets, but if
    // you look at the a plot of this actual fit, it makes a lot of sense, what it does
    // is compress the areas that are very visually easy to see changes (the middle sections)
    // and spread out the sections that are hard to see (each end of the icon).
    // The current fit is cubic, but pretty easy to change the way the code is written (just add
    // terms to the end of FIT).
    private float calcFit(float v) {
        float ret = 0;
        float t = v;
        for (int i = 0; i < FIT.length; i++) {
            ret += FIT[i] * t;
            t *= v;
        }
        return ret;
    }

    @Override
    public int getAlpha() {
        return mPaint.getAlpha();
    }

    @Override
    public void setAlpha(@IntRange(from = 0, to = 255) int alpha) {
        mPaint.setAlpha(alpha);
        mForegroundPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        mForegroundPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 255;
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mVisible = visible;
        updateAnimation();
        return super.setVisible(visible, restart);
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

    public static int getLevel(int fullState) {
        return fullState & LEVEL_MASK;
    }

    public static int getState(int fullState) {
        return (fullState & STATE_MASK) >> STATE_SHIFT;
    }

    public static int getNumLevels(int fullState) {
        return (fullState & NUM_LEVEL_MASK) >> NUM_LEVEL_SHIFT;
    }

    public static int getState(int level, int numLevels, boolean cutOut) {
        return ((cutOut ? STATE_CUT : 0) << STATE_SHIFT)
                | (numLevels << NUM_LEVEL_SHIFT)
                | level;
    }

    public static int getCarrierChangeState(int numLevels) {
        return (STATE_CARRIER_CHANGE << STATE_SHIFT) | (numLevels << NUM_LEVEL_SHIFT);
    }

    public static int getEmptyState(int numLevels) {
        return (STATE_EMPTY << STATE_SHIFT) | (numLevels << NUM_LEVEL_SHIFT);
    }

    public static int getAirplaneModeState(int numLevels) {
        return (STATE_AIRPLANE << STATE_SHIFT) | (numLevels << NUM_LEVEL_SHIFT);
    }

    private final class SlashArtist {
        private static final float CORNER_RADIUS = 1f;
        // These values are derived in un-rotated (vertical) orientation
        private static final float SLASH_WIDTH = 1.8384776f;
        private static final float SLASH_HEIGHT = 22f;
        private static final float CENTER_X = 10.65f;
        private static final float CENTER_Y = 15.869239f;
        private static final float SCALE = 24f;

        // Bottom is derived during animation
        private static final float LEFT = (CENTER_X - (SLASH_WIDTH / 2)) / SCALE;
        private static final float TOP = (CENTER_Y - (SLASH_HEIGHT / 2)) / SCALE;
        private static final float RIGHT = (CENTER_X + (SLASH_WIDTH / 2)) / SCALE;
        private static final float BOTTOM = (CENTER_Y + (SLASH_HEIGHT / 2)) / SCALE;
        // Draw the slash washington-monument style; rotate to no-u-turn style
        private static final float ROTATION = -45f;

        private final Path mPath = new Path();
        private final RectF mSlashRect = new RectF();

        void draw(int height, int width, @NonNull Canvas canvas, Paint paint) {
            Matrix m = new Matrix();
            final float radius = scale(CORNER_RADIUS, width);
            updateRect(
                    scale(LEFT, width),
                    scale(TOP, height),
                    scale(RIGHT, width),
                    scale(BOTTOM, height));

            mPath.reset();
            // Draw the slash vertically
            mPath.addRoundRect(mSlashRect, radius, radius, Direction.CW);
            m.setRotate(ROTATION, width / 2, height / 2);
            mPath.transform(m);
            canvas.drawPath(mPath, paint);

            // Rotate back to vertical, and draw the cut-out rect next to this one
            m.setRotate(-ROTATION, width / 2, height / 2);
            mPath.transform(m);
            m.setTranslate(mSlashRect.width(), 0);
            mPath.transform(m);
            mPath.addRoundRect(mSlashRect, radius, radius, Direction.CW);
            m.setRotate(ROTATION, width / 2, height / 2);
            mPath.transform(m);
            canvas.clipOutPath(mPath);
        }

        void updateRect(float left, float top, float right, float bottom) {
            mSlashRect.left = left;
            mSlashRect.top = top;
            mSlashRect.right = right;
            mSlashRect.bottom = bottom;
        }

        private float scale(float frac, int width) {
            return frac * width;
        }
    }
}
