/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.widget;

import com.android.internal.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.DateFormatSymbols;
import java.util.Calendar;

/**
 * Displays the time
 */
public class DigitalClock extends RelativeLayout {

    private static final String SYSTEM = "/system/fonts/";
    private static final String SYSTEM_FONT_TIME_BACKGROUND = SYSTEM + "AndroidClock.ttf";
    private static final String SYSTEM_FONT_TIME_FOREGROUND = SYSTEM + "AndroidClock_Highlight.ttf";
    private final static String M12 = "h:mm";
    private final static String M24 = "kk:mm";

    private Calendar mCalendar;
    private String mFormat;
    private TextView mTimeDisplayBackground;
    private TextView mTimeDisplayForeground;
    private AmPm mAmPm;
    private ContentObserver mFormatChangeObserver;
    private int mAttached = 0; // for debugging - tells us whether attach/detach is unbalanced

    /* called by system on minute ticks */
    private final Handler mHandler = new Handler();
    private BroadcastReceiver mIntentReceiver;

    private static final Typeface sBackgroundFont;
    private static final Typeface sForegroundFont;

    static {
        sBackgroundFont = Typeface.createFromFile(SYSTEM_FONT_TIME_BACKGROUND);
        sForegroundFont = Typeface.createFromFile(SYSTEM_FONT_TIME_FOREGROUND);
    }

    private static class TimeChangedReceiver extends BroadcastReceiver {
        private WeakReference<DigitalClock> mClock;
        private Context mContext;

        public TimeChangedReceiver(DigitalClock clock) {
            mClock = new WeakReference<DigitalClock>(clock);
            mContext = clock.getContext();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // Post a runnable to avoid blocking the broadcast.
            final boolean timezoneChanged =
                    intent.getAction().equals(Intent.ACTION_TIMEZONE_CHANGED);
            final DigitalClock clock = mClock.get();
            if (clock != null) {
                clock.mHandler.post(new Runnable() {
                    public void run() {
                        if (timezoneChanged) {
                            clock.mCalendar = Calendar.getInstance();
                        }
                        clock.updateTime();
                    }
                });
            } else {
                try {
                    mContext.unregisterReceiver(this);
                } catch (RuntimeException e) {
                    // Shouldn't happen
                }
            }
        }
    };

    static class AmPm {
        private TextView mAmPmTextView;
        private String mAmString, mPmString;

        AmPm(View parent, Typeface tf) {
            // No longer used, uncomment if we decide to use AM/PM indicator again
            // mAmPmTextView = (TextView) parent.findViewById(R.id.am_pm);
            if (mAmPmTextView != null && tf != null) {
                mAmPmTextView.setTypeface(tf);
            }

            String[] ampm = new DateFormatSymbols().getAmPmStrings();
            mAmString = ampm[0];
            mPmString = ampm[1];
        }

        void setShowAmPm(boolean show) {
            if (mAmPmTextView != null) {
                mAmPmTextView.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }

        void setIsMorning(boolean isMorning) {
            if (mAmPmTextView != null) {
                mAmPmTextView.setText(isMorning ? mAmString : mPmString);
            }
        }
    }

    private static class FormatChangeObserver extends ContentObserver {
        private WeakReference<DigitalClock> mClock;
        private Context mContext;
        public FormatChangeObserver(DigitalClock clock) {
            super(new Handler());
            mClock = new WeakReference<DigitalClock>(clock);
            mContext = clock.getContext();
        }
        @Override
        public void onChange(boolean selfChange) {
            DigitalClock digitalClock = mClock.get();
            if (digitalClock != null) {
                digitalClock.setDateFormat();
                digitalClock.updateTime();
            } else {
                try {
                    mContext.getContentResolver().unregisterContentObserver(this);
                } catch (RuntimeException e) {
                    // Shouldn't happen
                }
            }
        }
    }

    public DigitalClock(Context context) {
        this(context, null);
    }

    public DigitalClock(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        /* The time display consists of two tones. That's why we have two overlapping text views. */
        mTimeDisplayBackground = (TextView) findViewById(R.id.timeDisplayBackground);
        mTimeDisplayBackground.setTypeface(sBackgroundFont);
        mTimeDisplayBackground.setVisibility(View.INVISIBLE);

        mTimeDisplayForeground = (TextView) findViewById(R.id.timeDisplayForeground);
        mTimeDisplayForeground.setTypeface(sForegroundFont);
        mAmPm = new AmPm(this, null);
        mCalendar = Calendar.getInstance();

        setDateFormat();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAttached++;

        /* monitor time ticks, time changed, timezone */
        if (mIntentReceiver == null) {
            mIntentReceiver = new TimeChangedReceiver(this);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter);
        }

        /* monitor 12/24-hour display preference */
        if (mFormatChangeObserver == null) {
            mFormatChangeObserver = new FormatChangeObserver(this);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, mFormatChangeObserver);
        }

        updateTime();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mAttached--;

        if (mIntentReceiver != null) {
            mContext.unregisterReceiver(mIntentReceiver);
        }
        if (mFormatChangeObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(
                    mFormatChangeObserver);
        }

        mFormatChangeObserver = null;
        mIntentReceiver = null;
    }

    void updateTime(Calendar c) {
        mCalendar = c;
        updateTime();
    }

    private void updateTime() {
        mCalendar.setTimeInMillis(System.currentTimeMillis());

        CharSequence newTime = DateFormat.format(mFormat, mCalendar);
        mTimeDisplayBackground.setText(newTime);
        mTimeDisplayForeground.setText(newTime);
        mAmPm.setIsMorning(mCalendar.get(Calendar.AM_PM) == 0);
    }

    private void setDateFormat() {
        mFormat = android.text.format.DateFormat.is24HourFormat(getContext())
            ? M24 : M12;
        mAmPm.setShowAmPm(mFormat.equals(M12));
    }
}
