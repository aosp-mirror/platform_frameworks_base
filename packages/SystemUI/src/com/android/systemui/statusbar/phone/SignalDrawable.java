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

package com.android.systemui.statusbar.phone;

import android.animation.ArgbEvaluator;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.Path.Op;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.LayoutDirection;
import android.util.Log;

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

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mForegroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mDarkModeBackgroundColor;
    private final int mDarkModeFillColor;
    private final int mLightModeBackgroundColor;
    private final int mLightModeFillColor;
    private final Path mFullPath = new Path();
    private final Path mForegroundPath = new Path();
    private final Path mXPath = new Path();
    private final int mIntrinsicSize;
    private final Handler mHandler;
    private float mOldDarkIntensity = -1;
    private float mNumLevels = 1;
    private int mLevel;
    private int mState;
    private boolean mVisible;
    private boolean mAnimating;
    private int mCurrentDot;

    public SignalDrawable(Context context) {
        mDarkModeBackgroundColor =
                Utils.getDefaultColor(context, R.color.dark_mode_icon_color_dual_tone_background);
        mDarkModeFillColor =
                Utils.getDefaultColor(context, R.color.dark_mode_icon_color_dual_tone_fill);
        mLightModeBackgroundColor =
                Utils.getDefaultColor(context, R.color.light_mode_icon_color_dual_tone_background);
        mLightModeFillColor =
                Utils.getDefaultColor(context, R.color.light_mode_icon_color_dual_tone_fill);
        mIntrinsicSize = context.getResources().getDimensionPixelSize(R.dimen.signal_icon_size);
        mHandler = new Handler();
        setDarkIntensity(0);
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
        boolean isRtl = getLayoutDirection() == LayoutDirection.RTL;
        if (isRtl) {
            canvas.save();
            // Mirror the drawable
            canvas.translate(canvas.getWidth(), 0);
            canvas.scale(-1.0f, 1.0f);
        }
        mFullPath.reset();
        mFullPath.setFillType(FillType.WINDING);
        float width = getBounds().width();
        float height = getBounds().height();
        float padding = (PAD * width);
        mFullPath.moveTo(width - padding, height - padding);
        mFullPath.lineTo(width - padding, padding);
        mFullPath.lineTo(padding, height - padding);
        mFullPath.lineTo(width - padding, height - padding);

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

        mPaint.setStyle(mState == STATE_EMPTY ? Style.STROKE : Style.FILL);
        mForegroundPaint.setStyle(mState == STATE_EMPTY ? Style.STROKE : Style.FILL);

        if (mState != STATE_CARRIER_CHANGE) {
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
}
