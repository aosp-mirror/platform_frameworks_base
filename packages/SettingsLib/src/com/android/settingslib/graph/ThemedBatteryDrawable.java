/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Path.Op;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.PathParser;
import android.util.TypedValue;

import com.android.settingslib.R;
import com.android.settingslib.Utils;

public class ThemedBatteryDrawable extends Drawable {
    private final Path boltPath = new Path();
    private boolean charging;
    private int[] colorLevels;
    private final Context mContext;
    private int criticalLevel;
    private boolean dualTone;
    private int fillColor = Color.MAGENTA;
    private final Path fillMask = new Path();
    private final RectF fillRect = new RectF();
    private int intrinsicHeight;
    private int intrinsicWidth;
    private int levelColor = Color.MAGENTA;
    private final Path levelPath = new Path();
    private final RectF levelRect = new RectF();
    private final Rect padding = new Rect();
    private final Path errorPerimeterPath = new Path();
    private final Path perimeterPath = new Path();
    private boolean powerSaveEnabled;
    private final Matrix scaleMatrix = new Matrix();
    private final Path scaledBolt = new Path();
    private final Path scaledFill = new Path();
    private final Path scaledErrorPerimeter = new Path();
    private final Path scaledPerimeter = new Path();

    // Plus sign (used for power save mode)
    private final Path plusPath = new Path();
    private final Path scaledPlus = new Path();

    // To implement hysteresis, keep track of the need to invert the interior icon of the battery
    private boolean invertFillIcon;

    private final Path unifiedPath = new Path();
    private final Path textPath = new Path();
    private final RectF iconRect = new RectF();

    private final Paint dualToneBackgroundFill;
    private final Paint fillColorStrokePaint;
    private final Paint fillColorStrokeProtection;
    private final Paint fillPaint;
    private final Paint textPaint;
    private final Paint errorPaint;

    private final float mWidthDp = 12f;
    private final float mHeightDp = 20f;

    private int mMeterStyle;
    private int level;
    private boolean showPercent;

    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    public void setAlpha(int i) {
    }

    public ThemedBatteryDrawable(Context context, int frameColor) {
        mContext = context;
        float f = mContext.getResources().getDisplayMetrics().density;
        intrinsicHeight = (int) (mHeightDp * f);
        intrinsicWidth = (int) (mWidthDp * f);
        Resources res = mContext.getResources();

        TypedArray levels = res.obtainTypedArray(R.array.batterymeter_color_levels);
        TypedArray colors = res.obtainTypedArray(R.array.batterymeter_color_values);

        final int N = levels.length();
        colorLevels = new int[2 * N];
        for (int i = 0; i < N; i++) {
            colorLevels[2 * i] = levels.getInt(i, 0);
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = Utils.getColorAttrDefaultColor(mContext, colors.getThemeAttributeId(i, 0));
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0);
            }
        }
        levels.recycle();
        colors.recycle();
        
        setCriticalLevel(res.getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel));

        dualToneBackgroundFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        dualToneBackgroundFill.setColor(frameColor);
        dualToneBackgroundFill.setAlpha(255);
        dualToneBackgroundFill.setDither(true);
        dualToneBackgroundFill.setStrokeWidth(0f);
        dualToneBackgroundFill.setStyle(Style.FILL_AND_STROKE);

        fillColorStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillColorStrokePaint.setColor(frameColor);
        fillColorStrokePaint.setDither(true);
        fillColorStrokePaint.setStrokeWidth(5f);
        fillColorStrokePaint.setStyle(Style.STROKE);
        fillColorStrokePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        fillColorStrokePaint.setStrokeMiter(5f);
        fillColorStrokePaint.setStrokeJoin(Join.ROUND);

        fillColorStrokeProtection = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillColorStrokeProtection.setDither(true);
        fillColorStrokeProtection.setStrokeWidth(5f);
        fillColorStrokeProtection.setStyle(Style.STROKE);
        fillColorStrokeProtection.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        fillColorStrokeProtection.setStrokeMiter(5f);
        fillColorStrokeProtection.setStrokeJoin(Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(frameColor);
        fillPaint.setAlpha(255);
        fillPaint.setDither(true);
        fillPaint.setStrokeWidth(0f);
        fillPaint.setStyle(Style.FILL_AND_STROKE);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);

        errorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        errorPaint.setColor(Utils.getColorStateListDefaultColor(mContext, R.color.batterymeter_plus_color));
        errorPaint.setAlpha(255);
        errorPaint.setAlpha(255);
        errorPaint.setDither(true);
        errorPaint.setStrokeWidth(0f);
        errorPaint.setStyle(Style.FILL_AND_STROKE);
        errorPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        loadPaths();
    }

    public void setCriticalLevel(int i) {
        criticalLevel = i;
    }

    public int getCriticalLevel() {
        return criticalLevel;
    }

    public final void setCharging(boolean val) {
        if (charging != val) {
            charging = val;
            postInvalidate();
        }
    }

    public boolean getCharging() {
        return charging;
    }

    public final boolean getPowerSaveEnabled() {
        return powerSaveEnabled;
    }

    public final void setPowerSaveEnabled(boolean val) {
        if (powerSaveEnabled != val) {
            powerSaveEnabled = val;
            postInvalidate();
        }
    }

    public void setShowPercent(boolean show) {
        if (showPercent != show) {
            showPercent = show;
            postInvalidate();
        }
    }

    // an approximation of View.postInvalidate()
    protected void postInvalidate() {
        unscheduleSelf(this::invalidateSelf);
        scheduleSelf(this::invalidateSelf, 0);
    }

    public void draw(Canvas canvas) {
        boolean opaqueBolt = level <= 30;
        boolean drawText;
        float pctX = 0, pctY = 0, textHeight;
        String pctText = null;
        boolean pctOpaque;
        if (!charging && !powerSaveEnabled && showPercent) {
            float baseHeight = (dualTone ? iconRect : fillRect).height();
            textPaint.setColor(getColorForLevel(level));
            textPaint.setTextSize(baseHeight * (level == 100 ? 0.38f : 0.5f));
            textHeight = -textPaint.getFontMetrics().ascent;
            pctText = String.valueOf(level);
            pctX = fillRect.width() * 0.5f + fillRect.left;
            pctY = (fillRect.height() + textHeight) * 0.47f + fillRect.top;
            textPath.reset();
            textPaint.getTextPath(pctText, 0, pctText.length(), pctX, pctY, textPath);
            drawText = true;
        } else {
            drawText = false;
        }

        unifiedPath.reset();
        levelPath.reset();
        levelRect.set(fillRect);
        float fillFraction = ((float) level) / 100.0f;
        float levelTop;
        if (level >= 95) {
            levelTop = fillRect.top;
        } else {
            RectF rectF = fillRect;
            levelTop = (rectF.height() * (((float) 1) - fillFraction)) + rectF.top;
        }
        pctOpaque = levelTop > pctY;
        levelRect.top = (float) Math.floor(dualTone ? fillRect.top : levelTop);
        levelPath.addRect(levelRect, Direction.CCW);
        unifiedPath.addPath(scaledPerimeter);
        unifiedPath.op(levelPath, Op.UNION);
        fillPaint.setColor(levelColor);
        if (charging) {
            if (!dualTone || !opaqueBolt) {
                unifiedPath.op(scaledBolt, Op.DIFFERENCE);
            }
            if (!dualTone && !invertFillIcon) {
                canvas.drawPath(scaledBolt, fillPaint);
            }
        } else if (drawText) {
            if (!dualTone || !pctOpaque) {
                unifiedPath.op(textPath, Op.DIFFERENCE);
            }
            if (!dualTone && !invertFillIcon) {
                canvas.drawPath(textPath, fillPaint);
            }
        }
        if (dualTone) {
            canvas.drawPath(unifiedPath, dualToneBackgroundFill);
            canvas.save();
            float clipTop = getBounds().bottom - getBounds().height() * fillFraction;
            canvas.clipRect(0f, clipTop, (float) getBounds().right, (float) getBounds().bottom);
            canvas.drawPath(unifiedPath, fillPaint);
            canvas.restore();
            if (charging && opaqueBolt) {
                canvas.drawPath(scaledBolt, fillPaint);
            } else if (drawText && pctOpaque) {
                canvas.drawPath(textPath, fillPaint);
            }
            canvas.restore();
        } else {
            // Non dual-tone means we draw the perimeter (with the level fill), and potentially
            // draw the fill again with a critical color
            fillPaint.setColor(fillColor);
            canvas.drawPath(unifiedPath, fillPaint);
            fillPaint.setColor(levelColor);

            // Show colorError below this level
            if (level <= 15 && !charging) {
                canvas.save();
                canvas.clipPath(scaledFill);
                canvas.drawPath(levelPath, fillPaint);
                canvas.restore();
            }
        }

        if (charging) {
            canvas.clipOutPath(scaledBolt);
            if (invertFillIcon) {
                canvas.drawPath(scaledBolt, fillColorStrokePaint);
            } else {
                canvas.drawPath(scaledBolt, fillColorStrokeProtection);
            }
        } else if (powerSaveEnabled) {
            // If power save is enabled draw the perimeter path with colorError
            canvas.drawPath(scaledErrorPerimeter, errorPaint);
            // And draw the plus sign on top of the fill
            canvas.drawPath(scaledPlus, errorPaint);
        } else if (drawText) {
            canvas.clipOutPath(textPath);
            if (invertFillIcon) {
                canvas.drawPath(textPath, fillColorStrokePaint);
            } else {
                canvas.drawPath(textPath, fillColorStrokeProtection);
            }
        }
        canvas.restore();
    }

    public int getBatteryLevel() {
        return level;
    }

    protected int batteryColorForLevel(int level) {
        return (charging || powerSaveEnabled)
                ? fillColor
                : getColorForLevel(level);
    }

    private final int getColorForLevel(int percent) {
        int thresh, color = 0;
        for (int i = 0; i < colorLevels.length; i += 2) {
            thresh = colorLevels[i];
            color = colorLevels[i + 1];
            if (percent <= thresh) {

                // Respect tinting for "normal" level
                if (i == colorLevels.length - 2) {
                    return fillColor;
                } else {
                    return color;
                }
            }
        }
        return color;
    }

    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        fillColorStrokePaint.setColorFilter(colorFilter);
        dualToneBackgroundFill.setColorFilter(colorFilter);
    }

    public int getIntrinsicHeight() {
        return intrinsicHeight;
    }

    public int getIntrinsicWidth() {
        return intrinsicWidth;
    }

    public void setBatteryLevel(int val) {
        if (level != val) {
            level = val;
            invertFillIcon = val >= 67 ? true : val <= 33 ? false : invertFillIcon;
            levelColor = batteryColorForLevel(level);
            postInvalidate();
        }
    }

    protected void onBoundsChange(Rect rect) {
        super.onBoundsChange(rect);
        updateSize();
    }

    public void setColors(int fgColor, int bgColor, int singleToneColor) {
        fillColor = dualTone ? fgColor : singleToneColor;
        fillPaint.setColor(fillColor);
        fillColorStrokePaint.setColor(fillColor);
        dualToneBackgroundFill.setColor(bgColor);
        levelColor = batteryColorForLevel(level);
        invalidateSelf();
    }

    private final void updateSize() {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            scaleMatrix.setScale(1.0f, 1.0f);
        } else {
            scaleMatrix.setScale(bounds.right / mWidthDp, bounds.bottom / mHeightDp);
        }
        perimeterPath.transform(scaleMatrix, scaledPerimeter);
        errorPerimeterPath.transform(scaleMatrix, scaledErrorPerimeter);
        fillMask.transform(scaleMatrix, scaledFill);
        scaledFill.computeBounds(fillRect, true);
        boltPath.transform(scaleMatrix, scaledBolt);
        plusPath.transform(scaleMatrix, scaledPlus);
        float max = Math.max(bounds.right / mWidthDp * 3f, 6f);
        fillColorStrokePaint.setStrokeWidth(max);
        fillColorStrokeProtection.setStrokeWidth(max);
        iconRect.set(bounds);
    }

    private final void loadPaths() {
        String pathString = mContext.getResources().getString(
                com.android.internal.R.string.config_batterymeterPerimeterPath);
        perimeterPath.set(PathParser.createPathFromPathData(pathString));
        perimeterPath.computeBounds(new RectF(), true);

        String errorPathString = mContext.getResources().getString(
                com.android.internal.R.string.config_batterymeterErrorPerimeterPath);
        errorPerimeterPath.set(PathParser.createPathFromPathData(errorPathString));
        errorPerimeterPath.computeBounds(new RectF(), true);

        String fillMaskString = mContext.getResources().getString(
                com.android.internal.R.string.config_batterymeterFillMask);
        fillMask.set(PathParser.createPathFromPathData(fillMaskString));
        fillMask.computeBounds(fillRect, true);

        String boltPathString = mContext.getResources().getString(
                com.android.internal.R.string.config_batterymeterBoltPath);
        boltPath.set(PathParser.createPathFromPathData(boltPathString));

        String plusPathString = mContext.getResources().getString(
                com.android.internal.R.string.config_batterymeterPowersavePath);
        plusPath.set(PathParser.createPathFromPathData(plusPathString));

        dualTone = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_batterymeterDualTone);
    }
}
