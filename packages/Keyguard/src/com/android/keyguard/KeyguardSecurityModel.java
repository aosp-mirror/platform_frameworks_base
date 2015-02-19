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
import android.content.Context;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;

import java.util.List;

public class KeyguardSecurityModel {

    /**
     * The different types of security available for {@link Mode#UnlockScreen}.
     * @see com.android.internal.policy.impl.LockPatternKeyguardView#getUnlockMode()
     */
    public enum SecurityMode {
        Invalid, // NULL state
        None, // No security enabled
        Pattern, // Unlock by drawing a pattern.
        Password, // Unlock by entering an alphanumeric password
        PIN, // Strictly numeric password
        Biometric, // Unlock with a biometric key (e.g. finger print or face unlock)
        Account, // Unlock by entering an account's login and password.
        SimPin, // Unlock by entering a sim pin.
        SimPuk // Unlock by entering a sim puk
    }

    private Context mContext;
    private LockPatternUtils mLockPatternUtils;

    KeyguardSecurityModel(Context context) {
        mContext = context;
        mLockPatternUtils = new LockPatternUtils(context);
    }

    void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    /**
     * Returns true if biometric unlock is installed and selected.  If this returns false there is
     * no need to even construct the biometric unlock.
     */
    boolean isBiometricUnlockEnabled() {
        return mLockPatternUtils.usingBiometricWeak()
                && mLockPatternUtils.isBiometricWeakInstalled();
    }

    /**
     * Returns true if a condition is currently suppressing the biometric unlock.  If this returns
     * true there is no need to even construct the biometric unlock.
     */
    private boolean isBiometricUnlockSuppressed() {
        KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final boolean backupIsTimedOut = monitor.getFailedUnlockAttempts() >=
                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        return monitor.getMaxBiometricUnlockAttemptsReached() || backupIsTimedOut
                || !monitor.isAlternateUnlockEnabled()
                || monitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE;
    }

    SecurityMode getSecurityMode() {
        KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        SecurityMode mode = SecurityMode.None;
        if (SubscriptionManager.isValidSubscriptionId(
                monitor.getNextSubIdForState(IccCardConstants.State.PIN_REQUIRED))) {
            mode = SecurityMode.SimPin;
        } else if (SubscriptionManager.isValidSubscriptionId(
                    monitor.getNextSubIdForState(IccCardConstants.State.PUK_REQUIRED))
                && mLockPatternUtils.isPukUnlockScreenEnable()) {
            mode = SecurityMode.SimPuk;
        } else {
            final int security = mLockPatternUtils.getKeyguardStoredPasswordQuality();
            switch (security) {
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                    mode = mLockPatternUtils.isLockPasswordEnabled() ?
                            SecurityMode.PIN : SecurityMode.None;
                    break;
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
                case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
                case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                    mode = mLockPatternUtils.isLockPasswordEnabled() ?
                            SecurityMode.Password : SecurityMode.None;
                    break;

                case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
                    if (mLockPatternUtils.isLockPatternEnabled()) {
                        mode = mLockPatternUtils.isPermanentlyLocked() ?
                            SecurityMode.Account : SecurityMode.Pattern;
                    }
                    break;

                default:
                    throw new IllegalStateException("Unknown security quality:" + security);
            }
        }
        return mode;
    }

    /**
     * Some unlock methods can have an alternate, such as biometric unlocks (e.g. face unlock).
     * This function decides if an alternate unlock is available and returns it. Otherwise,
     * returns @param mode.
     *
     * @param mode the mode we want the alternate for
     * @return alternate or the given mode
     */
    SecurityMode getAlternateFor(SecurityMode mode) {
        if (isBiometricUnlockEnabled() && !isBiometricUnlockSuppressed()
                && (mode == SecurityMode.Password
                        || mode == SecurityMode.PIN
                        || mode == SecurityMode.Pattern)) {
            return SecurityMode.Biometric;
        }
        return mode; // no alternate, return what was given
    }

    /**
     * Some unlock methods can have a backup which gives the user another way to get into
     * the device. This is currently only supported for Biometric and Pattern unlock.
     *
     * @return backup method or current security mode
     */
    SecurityMode getBackupSecurityMode(SecurityMode mode) {
        switch(mode) {
            case Biometric:
                return getSecurityMode();
            case Pattern:
                return SecurityMode.Account;
        }
        return mode; // no backup, return current security mode
    }
}
