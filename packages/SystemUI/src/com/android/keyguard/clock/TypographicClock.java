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
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.keyguard.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Clock that presents the time in words.
 */
public class TypographicClock extends TextView {

    private static final String ANNOTATION_COLOR = "color";

    private final Resources mResources;
    private final String[] mHours;
    private final String[] mMinutes;
    private int mAccentColor;
    private final Calendar mTime = Calendar.getInstance(TimeZone.getDefault());
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
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
        mResources = context.getResources();
        mHours = mResources.getStringArray(R.array.type_clock_hours);
        mMinutes = mResources.getStringArray(R.array.type_clock_minutes);
        mAccentColor = mResources.getColor(R.color.typeClockAccentColor, null);
    }

    /**
     * Call when the time changes to update the text of the time.
     */
    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        setContentDescription(DateFormat.format(mDescFormat, mTime));
        final int hour = mTime.get(Calendar.HOUR) % 12;
        final int minute = mTime.get(Calendar.MINUTE) % 60;

        // Get the quantity based on the hour for languages like Portuguese and Czech.
        SpannedString typeTemplate = (SpannedString) mResources.getQuantityText(
                R.plurals.type_clock_header, hour);

        // Find the "color" annotation and set the foreground color to the accent color.
        Annotation[] annotations = typeTemplate.getSpans(0, typeTemplate.length(),
                Annotation.class);
        SpannableString spanType = new SpannableString(typeTemplate);
        for (int i = 0; i < annotations.length; i++) {
            Annotation annotation = annotations[i];
            String key = annotation.getValue();
            if (ANNOTATION_COLOR.equals(key)) {
                spanType.setSpan(new ForegroundColorSpan(mAccentColor),
                        spanType.getSpanStart(annotation), spanType.getSpanEnd(annotation),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        setText(TextUtils.expandTemplate(spanType, mHours[hour], mMinutes[minute]));
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
     * Sets the accent color used on the clock face.
     */
    public void setClockColor(int color) {
        mAccentColor = color;
        onTimeChanged();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mTime.setTimeZone(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }
}
