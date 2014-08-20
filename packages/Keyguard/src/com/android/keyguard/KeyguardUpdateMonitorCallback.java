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

import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.WindowManagerPolicy;

import com.android.internal.telephony.IccCardConstants;

/**
 * Callback for general information relevant to lock screen.
 */
public class KeyguardUpdateMonitorCallback {

    private static final long VISIBILITY_CHANGED_COLLAPSE_MS = 1000;
    private long mVisibilityChangedCalled;
    private boolean mShowing;

    /**
     * Called when the battery status changes, e.g. when plugged in or unplugged, charge
     * level, etc. changes.
     *
     * @param status current battery status
     */
    public void onRefreshBatteryInfo(KeyguardUpdateMonitor.BatteryStatus status) { }

    /**
     * Called once per minute or when the time changes.
     */
    public void onTimeChanged() { }

    /**
     * Called when the carrier PLMN or SPN changes.
     *
     * @param plmn The operator name of the registered network.  May be null if it shouldn't
     *   be displayed.
     * @param spn The service provider name.  May be null if it shouldn't be displayed.
     */
    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) { }

    /**
     * Called when the ringer mode changes.
     * @param state the current ringer state, as defined in
     * {@link AudioManager#RINGER_MODE_CHANGED_ACTION}
     */
    public void onRingerModeChanged(int state) { }

    /**
     * Called when the phone state changes. String will be one of:
     * {@link TelephonyManager#EXTRA_STATE_IDLE}
     * {@link TelephonyManager@EXTRA_STATE_RINGING}
     * {@link TelephonyManager#EXTRA_STATE_OFFHOOK
     */
    public void onPhoneStateChanged(int phoneState) { }

    /**
     * Called when the visibility of the keyguard changes.
     * @param showing Indicates if the keyguard is now visible.
     */
    public void onKeyguardVisibilityChanged(boolean showing) { }

    public void onKeyguardVisibilityChangedRaw(boolean showing) {
        final long now = SystemClock.elapsedRealtime();
        if (showing == mShowing
                && (now - mVisibilityChangedCalled) < VISIBILITY_CHANGED_COLLAPSE_MS) return;
        onKeyguardVisibilityChanged(showing);
        mVisibilityChangedCalled = now;
        mShowing = showing;
    }

    /**
     * Called when the keyguard enters or leaves bouncer mode.
     * @param bouncer if true, keyguard is now in bouncer mode.
     */
    public void onKeyguardBouncerChanged(boolean bouncer) { }

    /**
     * Called when visibility of lockscreen clock changes, such as when
     * obscured by a widget.
     */
    public void onClockVisibilityChanged() { }

    /**
     * Called when the device becomes provisioned
     */
    public void onDeviceProvisioned() { }

    /**
     * Called when the device policy changes.
     * See {@link DevicePolicyManager#ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED}
     */
    public void onDevicePolicyManagerStateChanged() { }

    /**
     * Called when the user change begins.
     */
    public void onUserSwitching(int userId) { }

    /**
     * Called when the user change is complete.
     */
    public void onUserSwitchComplete(int userId) { }

    /**
     * Called when the SIM state changes.
     * @param simState
     */
    public void onSimStateChanged(IccCardConstants.State simState) { }

    /**
     * Called when a user is removed.
     */
    public void onUserRemoved(int userId) { }

    /**
     * Called when the user's info changed.
     */
    public void onUserInfoChanged(int userId) { }

    /**
     * Called when boot completed.
     *
     * Note, this callback will only be received if boot complete occurs after registering with
     * KeyguardUpdateMonitor.
     */
    public void onBootCompleted() { }

    /**
     * Called when the emergency call button is pressed.
     */
    void onEmergencyCallAction() { }

    /**
     * Called when the transport background changes.
     * @param bitmap
     */
    public void onSetBackground(Bitmap bitmap) {
    }

    /**
     * Called when the screen turns on
     */
    public void onScreenTurnedOn() { }

    /**
     * Called when the screen turns off
     * @param why either {@link WindowManagerPolicy#OFF_BECAUSE_OF_ADMIN},
     * {@link WindowManagerPolicy#OFF_BECAUSE_OF_USER}, or
     * {@link WindowManagerPolicy#OFF_BECAUSE_OF_TIMEOUT}.
     */
    public void onScreenTurnedOff(int why) { }

    /**
     * Called when trust changes for a user.
     */
    public void onTrustChanged(int userId) { }

    /**
     * Called when trust being managed changes for a user.
     */
    public void onTrustManagedChanged(int userId) { }

    /**
     * Called when the user has proved to a trust agent that they want to use the device.
     */
    public void onTrustInitiatedByUser(int userId) { }

    /**
     * Called when a fingerprint is recognized.
     * @param userId
     */
    public void onFingerprintRecognized(int userId) { }

    /**
     * Called when fingerprint is acquired but not yet recognized
     */
    public void onFingerprintAcquired(int info) { }

    /**
     * Called when the state of face unlock changed.
     */
    public void onFaceUnlockStateChanged(boolean running, int userId) { }
}
