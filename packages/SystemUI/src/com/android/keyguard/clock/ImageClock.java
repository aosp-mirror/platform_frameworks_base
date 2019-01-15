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
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.keyguard.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Clock composed of two images that rotate with the time.
 *
 * The images are the clock hands. ImageClock expects two child ImageViews
 * with ids hour_hand and minute_hand.
 */
public class ImageClock extends FrameLayout {

    private ImageView mHourHand;
    private ImageView mMinuteHand;
    private Calendar mTime;
    private String mDescFormat;
    private TimeZone mTimeZone;

    public ImageClock(Context context) {
        this(context, null);
    }

    public ImageClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageClock(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTime = Calendar.getInstance();
        mDescFormat = ((SimpleDateFormat) DateFormat.getTimeFormat(context)).toLocalizedPattern();
    }

    /**
     * Call when the time changes to update the rotation of the clock hands.
     */
    public void onTimeChanged() {
        mTime.setTimeInMillis(System.currentTimeMillis());
        final float hourAngle = mTime.get(Calendar.HOUR) * 30f;
        mHourHand.setRotation(hourAngle);
        final float minuteAngle = mTime.get(Calendar.MINUTE) * 6f;
        mMinuteHand.setRotation(minuteAngle);
        setContentDescription(DateFormat.format(mDescFormat, mTime));
        invalidate();
    }

    /**
     * Call when the time zone has changed to update clock hands.
     *
     * @param timeZone The updated time zone that will be used.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        mTimeZone = timeZone;
        mTime.setTimeZone(timeZone);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHourHand = findViewById(R.id.hour_hand);
        mMinuteHand = findViewById(R.id.minute_hand);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mTime = Calendar.getInstance(mTimeZone != null ? mTimeZone : TimeZone.getDefault());
        onTimeChanged();
    }
}
