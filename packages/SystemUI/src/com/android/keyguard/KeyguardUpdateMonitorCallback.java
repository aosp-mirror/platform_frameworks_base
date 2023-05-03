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

import android.hardware.biometrics.BiometricSourceType;
import android.telephony.TelephonyManager;
import android.view.WindowManagerPolicyConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.plugins.WeatherData;
import com.android.systemui.statusbar.KeyguardIndicationController;

import java.util.TimeZone;

/**
 * Callback for general information relevant to lock screen.
 */
public class KeyguardUpdateMonitorCallback {

    /**
     * Called when the battery status changes, e.g. when plugged in or unplugged, charge
     * level, etc. changes.
     *
     * @param status current battery status
     */
    public void onRefreshBatteryInfo(BatteryStatus status) { }

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
     * Called when time format changes.
     */
    public void onTimeFormatChanged(String timeFormat) { }

    /**
     * Called when receive new weather data.
     */
    public void onWeatherDataChanged(WeatherData data) { }

    /**
     * Called when the carrier PLMN or SPN changes.
     */
    public void onRefreshCarrierInfo() { }

    /**
     * Called when the phone state changes. String will be one of:
     * {@link TelephonyManager#EXTRA_STATE_IDLE}
     * {@link TelephonyManager@EXTRA_STATE_RINGING}
     * {@link TelephonyManager#EXTRA_STATE_OFFHOOK
     */
    public void onPhoneStateChanged(int phoneState) { }

    /**
     * Called when the keyguard enters or leaves bouncer mode.
     * @param bouncerIsOrWillBeShowing if true, keyguard is showing the bouncer or transitioning
     *                                 from/to bouncer mode.
     */
    public void onKeyguardBouncerStateChanged(boolean bouncerIsOrWillBeShowing) { }

    /**
     * Called when the keyguard visibility changes.
     * @param visible whether the keyguard is showing and is NOT occluded
     */
    public void onKeyguardVisibilityChanged(boolean visible) { }

    /**
     * Called when the keyguard fully transitions to the bouncer or is no longer the bouncer
     * @param bouncerIsFullyShowing if true, keyguard is fully showing the bouncer
     */
    public void onKeyguardBouncerFullyShowingChanged(boolean bouncerIsFullyShowing) { }

    /**
     * Called when the dismissing animation of keyguard and surfaces behind is finished.
     * If the surface behind is the Launcher, we may still be playing in-window animations
     * when this is called (since it's only called once we dismiss the keyguard and end the
     * remote animation).
     */
    public void onKeyguardDismissAnimationFinished() { }

    /**
     * Called when the device becomes provisioned
     */
    public void onDeviceProvisioned() { }

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
    public void onSimStateChanged(int subId, int slotId, int simState) { }

    /**
     * Called when a user got unlocked.
     */
    public void onUserUnlocked() { }

    /**
     * Called when the emergency call button is pressed.
     */
    public void onEmergencyCallAction() { }

    /**
     * Called when the device has started waking up and after biometric states are updated.
     *
     * @deprecated use {@link com.android.systemui.keyguard.WakefulnessLifecycle}.
     */
    @Deprecated
    public void onStartedWakingUp() { }

    /**
     * Called when the device has started going to sleep and after biometric recognized
     * states are reset.
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
     * Called when trust changes for a user.
     */
    public void onTrustChanged(int userId) { }

    /**
     * Called when trust being managed changes for a user.
     */
    public void onTrustManagedChanged(int userId) { }

    /**
     * Called after trust was granted.
     * @param dismissKeyguard whether the keyguard should be dismissed as a result of the
     *                        trustGranted
     * @param newlyUnlocked whether the grantedTrust is believed to be the cause of a newly
     *                      unlocked device (after being locked).
     * @param message optional message the trust agent has provided to show that should indicate
     *                why trust was granted.
     */
    public void onTrustGrantedForCurrentUser(
            boolean dismissKeyguard,
            boolean newlyUnlocked,
            @NonNull TrustGrantFlags flags,
            @Nullable String message
    ) { }

    /**
     * Called when a biometric has been acquired.
     * <p>
     * It is guaranteed that either {@link #onBiometricAuthenticated} or
     * {@link #onBiometricAuthFailed(BiometricSourceType)} is called after this method eventually.
     * @param biometricSourceType
     * @param acquireInfo see {@link android.hardware.biometrics.BiometricFaceConstants} and
     *                    {@link android.hardware.biometrics.BiometricFingerprintConstants}
     */
    public void onBiometricAcquired(BiometricSourceType biometricSourceType, int acquireInfo) { }

    /**
     * Called when a biometric couldn't be authenticated.
     * @param biometricSourceType
     */
    public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) { }

    /**
     * Called when a biometric is authenticated.
     * @param userId the user id for which the biometric sample was authenticated
     * @param biometricSourceType
     */
    public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) { }

    /**
     * Called when a biometric is detected but not successfully authenticated.
     * @param userId the user id for which the biometric sample was detected
     * @param biometricSourceType
     */
    public void onBiometricDetected(int userId, BiometricSourceType biometricSourceType,
            boolean isStrongBiometric) { }

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
     * When the current user's locked out state changed.
     */
    public void onLockedOutStateChanged(BiometricSourceType biometricSourceType) { }

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
     * @see KeyguardIndicationController#DEFAULT_HIDE_DELAY_MS
     * @see KeyguardIndicationController#showTransientIndication(CharSequence)
     */
    public void onTrustAgentErrorMessage(CharSequence message) { }

    /**
     * Called when a value of logout enabled is change.
     */
    public void onLogoutEnabledChanged() { }

    /**
     * Called when authenticated biometrics are cleared.
     */
    public void onBiometricsCleared() { }

    /**
     * Called when the secondary lock screen requirement changes.
     */
    public void onSecondaryLockscreenRequirementChanged(int userId) { }

    /**
     * Called when device policy manager state changes.
     */
    public void onDevicePolicyManagerStateChanged() { }

    /**
     * Called when notifying user to unlock in order to use NFC.
     */
    public void onRequireUnlockForNfc() { }

    /**
     * Called when the notification shade is expanded or collapsed.
     */
    public void onShadeExpandedChanged(boolean expanded) { }

    /**
     * Called when the non-strong biometric state changed.
     */
    public void onNonStrongBiometricAllowedChanged(int userId) { }

    /**
     * Called when keyguard is going away or not going away.
     */
    public void onKeyguardGoingAway() { }

    /**
     * Called when the enabled trust agents associated with the specified user.
     */
    public void onEnabledTrustAgentsChanged(int userId) { }
}
