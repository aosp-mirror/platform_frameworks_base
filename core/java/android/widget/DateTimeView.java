/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.RemoteViews.RemoteView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

//
// TODO
// - listen for the next threshold time to update the view.
// - listen for date format pref changed
// - put the AM/PM in a smaller font
//

/**
 * Displays a given time in a convenient human-readable foramt.
 *
 * @hide
 */
@RemoteView
public class DateTimeView extends TextView {
    private static final String TAG = "DateTimeView";

    private static final long TWELVE_HOURS_IN_MINUTES = 12 * 60;
    private static final long TWENTY_FOUR_HOURS_IN_MILLIS = 24 * 60 * 60 * 1000;

    private static final int SHOW_TIME = 0;
    private static final int SHOW_MONTH_DAY_YEAR = 1;

    Date mTime;
    long mTimeMillis;

    int mLastDisplay = -1;
    DateFormat mLastFormat;

    private boolean mAttachedToWindow;
    private long mUpdateTimeMillis;

    public DateTimeView(Context context) {
        super(context);
    }

    public DateTimeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerReceivers();
        mAttachedToWindow = true;
    }
        
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unregisterReceivers();
        mAttachedToWindow = false;
    }

    @android.view.RemotableViewMethod
    public void setTime(long time) {
        Time t = new Time();
        t.set(time);
        t.second = 0;
        mTimeMillis = t.toMillis(false);
        mTime = new Date(t.year-1900, t.month, t.monthDay, t.hour, t.minute, 0);
        update();
    }

    void update() {
        if (mTime == null) {
            return;
        }

        long start = System.nanoTime();

        int display;
        Date time = mTime;

        Time t = new Time();
        t.set(mTimeMillis);
        t.second = 0;

        t.hour -= 12;
        long twelveHoursBefore = t.toMillis(false);
        t.hour += 12;
        long twelveHoursAfter = t.toMillis(false);
        t.hour = 0;
        t.minute = 0;
        long midnightBefore = t.toMillis(false);
        t.monthDay++;
        long midnightAfter = t.toMillis(false);

        long nowMillis = System.currentTimeMillis();
        t.set(nowMillis);
        t.second = 0;
        nowMillis = t.normalize(false);

        // Choose the display mode
        choose_display: {
            if ((nowMillis >= midnightBefore && nowMillis < midnightAfter)
                    || (nowMillis >= twelveHoursBefore && nowMillis < twelveHoursAfter)) {
                display = SHOW_TIME;
                break choose_display;
            }
            // Else, show month day and year.
            display = SHOW_MONTH_DAY_YEAR;
            break choose_display;
        }

        // Choose the format
        DateFormat format;
        if (display == mLastDisplay && mLastFormat != null) {
            // use cached format
            format = mLastFormat;
        } else {
            switch (display) {
                case SHOW_TIME:
                    format = getTimeFormat();
                    break;
                case SHOW_MONTH_DAY_YEAR:
                    format = getDateFormat();
                    break;
                default:
                    throw new RuntimeException("unknown display value: " + display);
            }
            mLastFormat = format;
        }

        // Set the text
        String text = format.format(mTime);
        setText(text);

        // Schedule the next update
        if (display == SHOW_TIME) {
            // Currently showing the time, update at the later of twelve hours after or midnight.
            mUpdateTimeMillis = twelveHoursAfter > midnightAfter ? twelveHoursAfter : midnightAfter;
        } else {
            // Currently showing the date
            if (mTimeMillis < nowMillis) {
                // If the time is in the past, don't schedule an update
                mUpdateTimeMillis = 0;
            } else {
                // If hte time is in the future, schedule one at the earlier of twelve hours
                // before or midnight before.
                mUpdateTimeMillis = twelveHoursBefore < midnightBefore
                        ? twelveHoursBefore : midnightBefore;
            }
        }
        if (false) {
            Log.d(TAG, "update needed for '" + time + "' at '" + new Date(mUpdateTimeMillis)
                    + "' - text=" + text);
        }

        long finish = System.nanoTime();
    }

    private DateFormat getTimeFormat() {
        return android.text.format.DateFormat.getTimeFormat(getContext());
    }

    private DateFormat getDateFormat() {
        String format = Settings.System.getString(getContext().getContentResolver(),
                Settings.System.DATE_FORMAT);
        if (format == null || "".equals(format)) {
            return DateFormat.getDateInstance(DateFormat.SHORT);
        } else {
            try {
                return new SimpleDateFormat(format);
            } catch (IllegalArgumentException e) {
                // If we tried to use a bad format string, fall back to a default.
                return DateFormat.getDateInstance(DateFormat.SHORT);
            }
        }
    }

    private void registerReceivers() {
        Context context = getContext();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(mBroadcastReceiver, filter);

        Uri uri = Settings.System.getUriFor(Settings.System.DATE_FORMAT);
        context.getContentResolver().registerContentObserver(uri, true, mContentObserver);
    }

    private void unregisterReceivers() {
        Context context = getContext();
        context.unregisterReceiver(mBroadcastReceiver);
        context.getContentResolver().unregisterContentObserver(mContentObserver);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action)) {
                if (System.currentTimeMillis() < mUpdateTimeMillis) {
                    // The update() function takes a few milliseconds to run because of
                    // all of the time conversions it needs to do, so we can't do that
                    // every minute.
                    return;
                }
            }
            // ACTION_TIME_CHANGED can also signal a change of 12/24 hr. format.
            mLastFormat = null;
            update();
        }
    };

    private ContentObserver mContentObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            mLastFormat = null;
            update();
        }
    };
}
