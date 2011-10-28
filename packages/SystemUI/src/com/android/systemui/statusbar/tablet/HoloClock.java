/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import com.android.systemui.R;

public class HoloClock extends FrameLayout {
    private boolean mAttached;
    private Calendar mCalendar;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;

    private static final String FONT_DIR = "/system/fonts/";
    private static final String CLOCK_FONT = FONT_DIR + "AndroidClock_Solid.ttf";
    private static final String CLOCK_FG_FONT = FONT_DIR + "AndroidClock.ttf";
    private static final String CLOCK_BG_FONT = FONT_DIR + "AndroidClock_Highlight.ttf";

    private static Typeface sBackgroundType, sForegroundType, sSolidType;
    private TextView mSolidText, mBgText, mFgText;

    public HoloClock(Context context) {
        this(context, null);
    }

    public HoloClock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HoloClock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (sSolidType == null) {
            sSolidType = Typeface.createFromFile(CLOCK_FONT);
            sBackgroundType = Typeface.createFromFile(CLOCK_BG_FONT);
            sForegroundType = Typeface.createFromFile(CLOCK_FG_FONT);
        }
        mBgText = (TextView) findViewById(R.id.time_bg);
        if (mBgText != null) {
            mBgText.setTypeface(sBackgroundType);
            mBgText.setVisibility(View.INVISIBLE);
        }

        mFgText = (TextView) findViewById(R.id.time_fg);
        if (mFgText != null) {
            mFgText.setTypeface(sForegroundType);
        }
        mSolidText = (TextView) findViewById(R.id.time_solid);
        if (mSolidText != null) {
            mSolidText.setTypeface(sSolidType);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);

            getContext().registerReceiver(mIntentReceiver, filter, null, getHandler());
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());

        // Make sure we update to the current time
        updateClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                if (mClockFormat != null) {
                    mClockFormat.setTimeZone(mCalendar.getTimeZone());
                }
            }
            updateClock();
        }
    };

    final void updateClock() {
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        CharSequence txt = getTimeText();
        if (mBgText != null) mBgText.setText(txt);
        if (mFgText != null) mFgText.setText(txt);
        if (mSolidText != null) mSolidText.setText(txt);
    }

    private final CharSequence getTimeText() {
        Context context = getContext();
        int res = DateFormat.is24HourFormat(context)
            ? com.android.internal.R.string.twenty_four_hour_time_format
            : com.android.internal.R.string.twelve_hour_time_format;

        SimpleDateFormat sdf;
        String format = context.getString(res);
        if (!format.equals(mClockFormatString)) {
            // we don't want AM/PM showing up in our statusbar, even in 12h mode
            format = format.replaceAll("a", "").trim();
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        String result = sdf.format(mCalendar.getTime());
        return result;
    }
}

