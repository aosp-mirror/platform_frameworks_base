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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.internal.telephony.SimCard;

import java.util.Date;

/**
 * The screen within {@link LockPatternKeyguardView} that shows general
 * information about the device depending on its state, and how to get
 * past it, as applicable.
 */
class LockScreen extends LinearLayout implements KeyguardScreen, KeyguardUpdateMonitor.InfoCallback,
        KeyguardUpdateMonitor.SimStateCallback, KeyguardUpdateMonitor.ConfigurationChangeCallback {
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private TextView mHeaderSimOk1;
    private TextView mHeaderSimOk2;

    private TextView mHeaderSimBad1;
    private TextView mHeaderSimBad2;

    private TextView mTime;
    private TextView mDate;

    private ViewGroup mBatteryInfoGroup;
    private ImageView mBatteryInfoIcon;
    private TextView mBatteryInfoText;
    private View mBatteryInfoSpacer;

    private ViewGroup mNextAlarmGroup;
    private TextView mAlarmText;
    private View mAlarmSpacer;

    private ViewGroup mScreenLockedMessageGroup;

    private TextView mLockInstructions;

    private Button mEmergencyCallButton;

    /**
     * false means sim is missing or PUK'd
     */
    private boolean mSimOk = true;

    // are we showing battery information?
    private boolean mShowingBatteryInfo = false;

    // last known plugged in state
    private boolean mPluggedIn = false;

    // last known battery level
    private int mBatteryLevel = 100;


    private View[] mOnlyVisibleWhenSimOk;

    private View[] mOnlyVisibleWhenSimNotOk;

    /**
     * @param context Used to setup the view.
     * @param lockPatternUtils Used to know the state of the lock pattern settings.
     * @param updateMonitor Used to register for updates on various keyguard related
     *    state, and query the initial state at setup.
     * @param callback Used to communicate back to the host keyguard view.
     */
    LockScreen(Context context, LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;

        final LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(R.layout.keyguard_screen_lock, this, true);

        mSimOk = isSimOk(updateMonitor.getSimState());
        mShowingBatteryInfo = updateMonitor.shouldShowBatteryInfo();
        mPluggedIn = updateMonitor.isDevicePluggedIn();
        mBatteryLevel = updateMonitor.getBatteryLevel();

        mHeaderSimOk1 = (TextView) findViewById(R.id.headerSimOk1);
        mHeaderSimOk2 = (TextView) findViewById(R.id.headerSimOk2);

        mHeaderSimBad1 = (TextView) findViewById(R.id.headerSimBad1);
        mHeaderSimBad2 = (TextView) findViewById(R.id.headerSimBad2);

        mTime = (TextView) findViewById(R.id.time);
        mDate = (TextView) findViewById(R.id.date);

        mBatteryInfoGroup = (ViewGroup) findViewById(R.id.batteryInfo);
        mBatteryInfoIcon = (ImageView) findViewById(R.id.batteryInfoIcon);
        mBatteryInfoText = (TextView) findViewById(R.id.batteryInfoText);
        mBatteryInfoSpacer = findViewById(R.id.batteryInfoSpacer);

        mNextAlarmGroup = (ViewGroup) findViewById(R.id.nextAlarmInfo);
        mAlarmText = (TextView) findViewById(R.id.nextAlarmText);
        mAlarmSpacer = findViewById(R.id.nextAlarmSpacer);

        mScreenLockedMessageGroup = (ViewGroup) findViewById(R.id.screenLockedInfo);

        mLockInstructions = (TextView) findViewById(R.id.lockInstructions);

        mEmergencyCallButton = (Button) findViewById(R.id.emergencyCallButton);

        mEmergencyCallButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mCallback.takeEmergencyCallAction();
            }
        });

        mOnlyVisibleWhenSimOk = new View[] {
            mHeaderSimOk1,
            mHeaderSimOk2,
            mBatteryInfoGroup,
            mBatteryInfoSpacer,
            mNextAlarmGroup,
            mAlarmSpacer,
            mScreenLockedMessageGroup,
            mLockInstructions
        };

        mOnlyVisibleWhenSimNotOk = new View[] {
            mHeaderSimBad1,
            mHeaderSimBad2,
            mEmergencyCallButton
        };

        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        refreshBatteryDisplay();
        refreshAlarmDisplay();
        refreshTimeAndDateDisplay();
        refreshUnlockIntructions();
        refreshViewsWRTSimOk();
        refreshSimOkHeaders(mUpdateMonitor.getTelephonyPlmn(), mUpdateMonitor.getTelephonySpn());

        updateMonitor.registerInfoCallback(this);
        updateMonitor.registerSimStateCallback(this);
        updateMonitor.registerConfigurationChangeCallback(this);
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            mCallback.goToUnlockScreen();
        }
        return false;
    }

    private void refreshViewsWRTSimOk() {
        if (mSimOk) {
            for (int i = 0; i < mOnlyVisibleWhenSimOk.length; i++) {
                final View view = mOnlyVisibleWhenSimOk[i];
                if (view == null) throw new RuntimeException("index " + i + " null");
                view.setVisibility(View.VISIBLE);
            }
            for (int i = 0; i < mOnlyVisibleWhenSimNotOk.length; i++) {
                final View view = mOnlyVisibleWhenSimNotOk[i];
                view.setVisibility(View.GONE);
            }
            refreshSimOkHeaders(mUpdateMonitor.getTelephonyPlmn(), mUpdateMonitor.getTelephonySpn());
            refreshAlarmDisplay();
            refreshBatteryDisplay();
        } else {
            for (int i = 0; i < mOnlyVisibleWhenSimOk.length; i++) {
                final View view = mOnlyVisibleWhenSimOk[i];
                view.setVisibility(View.GONE);
            }
            for (int i = 0; i < mOnlyVisibleWhenSimNotOk.length; i++) {
                final View view = mOnlyVisibleWhenSimNotOk[i];
                view.setVisibility(View.VISIBLE);
            }
            refreshSimBadInfo();
        }
    }

    private void refreshSimBadInfo() {
        final SimCard.State simState = mUpdateMonitor.getSimState();
        if (simState == SimCard.State.PUK_REQUIRED) {
            mHeaderSimBad1.setText(R.string.lockscreen_sim_puk_locked_message);
            mHeaderSimBad2.setText(R.string.lockscreen_sim_puk_locked_instructions);
        } else if (simState == SimCard.State.ABSENT) {
            mHeaderSimBad1.setText(R.string.lockscreen_missing_sim_message);
            mHeaderSimBad2.setVisibility(View.GONE);
            //mHeaderSimBad2.setText(R.string.lockscreen_missing_sim_instructions);
        } else {
            mHeaderSimBad1.setVisibility(View.GONE);
            mHeaderSimBad2.setVisibility(View.GONE);
        }
    }

    private void refreshUnlockIntructions() {
        if (mLockPatternUtils.isLockPatternEnabled()
                || mUpdateMonitor.getSimState() == SimCard.State.PIN_REQUIRED) {
            mLockInstructions.setText(R.string.lockscreen_instructions_when_pattern_enabled);
        } else {
            mLockInstructions.setText(R.string.lockscreen_instructions_when_pattern_disabled);
        }
    }

    private void refreshAlarmDisplay() {
        String nextAlarmText = mLockPatternUtils.getNextAlarm();
        if (nextAlarmText != null && mSimOk) {
            setAlarmInfoVisible(true);
            mAlarmText.setText(nextAlarmText);
        } else {
            setAlarmInfoVisible(false);
        }
    }

    private void setAlarmInfoVisible(boolean visible) {
        final int visibilityFlag = visible ? View.VISIBLE : View.GONE;
        mNextAlarmGroup.setVisibility(visibilityFlag);
        mAlarmSpacer.setVisibility(visibilityFlag);
    }


    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn,
            int batteryLevel) {
        mShowingBatteryInfo = showBatteryInfo;
        mPluggedIn = pluggedIn;
        mBatteryLevel = batteryLevel;

        refreshBatteryDisplay();
    }

    private void refreshBatteryDisplay() {
        if (!mShowingBatteryInfo || !mSimOk) {
            mBatteryInfoGroup.setVisibility(View.GONE);
            mBatteryInfoSpacer.setVisibility(View.GONE);
            return;
        }
        mBatteryInfoGroup.setVisibility(View.VISIBLE);
        mBatteryInfoSpacer.setVisibility(View.VISIBLE);

        if (mPluggedIn) {
            mBatteryInfoIcon.setImageResource(R.drawable.ic_lock_idle_charging);
            mBatteryInfoText.setText(
                    getContext().getString(R.string.lockscreen_plugged_in, mBatteryLevel));
        } else {
            mBatteryInfoIcon.setImageResource(R.drawable.ic_lock_idle_low_battery);
            mBatteryInfoText.setText(R.string.lockscreen_low_battery);
        }
    }

    public void onTimeChanged() {
        refreshTimeAndDateDisplay();
    }

    private void refreshTimeAndDateDisplay() {
        Date now = new Date();
        mTime.setText(DateFormat.getTimeFormat(getContext()).format(now));
        mDate.setText(DateFormat.getDateFormat(getContext()).format(now));
    }

    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
        refreshSimOkHeaders(plmn, spn);
    }

    private void refreshSimOkHeaders(CharSequence plmn, CharSequence spn) {
        final SimCard.State simState = mUpdateMonitor.getSimState();
        if (simState == SimCard.State.READY) {
            if (plmn != null) {
                mHeaderSimOk1.setVisibility(View.VISIBLE);
                mHeaderSimOk1.setText(plmn);
            } else {
                mHeaderSimOk1.setVisibility(View.GONE);
            }

            if (spn != null) {
                mHeaderSimOk2.setVisibility(View.VISIBLE);
                mHeaderSimOk2.setText(spn);
            } else {
                mHeaderSimOk2.setVisibility(View.GONE);
            }
        } else if (simState == SimCard.State.PIN_REQUIRED) {
            mHeaderSimOk1.setVisibility(View.VISIBLE);
            mHeaderSimOk1.setText(R.string.lockscreen_sim_locked_message);
            mHeaderSimOk2.setVisibility(View.GONE);
        } else if (simState == SimCard.State.ABSENT) {
            mHeaderSimOk1.setVisibility(View.VISIBLE);
            mHeaderSimOk1.setText(R.string.lockscreen_missing_sim_message_short);
            mHeaderSimOk2.setVisibility(View.GONE);
        } else if (simState == SimCard.State.NETWORK_LOCKED) {
            mHeaderSimOk1.setVisibility(View.VISIBLE);
            mHeaderSimOk1.setText(R.string.lockscreen_network_locked_message);
            mHeaderSimOk2.setVisibility(View.GONE);
        }
    }

    public void onSimStateChanged(SimCard.State simState) {
        mSimOk = isSimOk(simState);
        refreshViewsWRTSimOk();
    }

    /**
     * @return Whether the sim state is ok, meaning we don't need to show
     *   a special screen with the emergency call button and keep them from
     *   doing anything else.
     */
    private boolean isSimOk(SimCard.State simState) {
        boolean missingAndNotProvisioned = (!mUpdateMonitor.isDeviceProvisioned()
                && simState == SimCard.State.ABSENT);
        return !(missingAndNotProvisioned || simState == SimCard.State.PUK_REQUIRED);
    }

    public void onOrientationChange(boolean inPortrait) {
    }

    public void onKeyboardChange(boolean isKeyboardOpen) {
        if (isKeyboardOpen) {
            mCallback.goToUnlockScreen();
        }
    }


    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }
    
    /** {@inheritDoc} */
    public void onPause() {

    }

    /** {@inheritDoc} */
    public void onResume() {

    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this);
    }
}
