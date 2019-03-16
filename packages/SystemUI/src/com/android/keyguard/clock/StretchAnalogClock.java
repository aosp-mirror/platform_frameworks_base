/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Analog clock where the minute hand extends off of the screen.
 */
public class StretchAnalogClock extends View {

    private static final int DEFAULT_COLOR = Color.parseColor("#F5C983");
    private static final float HOUR_STROKE_WIDTH = 60f;
    private static final float MINUTE_STROKE_WIDTH = 20f;
    private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 80f;

    private final Paint mHourPaint = new Paint();
    private final Paint mMinutePaint = new Paint();
    private Calendar mTime = Calendar.getInstance(TimeZone.getDefault());
    private TimeZone mTimeZone;

    public StretchAnalogClock(Context context) {
        this(context, null);
    }

    public StretchAnalogClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StretchAnalogClock(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public StretchAnalogClock(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    /**
     * Call when the time changes to update the clock hands.
     */
    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        invalidate();
    }

    /**
     * Call when the time zone has changed to update clock hands.
     *
     * @param timeZone The updated time zone that will be used.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTime.setTimeZone(timeZone);
    }

    /**
     * Set the colors to use on the clock face.
     * @param dark Darker color obtained from color palette.
     * @param light Lighter color obtained from color palette.
     */
    public void setClockColor(int dark, int light) {
        mHourPaint.setColor(dark);
        invalidate();
    }

    private void init() {
        mHourPaint.setColor(DEFAULT_COLOR);
        mHourPaint.setStrokeWidth(HOUR_STROKE_WIDTH);
        mHourPaint.setAntiAlias(true);
        mHourPaint.setStrokeCap(Paint.Cap.ROUND);

        mMinutePaint.setColor(Color.WHITE);
        mMinutePaint.setStrokeWidth(MINUTE_STROKE_WIDTH);
        mMinutePaint.setAntiAlias(true);
        mMinutePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float centerX = getWidth() / 2f;
        final float centerY = getHeight() / 2f;

        final float minutesRotation = mTime.get(Calendar.MINUTE) * 6f;
        final float hoursRotation = mTime.get(Calendar.HOUR) * 30
                + mTime.get(Calendar.MINUTE) * 0.5f;

        // Compute length of clock hands. Hour hand is 60% the length from center to edge
        // and minute hand is twice the length to make sure it extends past screen edge.
        double sMinuteHandLengthFactor = Math.sin(2d * Math.PI * minutesRotation / 360d);
        float sMinuteHandLength = (float) (2d * (centerY + (centerX - centerY)
                * sMinuteHandLengthFactor * sMinuteHandLengthFactor));
        double sHourHandLengthFactor = Math.sin(2d * Math.PI * hoursRotation / 360d);
        float sHourHandLength = (float) (0.6d * (centerY + (centerX - centerY)
                * sHourHandLengthFactor * sHourHandLengthFactor));

        canvas.save();

        canvas.rotate(minutesRotation, centerX, centerY);
        canvas.drawLine(
                centerX,
                centerY + CENTER_GAP_AND_CIRCLE_RADIUS,
                centerX,
                centerY - sMinuteHandLength,
                mMinutePaint);

        canvas.rotate(hoursRotation - minutesRotation, centerX, centerY);
        canvas.drawLine(
                centerX,
                centerY + CENTER_GAP_AND_CIRCLE_RADIUS,
                centerX,
                centerY - sHourHandLength,
                mHourPaint);

        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mTime.setTimeZone(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }
}
