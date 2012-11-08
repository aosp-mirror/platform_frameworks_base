/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import java.util.Date;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    public static final int LOCK_ICON = 0; // R.drawable.ic_lock_idle_lock;
    public static final int ALARM_ICON = com.android.internal.R.drawable.ic_lock_idle_alarm;
    public static final int CHARGING_ICON = 0; //R.drawable.ic_lock_idle_charging;
    public static final int BATTERY_LOW_ICON = 0; //R.drawable.ic_lock_idle_low_battery;

    private CharSequence mDateFormatString;
    private LockPatternUtils mLockPatternUtils;

    private TextView mDateView;
    private TextView mAlarmStatusView;
    private ClockView mClockView;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            refresh();
        }

        @Override
        void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
            }
        };
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Resources res = getContext().getResources();
        mDateFormatString =
                res.getText(com.android.internal.R.string.abbrev_wday_month_day_no_year);
        mDateView = (TextView) findViewById(R.id.date);
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mClockView = (ClockView) findViewById(R.id.clock_view);
        mLockPatternUtils = new LockPatternUtils(getContext());

        // Use custom font in mDateView
        mDateView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);

        // Required to get Marquee to work.
        final View marqueeViews[] = { mDateView, mAlarmStatusView };
        for (int i = 0; i < marqueeViews.length; i++) {
            View v = marqueeViews[i];
            if (v == null) {
                throw new RuntimeException("Can't find widget at index " + i);
            }
            v.setSelected(true);
        }
        refresh();
    }

    protected void refresh() {
        mClockView.updateTime();
        refreshDate();
        refreshAlarmStatus(); // might as well
    }

    void refreshAlarmStatus() {
        // Update Alarm status
        String nextAlarm = mLockPatternUtils.getNextAlarm();
        if (!TextUtils.isEmpty(nextAlarm)) {
            maybeSetUpperCaseText(mAlarmStatusView, nextAlarm);
            mAlarmStatusView.setCompoundDrawablesWithIntrinsicBounds(ALARM_ICON, 0, 0, 0);
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    void refreshDate() {
        maybeSetUpperCaseText(mDateView, DateFormat.format(mDateFormatString, new Date()));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
    }

    public int getAppWidgetId() {
        return LockPatternUtils.ID_DEFAULT_STATUS_WIDGET;
    }

    private void maybeSetUpperCaseText(TextView textView, CharSequence text) {
        if (KeyguardViewManager.USE_UPPER_CASE
                && textView.getId() != R.id.owner_info) { // currently only required for date view
            textView.setText(text != null ? text.toString().toUpperCase() : null);
        } else {
            textView.setText(text);
        }
    }
}
