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

import android.app.admin.DevicePolicyManager;
import android.graphics.Bitmap;
import android.hardware.biometrics.BiometricSourceType;
import android.media.AudioManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.telephony.IccCardConstants;
import com.android.systemui.statusbar.KeyguardIndicationController;

import java.util.TimeZone;

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
     * Called when time zone changes.
     *
     * @note When time zone changes, onTimeChanged will be called too.
     */
    public void onTimeZoneChanged(TimeZone timeZone) { }

    /**
     * Called when the carrier PLMN or SPN changes.
     */
    public void onRefreshCarrierInfo() { }

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
     * Called when the Telephony capable
     * @param capable
     */
    public void onTelephonyCapable(boolean capable) { }

    /**
     * Called when the SIM state changes.
     * @param slotId
     * @param simState
     */
    public void onSimStateChanged(int subId, int slotId, IccCardConstants.State simState) { }

    /**
     * Called when the user's info changed.
     */
    public void onUserInfoChanged(int userId) { }

    /**
     * Called when a user got unlocked.
     */
    public void onUserUnlocked() { }

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
    public void onEmergencyCallAction() { }

    /**
     * Called when the transport background changes.
     * @param bitmap
     */
    public void onSetBackground(Bitmap bitmap) {
    }

    /**
     * Called when the device has started waking up.
     *
     * @deprecated use {@link com.android.systemui.keyguard.WakefulnessLifecycle}.
     */
    @Deprecated
    public void onStartedWakingUp() { }

    /**
     * Called when the device has started going to sleep.
     * @param why see {@link #onFinishedGoingToSleep(int)}
     *
     * @deprecated use {@link com.android.systemui.keyguard.WakefulnessLifecycle}.
     */
    @Deprecated
    public void onStartedGoingToSleep(int why) { }

    /**
     * Called when the device has finished going to sleep.
     * @param why either {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_ADMIN},
     * {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_USER}, or
     * {@link WindowManagerPolicyConstants#OFF_BECAUSE_OF_TIMEOUT}.
     *
     * @deprecated use {@link com.android.systemui.keyguard.WakefulnessLifecycle}.
     */
    @Deprecated
    public void onFinishedGoingToSleep(int why) { }

    /**
     * Called when the screen has been turned on.
     *
     * @deprecated use {@link com.android.systemui.keyguard.ScreenLifecycle}.
     */
    @Deprecated
    public void onScreenTurnedOn() { }

    /**
     * Called when the screen has been turned off.
     *
     * @deprecated use {@link com.android.systemui.keyguard.ScreenLifecycle}.
     */
    @Deprecated
    public void onScreenTurnedOff() { }

    /**
     * Called when trust changes for a user.
     */
    public void onTrustChanged(int userId) { }

    /**
     * Called when trust being managed changes for a user.
     */
    public void onTrustManagedChanged(int userId) { }

    /**
     * Called after trust was granted with non-zero flags.
     */
    public void onTrustGrantedWithFlags(int flags, int userId) { }

    /**
     * Called when a biometric has been acquired.
     * <p>
     * It is guaranteed that either {@link #onBiometricAuthenticated} or
     * {@link #onBiometricAuthFailed(BiometricSourceType)} is called after this method eventually.
     * @param biometricSourceType
     */
    public void onBiometricAcquired(BiometricSourceType biometricSourceType) { }

    /**
     * Called when a biometric couldn't be authenticated.
     * @param biometricSourceType
     */
    public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) { }

    /**
     * Called when a biometric is recognized.
     * @param userId the user id for which the biometric sample was authenticated
     * @param biometricSourceType
     */
    public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType) { }

    /**
     * Called when biometric authentication provides help string (e.g. "Try again")
     * @param msgId
     * @param helpString
     * @param biometricSourceType
     */
    public void onBiometricHelp(int msgId, String helpString,
            BiometricSourceType biometricSourceType) { }

    /**
     * Called when biometric authentication method provides a semi-permanent
     * error message (e.g. "Hardware not available").
     * @param msgId one of the error messages listed in
     *        {@link android.hardware.biometrics.BiometricConstants}
     * @param errString
     * @param biometricSourceType
     */
    public void onBiometricError(int msgId, String errString,
            BiometricSourceType biometricSourceType) { }

    /**
     * Called when the state of face unlock changed.
     */
    public void onFaceUnlockStateChanged(boolean running, int userId) { }

    /**
     * Called when biometric running state changed.
     */
    public void onBiometricRunningStateChanged(boolean running,
            BiometricSourceType biometricSourceType) { }

    /**
     * Called when the state that the user hasn't used strong authentication since quite some time
     * has changed.
     */
    public void onStrongAuthStateChanged(int userId) { }

    /**
     * Called when the state whether we have a lockscreen wallpaper has changed.
     */
    public void onHasLockscreenWallpaperChanged(boolean hasLockscreenWallpaper) { }

    /**
     * Called when the dream's window state is changed.
     * @param dreaming true if the dream's window has been created and is visible
     */
    public void onDreamingStateChanged(boolean dreaming) { }

    /**
     * Called when an error message needs to be presented on the keyguard.
     * Message will be visible briefly, and might be overridden by other keyguard events,
     * like fingerprint authentication errors.
     *
     * @param message Message that indicates an error.
     * @see KeyguardIndicationController.BaseKeyguardCallback#HIDE_DELAY_MS
     * @see KeyguardIndicationController#showTransientIndication(CharSequence)
     */
    public void onTrustAgentErrorMessage(CharSequence message) { }


    /**
     * Called when a value of logout enabled is change.
     */
    public void onLogoutEnabledChanged() { }

}
