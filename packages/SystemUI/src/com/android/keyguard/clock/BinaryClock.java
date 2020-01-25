/*
 *  Copyright (C) 2018-2019 The OmniROM Project
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

package com.android.keyguard.clock;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class BinaryClock extends View {
    private static final String TAG = "BinaryClock";
    private final Calendar mCalendar = Calendar.getInstance(TimeZone.getDefault());
    private int mMinutes;
    private int mHour;
    private Paint mDotPaint;
    private Paint mEmptyDotPaint;
    private Paint mAmbientDotPaint;
    private Paint mAmbienEmptyDotPaint;
    private boolean mIsAmbientDisplay;
    private int mDotSize;
    private int[][] mDots = new int[4][4];
    private TimeZone mTimeZone;
    private String mDescFormat;

    public BinaryClock(Context context) {
        this(context, null);
    }

    public BinaryClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BinaryClock(Context context, AttributeSet attrs,
                       int defStyle) {
        super(context, attrs, defStyle);
        Resources r = context.getResources();

        mDotPaint = new Paint();
        mDotPaint.setAntiAlias(true);
        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(r.getColor(R.color.binary_clock_dot_color));

        mEmptyDotPaint = new Paint();
        mEmptyDotPaint.setAntiAlias(true);
        mEmptyDotPaint.setStyle(Paint.Style.STROKE);
        mEmptyDotPaint.setColor(r.getColor(R.color.binary_clock_empty_dot_color));

        mAmbientDotPaint = new Paint();
        mAmbientDotPaint.setAntiAlias(true);
        mAmbientDotPaint.setStyle(Paint.Style.FILL);
        mAmbientDotPaint.setColor(r.getColor(R.color.binary_clock_ambient_dot_color));

        mAmbienEmptyDotPaint = new Paint();
        mAmbienEmptyDotPaint.setAntiAlias(true);
        mAmbienEmptyDotPaint.setStyle(Paint.Style.STROKE);
        mAmbienEmptyDotPaint.setColor(r.getColor(R.color.binary_clock_ambient_empty_dot_color));

        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();

        onDensityOrFontScaleChanged();
    }

    public void onDensityOrFontScaleChanged() {
        Resources r = getContext().getResources();
        mDotSize = r.getDimensionPixelSize(R.dimen.binary_clock_dot_size);
        mDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mEmptyDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mAmbientDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
        mAmbienEmptyDotPaint.setStrokeWidth(r.getDimensionPixelSize(R.dimen.binary_clock_stroke_width));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCalendar.setTimeZone(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int availableWidth = getWidth();
        int cellWidth = availableWidth / 4;
        int availableHeight = getHeight();
        int cellHeight = availableHeight / 4;

        int yLine = cellHeight / 2;

        for (int y = 3; y >= 0 ; y--) {
            int xLine = cellWidth / 2;
            for (int x = 0; x < 4; x++) {
                if (y >= 2 && x == 0) {
                    xLine += cellWidth;
                    continue;
                }
                if (mDots[x][y] == 1) {
                    canvas.drawCircle(xLine, yLine, mDotSize, mIsAmbientDisplay ? mAmbientDotPaint : mDotPaint);
                } else {
                    canvas.drawCircle(xLine, yLine, mDotSize, mIsAmbientDisplay ? mAmbienEmptyDotPaint : mEmptyDotPaint);
                }
                xLine += cellWidth;
            }
            yLine += cellHeight;
        }
    }


    private void calculateDotMatrix() {
        int hour0 = (int) (mHour >= 10 ? mHour / 10 : 0);
        int hour1 = (int) (mHour - hour0 * 10);
        int minute0 = (int) (mMinutes >= 10 ? mMinutes / 10 : 0);
        int minute1 = (int) (mMinutes - minute0 * 10);

        mDots = new int[4][4];
        if (hour0 != 0) {
            String hour0Bin = Integer.toBinaryString(hour0);
            for (int i = 0; i < hour0Bin.length(); i++) {
                mDots[0][hour0Bin.length() - 1 - i] = hour0Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (hour1 != 0) {
            String hour1Bin = Integer.toBinaryString(hour1);
            for (int i = 0; i < hour1Bin.length(); i++) {
                mDots[1][hour1Bin.length() - 1 - i] = hour1Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (minute0 != 0) {
            String minute0Bin = Integer.toBinaryString(minute0);
            for (int i = 0; i < minute0Bin.length(); i++) {
                mDots[2][minute0Bin.length() - 1 - i] = minute0Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
        if (minute1 != 0) {
            String minute1Bin = Integer.toBinaryString(minute1);
            for (int i = 0; i < minute1Bin.length(); i++) {
                mDots[3][minute1Bin.length() - 1 - i] = minute1Bin.charAt(i) == '1' ? 1 : 0;
            }
        }
    }

    public void onTimeChanged() {
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        final boolean is24 = DateFormat.is24HourFormat(getContext(), ActivityManager.getCurrentUser());
        mHour = mCalendar.get(is24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        mMinutes = mCalendar.get(Calendar.MINUTE);
        setContentDescription(DateFormat.format(mDescFormat, mCalendar));

        calculateDotMatrix();
    }

    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mCalendar.setTimeZone(timeZone);
    }

    public void setDark(boolean dark) {
        if (mIsAmbientDisplay != dark) {
            mIsAmbientDisplay = dark;
            invalidate();
        }
    }

    public void setTintColor(int color) {
        mDotPaint.setColor(color);
        mEmptyDotPaint.setColor(color);
        invalidate();
    }

    public void refreshTime() {
        onTimeChanged();
        invalidate();
    }
}
