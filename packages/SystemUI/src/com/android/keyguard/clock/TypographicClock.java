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
import android.content.res.Resources;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.keyguard.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Clock that presents the time in words.
 */
public class TypographicClock extends LinearLayout {

    private final String[] mHours;
    private final String[] mMinutes;
    private TextView mHeaderText;
    private TextView mHourText;
    private TextView mMinuteText;
    private Calendar mTime;
    private String mDescFormat;
    private TimeZone mTimeZone;

    public TypographicClock(Context context) {
        this(context, null);
    }

    public TypographicClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TypographicClock(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTime = Calendar.getInstance();
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
        Resources res = context.getResources();
        mHours = res.getStringArray(R.array.type_clock_hours);
        mMinutes = res.getStringArray(R.array.type_clock_minutes);
    }

    /**
     * Call when the time changes to update the text of the time.
     */
    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setContentDescription(DateFormat.format(mDescFormat, mTime));
        final int hour = mTime.get(Calendar.HOUR);
        mHourText.setText(mHours[hour % 12]);
        final int minute = mTime.get(Calendar.MINUTE);
        mMinuteText.setText(mMinutes[minute % 60]);
        invalidate();
    }

    /**
     * Call when the time zone has changed to update clock time.
     *
     * @param timeZone The updated time zone that will be used.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
    }

    /**
     * Set the color of the text used to display the time.
     *
     * This is necessary when the wallpaper shown behind the clock on the
     * lock screen changes.
     */
    public void setTextColor(int color) {
        mHourText.setTextColor(color);
        mMinuteText.setTextColor(color);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeaderText = findViewById(R.id.header);
        mHourText = findViewById(R.id.hour);
        mMinuteText = findViewById(R.id.minute);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mTime = Calendar.getInstance(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }
}
