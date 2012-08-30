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
package com.android.internal.policy.impl.keyguard;

import android.app.admin.DevicePolicyManager;
import android.content.Context;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;

public class KeyguardSecurityModel {
    /**
     * The different types of security available for {@link Mode#UnlockScreen}.
     * @see com.android.internal.policy.impl.LockPatternKeyguardView#getUnlockMode()
     */
    enum SecurityMode {
        None, // No security enabled
        Pattern, // Unlock by drawing a pattern.
        Password, // Unlock by entering a password or PIN
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
     * This returns false if there is any condition that indicates that the biometric unlock should
     * not be used before the next time the unlock screen is recreated.  In other words, if this
     * returns false there is no need to even construct the biometric unlock.
     */
    private boolean isBiometricUnlockEnabled() {
        KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
        final boolean backupIsTimedOut =
                monitor.getFailedUnlockAttempts() >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;
        return mLockPatternUtils.usingBiometricWeak()
                && mLockPatternUtils.isBiometricWeakInstalled()
                && !monitor.getMaxBiometricUnlockAttemptsReached()
                && !backupIsTimedOut;
    }

    SecurityMode getSecurityMode() {
        KeyguardUpdateMonitor mUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        final IccCardConstants.State simState = mUpdateMonitor.getSimState();
        SecurityMode mode = SecurityMode.None;
        if (simState == IccCardConstants.State.PIN_REQUIRED) {
            mode = SecurityMode.SimPin;
        } else if (simState == IccCardConstants.State.PUK_REQUIRED) {
            mode = SecurityMode.SimPuk;
        } else {
            final int security = mLockPatternUtils.getKeyguardStoredPasswordQuality();
            switch (security) {
                case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
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
                    throw new IllegalStateException("Unknown unlock mode:" + mode);
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
        if (isBiometricUnlockEnabled()
                && (mode == SecurityMode.Password || mode == SecurityMode.Pattern)) {
            return SecurityMode.Biometric;
        }
        return mode; // no alternate, return what was given
    }

    /**
     * Some unlock methods can have a backup which gives the user another way to get into
     * the device. This is currently only supported for Biometric and Pattern unlock.
     *
     * @param mode the mode we want the backup for
     * @return backup method or given mode
     */
    SecurityMode getBackupFor(SecurityMode mode) {
        switch(mode) {
            case Biometric:
                return getSecurityMode();
            case Pattern:
                return SecurityMode.Account;
        }
        return mode; // no backup, return what was given
    }
}
