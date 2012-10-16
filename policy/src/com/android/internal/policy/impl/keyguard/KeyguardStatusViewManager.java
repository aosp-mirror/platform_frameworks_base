/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.internal.R;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.DigitalClock;
import com.android.internal.widget.LockPatternUtils;

import java.util.Date;

import libcore.util.MutableInt;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.util.Date;

import libcore.util.MutableInt;

/***
 * Manages a number of views inside of the given layout. See below for a list of widgets.
 */
class KeyguardStatusViewManager implements SecurityMessageDisplay {
    private static final boolean DEBUG = false;
    private static final String TAG = "KeyguardStatusView";

    public static final int LOCK_ICON = 0; // R.drawable.ic_lock_idle_lock;
    public static final int ALARM_ICON = com.android.internal.R.drawable.ic_lock_idle_alarm;
    public static final int CHARGING_ICON = 0; //R.drawable.ic_lock_idle_charging;
    public static final int BATTERY_LOW_ICON = 0; //R.drawable.ic_lock_idle_low_battery;

    private CharSequence mDateFormatString;

    // Views that this class controls.
    private TextView mDateView;
    private TextView mStatus1View;
    private TextView mOwnerInfoView;
    private TextView mAlarmStatusView;
    private TextView mSecurityMessage;
    private static final int SECURITY_MESSAGE_DURATION = 5000;
    private static final boolean SECURITY_MESSAGE_TIMES_OUT = false;

    // Top-level container view for above views
    private View mContainer;

    // are we showing battery information?
    private boolean mShowingBatteryInfo = false;

    // last known plugged in state
    private boolean mPluggedIn = false;

    // Whether to use the last line as a combined line to either display owner info / charging.
    // If false, each item will be given a dedicated space.
    private boolean mShareStatusRegion = false;
    
    // last known battery level
    private int mBatteryLevel = 100;

    // last known SIM state
    protected IccCardConstants.State mSimState;

    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mUpdateMonitor;

    // Shadowed text values
    private ClockView mClockView;
    protected boolean mBatteryCharged;
    protected boolean mBatteryIsLow;

    private Handler mHandler;
    private Runnable mClearSecurityMessageRunnable;
    private CharSequence mSecurityMessageContents = "";

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) {
            mShowingBatteryInfo = status.isPluggedIn() || status.isBatteryLow();
            mPluggedIn = status.isPluggedIn();
            mBatteryLevel = status.level;
            mBatteryCharged = status.isCharged();
            mBatteryIsLow = status.isBatteryLow();
            updateStatusLines();
        }

        @Override
        public void onTimeChanged() {
            refreshDate();
        }
    };

    /**
     * @param view the containing view of all widgets
     */
    public KeyguardStatusViewManager(View view) {
        if (DEBUG) Log.v(TAG, "KeyguardStatusViewManager()");
        mContainer = view;
        Resources res = getContext().getResources();
        mDateFormatString = 
                res.getText(com.android.internal.R.string.abbrev_wday_month_day_no_year);
        mShareStatusRegion = res.getBoolean(R.bool.kg_share_status_area);
        mLockPatternUtils = new LockPatternUtils(view.getContext());
        mUpdateMonitor = KeyguardUpdateMonitor.getInstance(view.getContext());

        mDateView = (TextView) view.findViewById(R.id.date);
        mStatus1View = (TextView) view.findViewById(R.id.status1);
        mAlarmStatusView = (TextView) view.findViewById(R.id.alarm_status);
        mOwnerInfoView = (TextView) view.findViewById(R.id.owner_info);
        mClockView = (ClockView) view.findViewById(R.id.clock_view);
        mSecurityMessage = (TextView) view.findViewById(R.id.status_security_message);

        // This is required to ensure marquee works
        if (mSecurityMessage != null) {
            mSecurityMessage.setSelected(true);
        }

        // Use custom font in mDateView
        mDateView.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);

        // Required to get Marquee to work.
        final View marqueeViews[] = { mDateView, mStatus1View, mOwnerInfoView, mAlarmStatusView };
        for (int i = 0; i < marqueeViews.length; i++) {
            View v = marqueeViews[i];
            if (v == null) {
                throw new RuntimeException("Can't find widget at index " + i);
            }
            v.setSelected(true);
        }

        // Registering this callback immediately updates the battery state, among other things.
        mUpdateMonitor.registerCallback(mInfoCallback);

        refreshDate();
        resetStatusInfo();

        mHandler = new Handler(Looper.myLooper());
        mClearSecurityMessageRunnable = new Runnable() {
            @Override
            public void run() {
                mSecurityMessageContents = "";
                updateStatusLines();
            }
        };
    }

    public void onPause() {
        if (DEBUG) Log.v(TAG, "onPause()");
        mUpdateMonitor.removeCallback(mInfoCallback);
    }

    /** {@inheritDoc} */
    public void onResume() {
        if (DEBUG) Log.v(TAG, "onResume()");

        // Force-update the time when we show this view.
        mClockView.updateTime();

        mUpdateMonitor.registerCallback(mInfoCallback);
        resetStatusInfo();
    }

    void resetStatusInfo() {
        updateStatusLines();
    }

    public void setMessage(CharSequence msg, boolean important) {
        if (!important) {
            mSecurityMessageContents = "";
        } else {
            mSecurityMessageContents = msg;
        }
        securityMessageChanged();
    }

    public void setMessage(int resId, boolean important) {
        if (resId != 0 && important) {
            mSecurityMessageContents = getContext().getResources().getText(resId);
        } else {
            mSecurityMessageContents = "";
        }
        securityMessageChanged();
    }

    public void setMessage(int resId, boolean important, Object... formatArgs) {
        if (resId != 0 && important) {
            mSecurityMessageContents = getContext().getString(resId, formatArgs);
        } else {
            mSecurityMessageContents = "";
        }
        securityMessageChanged();
    }

    public void securityMessageChanged() {
        updateStatusLines();
        if (SECURITY_MESSAGE_TIMES_OUT) {
            mHandler.removeCallbacks(mClearSecurityMessageRunnable);
            mHandler.postDelayed(mClearSecurityMessageRunnable, SECURITY_MESSAGE_DURATION);
        }
        mSecurityMessage.announceForAccessibility(mSecurityMessage.getText());
    }

    /**
     * Update the status lines based on these rules:
     * AlarmStatus: Alarm state always gets it's own line.
     * Status1 is shared between help, battery status and generic unlock instructions,
     * prioritized in that order.
     * @param showStatusLines status lines are shown if true
     */
    void updateStatusLines() {
        updateAlarmInfo();
        boolean statusAreaUsed = updateSecurityMessage();
        statusAreaUsed = updateStatus1(statusAreaUsed) || statusAreaUsed;
        updateOwnerInfo(statusAreaUsed);
    }

    private boolean updateSecurityMessage() {
        if (mSecurityMessage == null) return false;
        if (!TextUtils.isEmpty(mSecurityMessageContents)) {
            mSecurityMessage.setText(mSecurityMessageContents);
            mSecurityMessage.setVisibility(View.VISIBLE);
            return true;
        } else {
            mSecurityMessage.setVisibility(View.GONE);
            return false;
        }
    }

    private void updateAlarmInfo() {
        String nextAlarm = mLockPatternUtils.getNextAlarm();
        if (!TextUtils.isEmpty(nextAlarm)) {
            maybeSetUpperCaseText(mAlarmStatusView, nextAlarm);
            mAlarmStatusView.setCompoundDrawablesWithIntrinsicBounds(ALARM_ICON, 0, 0, 0);
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    private boolean updateStatus1(boolean statusAreaUsed) {
        MutableInt icon = new MutableInt(0);
        CharSequence string = getPriorityTextMessage(icon);

        boolean dontShow = statusAreaUsed && mShareStatusRegion;
        if (!dontShow && !TextUtils.isEmpty(string)) {
            maybeSetUpperCaseText(mStatus1View, string);
            mStatus1View.setCompoundDrawablesWithIntrinsicBounds(icon.value, 0, 0, 0);
            mStatus1View.setVisibility(View.VISIBLE);
            return true;
        } else {
            mStatus1View.setVisibility(View.GONE);
            return false;
        }
    }

    private void updateOwnerInfo(boolean statusAreaUsed) {
        final ContentResolver res = getContext().getContentResolver();
        final boolean ownerInfoEnabled = Settings.Secure.getIntForUser(res,
                Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED, 1, UserHandle.USER_CURRENT) != 0;
        String text = Settings.Secure.getStringForUser(res, Settings.Secure.LOCK_SCREEN_OWNER_INFO,
                UserHandle.USER_CURRENT);

        boolean dontShow = statusAreaUsed && mShareStatusRegion;
        if (!dontShow && ownerInfoEnabled && !TextUtils.isEmpty(text)) {
            text = text.trim(); // Remove trailing newlines
            maybeSetUpperCaseText(mOwnerInfoView, text);
            mOwnerInfoView.setVisibility(View.VISIBLE);
        } else {
            mOwnerInfoView.setVisibility(View.GONE);
        }
    }

    private CharSequence getPriorityTextMessage(MutableInt icon) {
        CharSequence string = null;
        if (mShowingBatteryInfo) {
            // Battery status
            if (mPluggedIn) {
                // Charging, charged or waiting to charge.
                string = getContext().getString(mBatteryCharged ?
                        com.android.internal.R.string.lockscreen_charged
                        :com.android.internal.R.string.lockscreen_plugged_in, mBatteryLevel);
                icon.value = CHARGING_ICON;
            } else if (mBatteryIsLow) {
                // Battery is low
                string = getContext().getString(
                        com.android.internal.R.string.lockscreen_low_battery);
                icon.value = BATTERY_LOW_ICON;
            }
        }
        return string;
    }

    void refreshDate() {
        maybeSetUpperCaseText(mDateView, DateFormat.format(mDateFormatString, new Date()));
    }

    private void maybeSetUpperCaseText(TextView textView, CharSequence text) {
        if (KeyguardViewManager.USE_UPPER_CASE
                && textView.getId() != R.id.owner_info) { // currently only required for date view
            textView.setText(text != null ? text.toString().toUpperCase() : null);
        } else {
            textView.setText(text);
        }
    }

    private Context getContext() {
        return mContainer.getContext();
    }

}
