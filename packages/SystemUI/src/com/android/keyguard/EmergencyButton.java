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

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.telecom.TelecomManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.util.EmergencyAffordanceManager;

/**
 * This class implements a smart emergency button that updates itself based
 * on telephony state.  When the phone is idle, it is an emergency call button.
 * When there's a call in progress, it presents an appropriate message and
 * allows the user to return to the call.
 */
public class EmergencyButton extends Button {
    private static final Intent INTENT_EMERGENCY_DIAL = new Intent()
            .setAction("com.android.phone.EmergencyDialer.DIAL")
            .setPackage("com.android.phone")
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);

    private static final String LOG_TAG = "EmergencyButton";
    private final EmergencyAffordanceManager mEmergencyAffordanceManager;

    private int mDownX;
    private int mDownY;
    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onSimStateChanged(int subId, int slotId, State simState) {
            updateEmergencyCallButton();
        }

        @Override
        public void onPhoneStateChanged(int phoneState) {
            updateEmergencyCallButton();
        }
    };
    private boolean mLongPressWasDragged;

    public interface EmergencyButtonCallback {
        public void onEmergencyButtonClickedWhenInCall();
    }

    private LockPatternUtils mLockPatternUtils;
    private PowerManager mPowerManager;
    private EmergencyButtonCallback mEmergencyButtonCallback;

    private final boolean mIsVoiceCapable;
    private final boolean mEnableEmergencyCallWhileSimLocked;

    public EmergencyButton(Context context) {
        this(context, null);
    }

    public EmergencyButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mIsVoiceCapable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
        mEnableEmergencyCallWhileSimLocked = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enable_emergency_call_while_sim_locked);
        mEmergencyAffordanceManager = new EmergencyAffordanceManager(context);
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

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                takeEmergencyCallAction();
            }
        });
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (!mLongPressWasDragged
                        && mEmergencyAffordanceManager.needsEmergencyAffordance()) {
                    mEmergencyAffordanceManager.performEmergencyCall();
                    return true;
                }
                return false;
            }
        });
        updateEmergencyCallButton();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDownX = x;
            mDownY = y;
            mLongPressWasDragged = false;
        } else {
            final int xDiff = Math.abs(x - mDownX);
            final int yDiff = Math.abs(y - mDownY);
            int touchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
            if (Math.abs(yDiff) > touchSlop || Math.abs(xDiff) > touchSlop) {
                mLongPressWasDragged = true;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performLongClick() {
        return super.performLongClick();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateEmergencyCallButton();
    }

    /**
     * Shows the emergency dialer or returns the user to the existing call.
     */
    public void takeEmergencyCallAction() {
        MetricsLogger.action(mContext, MetricsEvent.ACTION_EMERGENCY_CALL);
        // TODO: implement a shorter timeout once new PowerManager API is ready.
        // should be the equivalent to the old userActivity(EMERGENCY_CALL_TIMEOUT)
        mPowerManager.userActivity(SystemClock.uptimeMillis(), true);
        try {
            ActivityManager.getService().stopSystemLockTaskMode();
        } catch (RemoteException e) {
            Slog.w(LOG_TAG, "Failed to stop app pinning");
        }
        if (isInCall()) {
            resumeCall();
            if (mEmergencyButtonCallback != null) {
                mEmergencyButtonCallback.onEmergencyButtonClickedWhenInCall();
            }
        } else {
            KeyguardUpdateMonitor.getInstance(mContext).reportEmergencyCallAction(
                    true /* bypassHandler */);
            getContext().startActivityAsUser(INTENT_EMERGENCY_DIAL,
                    ActivityOptions.makeCustomAnimation(getContext(), 0, 0).toBundle(),
                    new UserHandle(KeyguardUpdateMonitor.getCurrentUser()));
        }
    }

    private void updateEmergencyCallButton() {
        boolean visible = false;
        if (mIsVoiceCapable) {
            // Emergency calling requires voice capability.
            if (isInCall()) {
                visible = true; // always show "return to call" if phone is off-hook
            } else {
                final boolean simLocked = KeyguardUpdateMonitor.getInstance(mContext)
                        .isSimPinVoiceSecure();
                if (simLocked) {
                    // Some countries can't handle emergency calls while SIM is locked.
                    visible = mEnableEmergencyCallWhileSimLocked;
                } else {
                    // Only show if there is a secure screen (pin/pattern/SIM pin/SIM puk);
                    visible = mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser());
                }
            }
        }
        if (visible) {
            setVisibility(View.VISIBLE);

            int textId;
            if (isInCall()) {
                textId = com.android.internal.R.string.lockscreen_return_to_call;
            } else {
                textId = com.android.internal.R.string.lockscreen_emergency_call;
            }
            setText(textId);
        } else {
            setVisibility(View.GONE);
        }
    }

    public void setCallback(EmergencyButtonCallback callback) {
        mEmergencyButtonCallback = callback;
    }

    /**
     * Resumes a call in progress.
     */
    private void resumeCall() {
        getTelecommManager().showInCallScreen(false);
    }

    /**
     * @return {@code true} if there is a call currently in progress.
     */
    private boolean isInCall() {
        return getTelecommManager().isInCall();
    }

    private TelecomManager getTelecommManager() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }
}
