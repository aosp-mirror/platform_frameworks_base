// Copyright 2010 Google Inc. All Rights Reserved.

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;
import com.google.android.util.AbstractMessageParser.Resources;

import java.util.Date;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

class StatusView {
    public static final int LOCK_ICON = 0; // R.drawable.ic_lock_idle_lock;
    public static final int ALARM_ICON = R.drawable.ic_lock_idle_alarm;
    public static final int CHARGING_ICON = 0; //R.drawable.ic_lock_idle_charging;
    public static final int BATTERY_LOW_ICON = 0; //R.drawable.ic_lock_idle_low_battery;

    private String mDateFormatString;

    private TextView mCarrier;
    private TextView mDate;

    // are we showing battery information?
    private boolean mShowingBatteryInfo = false;

    // last known plugged in state
    private boolean mPluggedIn = false;

    // last known battery level
    private int mBatteryLevel = 100;

    private String mInstructions = null;
    private TextView mStatus1;
    private TextView mPropertyOf;

    private boolean mHasCarrier;
    private boolean mHasDate;

    private View mView;

    private TextView mAlarmStatus;
    private LockPatternUtils mLockPatternUtils;
    private int mHelpMessageId;
    private int mHelpIconId;
    private KeyguardUpdateMonitor mUpdateMonitor;

    private View findViewById(int id) {
        return mView.findViewById(id);
    }

    private Context getContext() {
        return mView.getContext();
    }

    void setInstructions(String instructions) {
        mInstructions = instructions;
    }

    void setCarrierText(CharSequence carrierText) {
        if (mCarrier != null) {
            mCarrier.setText(carrierText);
        }
    }

    void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn, int batteryLevel) {
        mShowingBatteryInfo = showBatteryInfo;
        mPluggedIn = pluggedIn;
        mBatteryLevel = batteryLevel;
        updateStatusLines(true);
    }

    void onTimeChanged() {
        refreshTimeAndDateDisplay();
    }

    public void onRingerModeChanged(int state) {
    }

    void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
        setCarrierText(LockScreen.getCarrierString(plmn, spn));
    }

    public StatusView(View view, KeyguardUpdateMonitor updateMonitor,
                  LockPatternUtils lockPatternUtils) {
        mView = view;
        mCarrier = (TextView) findViewById(R.id.carrier);
        mHasCarrier = (mCarrier != null);
        mDate = (TextView) findViewById(R.id.date);
        mHasDate = (mDate != null);
        mDateFormatString = getContext().getString(R.string.full_wday_month_day_no_year);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;

        refreshTimeAndDateDisplay();

        mStatus1 = (TextView) findViewById(R.id.status1);
        mAlarmStatus = (TextView) findViewById(R.id.alarm_status);
        mAlarmStatus.setCompoundDrawablesWithIntrinsicBounds(ALARM_ICON, 0, 0, 0);
        mPropertyOf = (TextView) findViewById(R.id.propertyOf);

        resetStatusInfo(updateMonitor, lockPatternUtils);

        // Required to get Marquee to work.
        if (mHasCarrier) {
            mCarrier.setSelected(true);
            mCarrier.setTextColor(0xffffffff);
        }
    }

    void resetStatusInfo(KeyguardUpdateMonitor updateMonitor, LockPatternUtils lockPatternUtils) {
        mInstructions = null;
        mShowingBatteryInfo = updateMonitor.shouldShowBatteryInfo();
        mPluggedIn = updateMonitor.isDevicePluggedIn();
        mBatteryLevel = updateMonitor.getBatteryLevel();
        updateStatusLines(true);
    }

    void setInstructionText(int stringId) {
        mStatus1.setText(stringId);
        mStatus1.setCompoundDrawablesWithIntrinsicBounds(LOCK_ICON, 0, 0, 0);
        mStatus1.setVisibility(stringId != 0 ? View.VISIBLE : View.INVISIBLE);
    }

    void setInstructionText(String string) {
        mStatus1.setText(string);
        mStatus1.setCompoundDrawablesWithIntrinsicBounds(LOCK_ICON, 0, 0, 0);
        mStatus1.setVisibility(TextUtils.isEmpty(string) ? View.INVISIBLE : View.VISIBLE);
    }

    void setCarrierText(int stringId) {
        mCarrier.setText(stringId);
    }
    void setCarrierText(String string) {
        mCarrier.setText(string);
    }

    /**
     * Update the status lines based on these rules:
     * AlarmStatus: Alarm state always gets it's own line.
     * Status1 is shared between help, battery status and generic unlock instructions,
     * prioritized in that order.
     * @param showStatusLines status lines are shown if true
     */
    void updateStatusLines(boolean showStatusLines) {
        if (!showStatusLines) {
            mStatus1.setVisibility(showStatusLines ? View.VISIBLE : View.INVISIBLE);
            mAlarmStatus.setVisibility(showStatusLines ? View.VISIBLE : View.INVISIBLE);
            return;
        }

        // Update owner info
        if (mPropertyOf != null) {
            ContentResolver res = getContext().getContentResolver();
            String info = Settings.Secure.getString(res, Settings.Secure.LOCK_SCREEN_OWNER_INFO);
            boolean enabled = Settings.Secure.getInt(res,
                    Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED, 1) != 0;

            mPropertyOf.setText(info);
            mPropertyOf.setVisibility(enabled && !TextUtils.isEmpty(info) ?
                    View.VISIBLE : View.INVISIBLE);
        }

        // Update Alarm status
        String nextAlarm = mLockPatternUtils.getNextAlarm();
        if (!TextUtils.isEmpty(nextAlarm)) {
            mAlarmStatus.setText(nextAlarm);
            mAlarmStatus.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatus.setVisibility(View.INVISIBLE);
        }

        // Update Status1
        if (!TextUtils.isEmpty(mInstructions)) {
            // Instructions only
            mStatus1.setText(mInstructions);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(LOCK_ICON, 0, 0, 0);
            mStatus1.setVisibility(View.VISIBLE);
        } else if (mShowingBatteryInfo) {
            // Battery status
            if (mPluggedIn) {
                // Charging or charged
                if (mUpdateMonitor.isDeviceCharged()) {
                    mStatus1.setText(getContext().getString(R.string.lockscreen_charged));
                } else {
                    mStatus1.setText(getContext().getString(R.string.lockscreen_plugged_in,
                            mBatteryLevel));
                }
                mStatus1.setCompoundDrawablesWithIntrinsicBounds(CHARGING_ICON, 0, 0, 0);
                mStatus1.setVisibility(View.VISIBLE);
            } else if (mBatteryLevel < KeyguardUpdateMonitor.LOW_BATTERY_THRESHOLD) {
                // Battery is low
                mStatus1.setText(getContext().getString(R.string.lockscreen_low_battery));
                mStatus1.setCompoundDrawablesWithIntrinsicBounds(BATTERY_LOW_ICON, 0, 0, 0);
                mStatus1.setVisibility(View.VISIBLE);
            } else {
                mStatus1.setVisibility(View.INVISIBLE);
            }
        } else {
            // nothing specific to show; show help message and icon, if provided
            if (mHelpMessageId != 0) {
                mStatus1.setText(mHelpMessageId);
                mStatus1.setCompoundDrawablesWithIntrinsicBounds(mHelpIconId, 0,0, 0);
                mStatus1.setVisibility(View.VISIBLE);
            } else {
                mStatus1.setVisibility(View.INVISIBLE);
            }
        }
    }

    void setHelpMessage(int messageId, int iconId) {
        mHelpMessageId = messageId;
        mHelpIconId = iconId;
    }

    void refreshTimeAndDateDisplay() {
        if (mHasDate) {
            mDate.setText(DateFormat.format(mDateFormatString, new Date()));
        }
    }

}
