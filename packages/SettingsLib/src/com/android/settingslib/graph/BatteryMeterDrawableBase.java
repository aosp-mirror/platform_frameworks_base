/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.graph;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.FillType;
import android.graphics.Path.Op;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

public class BatteryMeterDrawableBase extends Drawable {

    private static final float ASPECT_RATIO = .58f;
    public static final String TAG = BatteryMeterDrawableBase.class.getSimpleName();
    private static final float RADIUS_RATIO = 1.0f / 17f;

    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_CIRCLE = 1;
    public static final int BATTERY_STYLE_DOTTED_CIRCLE = 2;
    public static final int BATTERY_STYLE_SQUARE = 3; // not functional
    public static final int BATTERY_STYLE_TEXT = 4;
    public static final int BATTERY_STYLE_HIDDEN = 5;

    protected final Context mContext;
    protected final Paint mFramePaint;
    protected final Paint mBatteryPaint;
    protected final Paint mWarningTextPaint;
    protected final Paint mTextPaint;
    protected final Paint mBoltPaint;
    protected final Paint mPlusPaint;
    protected final Paint mPowersavePaint;
    protected float mButtonHeightFraction;

    private int mLevel = -1;
    private int mMeterStyle;
    private boolean mCharging;
    private boolean mPowerSaveEnabled;
    protected boolean mPowerSaveAsColorError = true;
    private boolean mShowPercent;

    private static final boolean SINGLE_DIGIT_PERCENT = false;

    private static final int FULL = 96;

    private static final float BOLT_LEVEL_THRESHOLD = 0.3f;  // opaque bolt below this fraction

    private final int[] mColors;
    private int mIntrinsicWidth;
    private int mIntrinsicHeight;

    private float mSubpixelSmoothingLeft;
    private float mSubpixelSmoothingRight;
    private float mTextHeight, mWarningTextHeight;
    private int mIconTint = Color.WHITE;
    private float mOldDarkIntensity = -1f;

    private int mHeight;
    private int mWidth;
    private String mWarningString;
    private final int mCriticalLevel;
    private int mChargeColor;
    private final float[] mBoltPoints;
    private final Path mBoltPath = new Path();
    private final float[] mPlusPoints;
    private final Path mPlusPath = new Path();

    private final Rect mPadding = new Rect();
    private final RectF mFrame = new RectF();
    private final RectF mButtonFrame = new RectF();
    private final RectF mBoltFrame = new RectF();
    private final RectF mPlusFrame = new RectF();

    private final Path mShapePath = new Path();
    private final Path mOutlinePath = new Path();
    private final Path mTextPath = new Path();
    private final DashPathEffect mPathEffect = new DashPathEffect(new float[]{3,2},0);

    public BatteryMeterDrawableBase(Context context, int frameColor) {
        // Portrait is the default drawable style
        this(context, frameColor, BATTERY_STYLE_PORTRAIT);
    }

    public BatteryMeterDrawableBase(Context context, int frameColor, int style) {
        mContext = context;
        mMeterStyle = style;
        final Resources res = context.getResources();
        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        mColors = new int[2 * N];
        for (int i = 0; i < N; i++) {
            mColors[2 * i] = levels.getInt(i, 0);
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                mColors[2 * i + 1] = Utils.getColorAttrDefaultColor(context,
                        colors.getThemeAttributeId(i, 0));
            } else {
                mColors[2 * i + 1] = colors.getColor(i, 0);
            }
        }
        levels.recycle();
        colors.recycle();

        mWarningString = context.getString(R.string.battery_meter_very_low_overlay_symbol);
        mCriticalLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mButtonHeightFraction = context.getResources().getFraction(
                R.fraction.battery_button_height_fraction, 1, 1);
        mSubpixelSmoothingLeft = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_left, 1, 1);
        mSubpixelSmoothingRight = context.getResources().getFraction(
                R.fraction.battery_subpixel_smoothing_right, 1, 1);

        mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFramePaint.setColor(frameColor);
        mFramePaint.setDither(true);

        mBatteryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBatteryPaint.setDither(true);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface font = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        mTextPaint.setTypeface(font);
        mTextPaint.setTextAlign(Paint.Align.CENTER);

        mWarningTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        font = Typeface.create("sans-serif", Typeface.BOLD);
        mWarningTextPaint.setTypeface(font);
        mWarningTextPaint.setTextAlign(Paint.Align.CENTER);
        if (mColors.length > 1) {
            mWarningTextPaint.setColor(mColors[1]);
        }

        mChargeColor = Utils.getColorStateListDefaultColor(mContext, R.color.meter_consumed_color);

        mBoltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBoltPaint.setColor(Utils.getColorStateListDefaultColor(mContext,
                R.color.batterymeter_bolt_color));
        mBoltPoints = loadPoints(res, R.array.batterymeter_bolt_points);

        mPlusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPlusPaint.setColor(Utils.getColorStateListDefaultColor(mContext,
                R.color.batterymeter_plus_color));
        mPlusPoints = loadPoints(res, R.array.batterymeter_plus_points);

        mPowersavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPowersavePaint.setColor(mPlusPaint.getColor());
        mPowersavePaint.setStyle(Style.STROKE);

        mIntrinsicWidth = context.getResources().getDimensionPixelSize(R.dimen.battery_width);
        mIntrinsicHeight = context.getResources().getDimensionPixelSize(R.dimen.battery_height);
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicWidth;
    }

    public void setShowPercent(boolean show) {
        if (mShowPercent != show) {
            mShowPercent = show;
            postInvalidate();
        }
    }

    public void setCharging(boolean val) {
        if (mCharging != val) {
            mCharging = val;
            postInvalidate();
        }
    }

    public boolean getCharging() {
        return mCharging;
    }

    public void setBatteryLevel(int val) {
        if (mLevel != val) {
            mLevel = val;
            postInvalidate();
        }
    }

    public int getBatteryLevel() {
        return mLevel;
    }

    public void setPowerSave(boolean val) {
        if (mPowerSaveEnabled != val) {
            mPowerSaveEnabled = val;
            postInvalidate();
        }
    }

    public boolean getPowerSave() {
        return mPowerSaveEnabled;
    }

    protected void setPowerSaveAsColorError(boolean asError) {
        mPowerSaveAsColorError = asError;
    }

    public void setMeterStyle(int style) {
        mMeterStyle = style;
        updateSize();
        postInvalidate();
    }

    // an approximation of View.postInvalidate()
    protected void postInvalidate() {
        unscheduleSelf(this::invalidateSelf);
        scheduleSelf(this::invalidateSelf, 0);
    }

    private static float[] loadPoints(Resources res, int pointArrayRes) {
        final int[] pts = res.getIntArray(pointArrayRes);
        int maxX = 0, maxY = 0;
        for (int i = 0; i < pts.length; i += 2) {
            maxX = Math.max(maxX, pts[i]);
            maxY = Math.max(maxY, pts[i + 1]);
        }
        final float[] ptsF = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            ptsF[i] = (float) pts[i] / maxX;
            ptsF[i + 1] = (float) pts[i + 1] / maxY;
        }
        return ptsF;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        updateSize();
    }

    private void updateSize() {
        final Rect bounds = getBounds();

        mHeight = (bounds.bottom - mPadding.bottom) - (bounds.top + mPadding.top);
        mWidth = (bounds.right - mPadding.right) - (bounds.left + mPadding.left);
        mWarningTextPaint.setTextSize(mHeight * 0.75f);
        mWarningTextHeight = -mWarningTextPaint.getFontMetrics().ascent;

        mIntrinsicHeight = mContext.getResources().getDimensionPixelSize(R.dimen.battery_height);
        mIntrinsicWidth = mMeterStyle == BATTERY_STYLE_PORTRAIT ?
                mContext.getResources().getDimensionPixelSize(R.dimen.battery_width) :
                mContext.getResources().getDimensionPixelSize(R.dimen.battery_height);
    }

    @Override
    public boolean getPadding(Rect padding) {
        if (mPadding.left == 0
            && mPadding.top == 0
            && mPadding.right == 0
            && mPadding.bottom == 0) {
            return super.getPadding(padding);
        }

        padding.set(mPadding);
        return true;
    }

    public void setPadding(int left, int top, int right, int bottom) {
        mPadding.left = left;
        mPadding.top = top;
        mPadding.right = right;
        mPadding.bottom = bottom;

        updateSize();
    }

    private int getColorForLevel(int percent) {
        int thresh, color = 0;
        for (int i = 0; i < mColors.length; i += 2) {
            thresh = mColors[i];
            color = mColors[i + 1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == mColors.length - 2) {
                    return mIconTint;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    public void setColors(int fillColor, int backgroundColor) {
        mIconTint = fillColor;
        mFramePaint.setColor(backgroundColor);
        mBoltPaint.setColor(fillColor);
        mChargeColor = fillColor;
        invalidateSelf();
    }

    protected int batteryColorForLevel(int level) {
        return (mCharging || (mPowerSaveEnabled && mPowerSaveAsColorError))
                ? mChargeColor
                : getColorForLevel(level);
    }

    @Override
    public void draw(Canvas c) {
        switch (mMeterStyle) {
            case BATTERY_STYLE_PORTRAIT:
                drawRectangle(c);
                break;
            case BATTERY_STYLE_CIRCLE:
            case BATTERY_STYLE_DOTTED_CIRCLE:
            default:
                drawCircle(c);
                break;
        }
    }

    private void drawCircle(Canvas c) {
        final int level = mLevel;

        if (level == -1) return;

        final int circleSize = Math.min(mWidth, mHeight);
        float strokeWidth = circleSize / 6.5f;

        mFramePaint.setStrokeWidth(strokeWidth);
        mFramePaint.setStyle(Paint.Style.STROKE);

        mBatteryPaint.setStrokeWidth(strokeWidth);
        mBatteryPaint.setStyle(Paint.Style.STROKE);

        mPowersavePaint.setStrokeWidth(strokeWidth);

        if (mMeterStyle == BATTERY_STYLE_DOTTED_CIRCLE) {
            mBatteryPaint.setPathEffect(mPathEffect);
        } else {
            mBatteryPaint.setPathEffect(null);
        }

        mFrame.set(
                strokeWidth / 2.0f + mPadding.left,
                strokeWidth / 2.0f,
                circleSize - strokeWidth / 2.0f + mPadding.left,
                circleSize - strokeWidth / 2.0f);

        // set the battery charging color
        mBatteryPaint.setColor(batteryColorForLevel(level));

        if (mCharging) {
            // define the bolt shape
            final float bl = mFrame.left + mFrame.width() / 3.0f;
            final float bt = mFrame.top + mFrame.height() / 3.4f;
            final float br = mFrame.right - mFrame.width() / 4.0f;
            final float bb = mFrame.bottom - mFrame.height() / 5.6f;
            if (mBoltFrame.left != bl || mBoltFrame.top != bt
                    || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                mBoltFrame.set(bl, bt, br, bb);
                mBoltPath.reset();
                mBoltPath.moveTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                for (int i = 2; i < mBoltPoints.length; i += 2) {
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                }
                mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
            }
            c.drawPath(mBoltPath, mBoltPaint);
        }

        // draw thin gray ring first
        c.drawArc(mFrame, 270, 360, false, mFramePaint);

        // draw colored arc representing charge level
        if (level > 0) {
            if (!mCharging && mPowerSaveEnabled && mPowerSaveAsColorError) {
                c.drawArc(mFrame, 270, 3.6f * level, false, mPowersavePaint);
            } else {
                c.drawArc(mFrame, 270, 3.6f * level, false, mBatteryPaint);
            }
        }

        if (!mCharging && level != 100 && mShowPercent && !mPowerSaveEnabled) {
            // compute percentage text
            float pctX = 0, pctY = 0;
            String pctText = null;
            mTextPaint.setColor(getColorForLevel(level));
            mTextPaint.setTextSize(mHeight * (SINGLE_DIGIT_PERCENT ? 0.86f : 0.52f));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = level > mCriticalLevel ?
                    String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level) : mWarningString;
            pctX = mWidth * 0.5f;
            pctY = (mHeight + mTextHeight) * 0.47f;

            c.drawText(pctText, pctX, pctY, mTextPaint);
        } else if (mPowerSaveEnabled) {
            // define the plus shape
            final float pw = mFrame.width() / 2;
            final float pl = mFrame.left + (mFrame.width() - pw) / 2;
            final float pt = mFrame.top + (mFrame.height() - pw) / 2;
            final float pr = mFrame.right - (mFrame.width() - pw) / 2;
            final float pb = mFrame.bottom - (mFrame.height() - pw) / 2;
            if (mPlusFrame.left != pl || mPlusFrame.top != pt
                    || mPlusFrame.right != pr || mPlusFrame.bottom != pb) {
                mPlusFrame.set(pl, pt, pr, pb);
                mPlusPath.reset();
                mPlusPath.moveTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
                for (int i = 2; i < mPlusPoints.length; i += 2) {
                    mPlusPath.lineTo(
                            mPlusFrame.left + mPlusPoints[i] * mPlusFrame.width(),
                            mPlusFrame.top + mPlusPoints[i + 1] * mPlusFrame.height());
                }
                mPlusPath.lineTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
            }

            // Always cut out of the whole shape, and sometimes filled colorError
            mShapePath.op(mPlusPath, Path.Op.DIFFERENCE);
            if (mPowerSaveAsColorError) {
                c.drawPath(mPlusPath, mPlusPaint);
            }
        }
    }

    private void drawRectangle(Canvas c) {
        final int level = mLevel;
        final Rect bounds = getBounds();

        if (level == -1) return;

        float drawFrac = (float) level / 100f;
        final int height = mHeight;
        final int width = (int) (getAspectRatio() * mHeight);
        final int px = (mWidth - width) / 2;
        final int buttonHeight = Math.round(height * mButtonHeightFraction);
        final int left = mPadding.left + bounds.left;
        final int top = bounds.bottom - mPadding.bottom - height;

        mFramePaint.setStrokeWidth(0);
        mFramePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mBatteryPaint.setStrokeWidth(0);
        mBatteryPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mPowersavePaint.setStrokeWidth(mContext.getResources()
                .getDimensionPixelSize(R.dimen.battery_powersave_outline_thickness));

        mFrame.set(left, top, width + left, height + top);
        mFrame.offset(px, 0);

        // button-frame: area above the battery body
        mButtonFrame.set(
                mFrame.left + Math.round(width * 0.28f),
                mFrame.top,
                mFrame.right - Math.round(width * 0.28f),
                mFrame.top + buttonHeight);

        // frame: battery body area
        mFrame.top += buttonHeight;

        // set the battery charging color
        mBatteryPaint.setColor(batteryColorForLevel(level));

        if (level >= FULL) {
            drawFrac = 1f;
        } else if (level <= mCriticalLevel) {
            drawFrac = 0f;
        }

        final float levelTop = drawFrac == 1f ? mButtonFrame.top
                : (mFrame.top + (mFrame.height() * (1f - drawFrac)));

        // define the battery shape
        mShapePath.reset();
        mOutlinePath.reset();
        final float radius = getRadiusRatio() * (mFrame.height() + buttonHeight);
        mShapePath.setFillType(FillType.WINDING);
        mShapePath.addRoundRect(mFrame, radius, radius, Direction.CW);
        mShapePath.addRect(mButtonFrame, Direction.CW);
        mOutlinePath.addRoundRect(mFrame, radius, radius, Direction.CW);
        Path p = new Path();
        p.addRect(mButtonFrame, Direction.CW);
        mOutlinePath.op(p, Op.XOR);

        if (mCharging) {
            // define the bolt shape
            // Shift right by 1px for maximal bolt-goodness
            final float bl = mFrame.left + mFrame.width() / 4f + 1;
            final float bt = mFrame.top + mFrame.height() / 6f;
            final float br = mFrame.right - mFrame.width() / 4f + 1;
            final float bb = mFrame.bottom - mFrame.height() / 10f;
            if (mBoltFrame.left != bl || mBoltFrame.top != bt
                    || mBoltFrame.right != br || mBoltFrame.bottom != bb) {
                mBoltFrame.set(bl, bt, br, bb);
                mBoltPath.reset();
                mBoltPath.moveTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
                for (int i = 2; i < mBoltPoints.length; i += 2) {
                    mBoltPath.lineTo(
                            mBoltFrame.left + mBoltPoints[i] * mBoltFrame.width(),
                            mBoltFrame.top + mBoltPoints[i + 1] * mBoltFrame.height());
                }
                mBoltPath.lineTo(
                        mBoltFrame.left + mBoltPoints[0] * mBoltFrame.width(),
                        mBoltFrame.top + mBoltPoints[1] * mBoltFrame.height());
            }

            float boltPct = (mBoltFrame.bottom - levelTop) / (mBoltFrame.bottom - mBoltFrame.top);
            boltPct = Math.min(Math.max(boltPct, 0), 1);
            if (boltPct <= BOLT_LEVEL_THRESHOLD) {
                // draw the bolt if opaque
                c.drawPath(mBoltPath, mBoltPaint);
            } else {
                // otherwise cut the bolt out of the overall shape
                mShapePath.op(mBoltPath, Path.Op.DIFFERENCE);
            }
        } else if (mPowerSaveEnabled) {
            // define the plus shape
            final float pw = mFrame.width() * 2 / 3;
            final float pl = mFrame.left + (mFrame.width() - pw) / 2;
            final float pt = mFrame.top + (mFrame.height() - pw) / 2;
            final float pr = mFrame.right - (mFrame.width() - pw) / 2;
            final float pb = mFrame.bottom - (mFrame.height() - pw) / 2;
            if (mPlusFrame.left != pl || mPlusFrame.top != pt
                    || mPlusFrame.right != pr || mPlusFrame.bottom != pb) {
                mPlusFrame.set(pl, pt, pr, pb);
                mPlusPath.reset();
                mPlusPath.moveTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
                for (int i = 2; i < mPlusPoints.length; i += 2) {
                    mPlusPath.lineTo(
                            mPlusFrame.left + mPlusPoints[i] * mPlusFrame.width(),
                            mPlusFrame.top + mPlusPoints[i + 1] * mPlusFrame.height());
                }
                mPlusPath.lineTo(
                        mPlusFrame.left + mPlusPoints[0] * mPlusFrame.width(),
                        mPlusFrame.top + mPlusPoints[1] * mPlusFrame.height());
            }

            // Always cut out of the whole shape, and sometimes filled colorError
            mShapePath.op(mPlusPath, Path.Op.DIFFERENCE);
            if (mPowerSaveAsColorError) {
                c.drawPath(mPlusPath, mPlusPaint);
            }
        }

        // compute percentage text
        boolean pctOpaque = false;
        float pctX = 0, pctY = 0;
        String pctText = null;
        if (!mCharging && !mPowerSaveEnabled && level > mCriticalLevel && mShowPercent) {
            mTextPaint.setColor(getColorForLevel(level));
            mTextPaint.setTextSize(height *
                    (SINGLE_DIGIT_PERCENT ? 0.75f
                            : (mLevel == 100 ? 0.38f : 0.5f)));
            mTextHeight = -mTextPaint.getFontMetrics().ascent;
            pctText = String.valueOf(SINGLE_DIGIT_PERCENT ? (level / 10) : level);
            pctX = mWidth * 0.5f + left;
            pctY = (mHeight + mTextHeight) * 0.47f + top;
            pctOpaque = levelTop > pctY;
            if (!pctOpaque) {
                mTextPath.reset();
                mTextPaint.getTextPath(pctText, 0, pctText.length(), pctX, pctY, mTextPath);
                // cut the percentage text out of the overall shape
                mShapePath.op(mTextPath, Path.Op.DIFFERENCE);
            }
        }

        // draw the battery shape background
        c.drawPath(mShapePath, mFramePaint);

        // draw the battery shape, clipped to charging level
        mFrame.top = levelTop;
        c.save();
        c.clipRect(mFrame);
        c.drawPath(mShapePath, mBatteryPaint);
        c.restore();

        if (!mCharging && !mPowerSaveEnabled) {
            if (level <= mCriticalLevel) {
                // draw the warning text
                final float x = mWidth * 0.5f + left;
                final float y = (mHeight + mWarningTextHeight) * 0.48f + top;
                mWarningTextPaint.setColor(mIconTint);
                c.drawText(mWarningString, x, y, mWarningTextPaint);
            } else if (pctOpaque) {
                // draw the percentage text
                c.drawText(pctText, pctX, pctY, mTextPaint);
            }
        }

        // Draw the powersave outline last
        if (!mCharging && mPowerSaveEnabled && mPowerSaveAsColorError) {
            c.drawPath(mOutlinePath, mPowersavePaint);
        }
    }

    // Some stuff required by Drawable.
    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mFramePaint.setColorFilter(colorFilter);
        mBatteryPaint.setColorFilter(colorFilter);
        mWarningTextPaint.setColorFilter(colorFilter);
        mBoltPaint.setColorFilter(colorFilter);
        mPlusPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    public int getCriticalLevel() {
        return mCriticalLevel;
    }

    protected float getAspectRatio() {
        return ASPECT_RATIO;
    }

    protected float getRadiusRatio() {
        return RADIUS_RATIO;
    }
}
