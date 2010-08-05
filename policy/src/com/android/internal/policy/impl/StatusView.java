// Copyright 2010 Google Inc. All Rights Reserved.

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import java.util.Date;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

class StatusView {
    private String mDateFormatString;

    private TextView mCarrier;
    private TextView mDate;

    // are we showing battery information?
    private boolean mShowingBatteryInfo = false;

    // last known plugged in state
    private boolean mPluggedIn = false;

    // last known battery level
    private int mBatteryLevel = 100;

    private String mNextAlarm = null;

    private String mInstructions = null;
    private TextView mStatus1;
    private TextView mStatus2;
    private TextView mPropertyOf;

    private boolean mHasStatus2;
    private boolean mHasCarrier;
    private boolean mHasDate;
    private boolean mHasProperty;

    private View mView;

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
        updateStatusLines();
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

        refreshTimeAndDateDisplay();

        mStatus1 = (TextView) findViewById(R.id.status1);
        mStatus2 = (TextView) findViewById(R.id.status2);
        mHasStatus2 = (mStatus2 != null);
        mPropertyOf = (TextView) findViewById(R.id.propertyOf);
        mHasProperty = (mPropertyOf != null);

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
        mNextAlarm = lockPatternUtils.getNextAlarm();
        updateStatusLines();
    }

    void setInstructionText(int stringId) {
        mStatus1.setText(stringId);
        mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_lock, 0, 0, 0);
        mStatus1.setVisibility(View.VISIBLE);
    }

    void setInstructionText(String string) {
        mStatus1.setText(string);
        mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_lock, 0, 0, 0);
        mStatus1.setVisibility(View.VISIBLE);
    }

    void setCarrierText(int stringId) {
        mCarrier.setText(stringId);
    }
    void setCarrierText(String string) {
        mCarrier.setText(string);
    }

    /** Originated from PatternUnlockScreen **/
    void updateStatusLines() {
        if (mHasProperty) {
            // TODO Get actual name & email
            String name = "John Smith";
            String email = "jsmith@gmail.com";
            mPropertyOf.setText("Property of:\n" + name + "\n" + email);
            mPropertyOf.setVisibility(View.VISIBLE);
        }

        if (!mHasStatus2) return;

        if (mInstructions != null) {
            // instructions only
            mStatus1.setText(mInstructions);
            if (TextUtils.isEmpty(mInstructions)) {
                mStatus1.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            } else {
                mStatus1.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_lock_idle_lock, 0, 0, 0);
            }

            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);
        } else if (mShowingBatteryInfo && mNextAlarm == null) {
            // battery only
            if (mPluggedIn) {
              if (mBatteryLevel >= 100) {
                mStatus1.setText(getContext().getString(R.string.lockscreen_charged));
              } else {
                  mStatus1.setText(getContext().getString(R.string.lockscreen_plugged_in,
                          mBatteryLevel));
              }
            } else {
                mStatus1.setText(getContext().getString(R.string.lockscreen_low_battery));
            }
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_charging, 0,
                    0, 0);

            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);

        } else if (mNextAlarm != null && !mShowingBatteryInfo) {
            // alarm only
            mStatus1.setText(mNextAlarm);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_alarm, 0,
                    0, 0);

            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);
        } else if (mNextAlarm != null && mShowingBatteryInfo) {
            // both battery and next alarm
            mStatus1.setText(mNextAlarm);
            mStatus2.setText(getContext().getString(
                    R.string.lockscreen_battery_short,
                    Math.min(100, mBatteryLevel)));
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_alarm, 0,
                    0, 0);
            if (mPluggedIn) {
                mStatus2.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_charging,
                        0, 0, 0);
            } else {
                mStatus2.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            }

            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.VISIBLE);
        } else {
            // nothing specific to show; show general instructions
            mStatus1.setText(R.string.lockscreen_pattern_instructions);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_lock_idle_lock, 0,
                    0, 0);

            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);
        }
    }

    /** Originated from LockScreen **/
    // TODO Merge with function above
    void updateStatusLines(boolean showStatusLines, String charging, Drawable chargingIcon,
            Drawable alarmIcon) {
        if (!showStatusLines || (charging == null && mNextAlarm == null)) {
            mStatus1.setVisibility(View.INVISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);
        } else if (charging != null && mNextAlarm == null) {
            // charging only
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);

            mStatus1.setText(charging);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(chargingIcon, null, null, null);
        } else if (mNextAlarm != null && charging == null) {
            // next alarm only
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);

            mStatus1.setText(mNextAlarm);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(alarmIcon, null, null, null);
        } else if (charging != null && mNextAlarm != null) {
            // both charging and next alarm
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.VISIBLE);

            mStatus1.setText(charging);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(chargingIcon, null, null, null);
            mStatus2.setText(mNextAlarm);
            mStatus2.setCompoundDrawablesWithIntrinsicBounds(alarmIcon, null, null, null);
        }
    }

    void refreshTimeAndDateDisplay() {
        if (mHasDate) {
            mDate.setText(DateFormat.format(mDateFormatString, new Date()));
        }
    }

}
