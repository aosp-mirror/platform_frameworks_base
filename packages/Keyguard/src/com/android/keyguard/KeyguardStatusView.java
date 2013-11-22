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

package com.android.keyguard;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.util.Locale;

public class KeyguardStatusView extends GridLayout {
    private static final boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static final String TAG = "KeyguardStatusView";

    private LockPatternUtils mLockPatternUtils;

    private TextView mAlarmStatusView;
    private TextClock mDateView;
    private TextClock mClockView;

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

        @Override
        public void onScreenTurnedOn() {
            setEnableMarquee(true);
        };

        @Override
        public void onScreenTurnedOff(int why) {
            setEnableMarquee(false);
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

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAlarmStatusView = (TextView) findViewById(R.id.alarm_status);
        mDateView = (TextClock) findViewById(R.id.date_view);
        mClockView = (TextClock) findViewById(R.id.clock_view);
        mLockPatternUtils = new LockPatternUtils(getContext());
        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setEnableMarquee(screenOn);
        refresh();
    }

    protected void refresh() {
        Resources res = mContext.getResources();
        Locale locale = Locale.getDefault();
        final String dateFormat = DateFormat.getBestDateTimePattern(locale,
                res.getString(R.string.abbrev_wday_month_day_no_year));

        mDateView.setFormat24Hour(dateFormat);
        mDateView.setFormat12Hour(dateFormat);

        // 12-hour clock.
        // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
        // format.  The following code removes the AM/PM indicator if we didn't want it.
        final String clock12skel = res.getString(R.string.clock_12hr_format);
        String clock12hr = DateFormat.getBestDateTimePattern(locale, clock12skel);
        clock12hr = clock12skel.contains("a") ? clock12hr : clock12hr.replaceAll("a", "").trim();
        mClockView.setFormat12Hour(clock12hr);

        // 24-hour clock
        final String clock24skel = res.getString(R.string.clock_24hr_format);
        final String clock24hr = DateFormat.getBestDateTimePattern(locale, clock24skel);
        mClockView.setFormat24Hour(clock24hr);

        refreshAlarmStatus();
    }

    void refreshAlarmStatus() {
        // Update Alarm status
        String nextAlarm = mLockPatternUtils.getNextAlarm();
        if (!TextUtils.isEmpty(nextAlarm)) {
            mAlarmStatusView.setText(nextAlarm);
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
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

}
