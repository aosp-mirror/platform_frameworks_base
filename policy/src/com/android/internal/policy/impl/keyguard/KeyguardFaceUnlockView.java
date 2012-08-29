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

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.android.internal.widget.LockPatternUtils;

public class KeyguardFaceUnlockView extends LinearLayout implements KeyguardSecurityView {

    // Long enough to stay visible while dialer comes up
    // Short enough to not be visible if the user goes back immediately
    private static final int BIOMETRIC_AREA_EMERGENCY_DIALER_TIMEOUT = 1000;
    private KeyguardSecurityCallback mKeyguardSecurityCallback;
    private LockPatternUtils mLockPatternUtils;

    public KeyguardFaceUnlockView(Context context) {
        this(context, null);
    }

    public KeyguardFaceUnlockView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mKeyguardSecurityCallback = callback;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {

    }

    @Override
    public void onPause() {

    }

    @Override
    public void onResume() {

    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mKeyguardSecurityCallback;
    }

    // TODO
    //    public void onRefreshBatteryInfo(BatteryStatus status) {
    //        // When someone plugs in or unplugs the device, we hide the biometric sensor area and
    //        // suppress its startup for the next onScreenTurnedOn().  Since plugging/unplugging
    //        // causes the screen to turn on, the biometric unlock would start if it wasn't
    //        // suppressed.
    //        //
    //        // However, if the biometric unlock is already running, we do not want to interrupt it.
    //        final boolean pluggedIn = status.isPluggedIn();
    //        if (mBiometricUnlock != null && mPluggedIn != pluggedIn
    //                && !mBiometricUnlock.isRunning()) {
    //            mBiometricUnlock.stop();
    //            mBiometricUnlock.hide();
    //            mSuppressBiometricUnlock = true;
    //        }
    //        mPluggedIn = pluggedIn;
    //    }

    // We need to stop the biometric unlock when a phone call comes in
    //    @Override
    //    public void onPhoneStateChanged(int phoneState) {
    //        if (DEBUG) Log.d(TAG, "phone state: " + phoneState);
    //        if (phoneState == TelephonyManager.CALL_STATE_RINGING) {
    //            mSuppressBiometricUnlock = true;
    //            mBiometricUnlock.stop();
    //            mBiometricUnlock.hide();
    //        }
    //    }

    //    @Override
    //    public void onUserSwitched(int userId) {
    //        if (mBiometricUnlock != null) {
    //            mBiometricUnlock.stop();
    //        }
    //        mLockPatternUtils.setCurrentUser(userId);
    //        updateScreen(getInitialMode(), true);
    //    }

    //    /**
    //     * This returns false if there is any condition that indicates that the biometric unlock should
    //     * not be used before the next time the unlock screen is recreated.  In other words, if this
    //     * returns false there is no need to even construct the biometric unlock.
    //     */
    //    private boolean useBiometricUnlock() {
    //        final ShowingMode unlockMode = getUnlockMode();
    //        final boolean backupIsTimedOut = (mUpdateMonitor.getFailedAttempts() >=
    //                LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
    //        return (mLockPatternUtils.usingBiometricWeak() &&
    //                mLockPatternUtils.isBiometricWeakInstalled() &&
    //                !mUpdateMonitor.getMaxBiometricUnlockAttemptsReached() &&
    //                !backupIsTimedOut &&
    //                (unlockMode == ShowingMode.Pattern || unlockMode == ShowingMode.Password));
    //    }

    //    private void initializeBiometricUnlockView(View view) {
    //        boolean restartBiometricUnlock = false;
    //
    //        if (mBiometricUnlock != null) {
    //            restartBiometricUnlock = mBiometricUnlock.stop();
    //        }
    //
    //        // Prevents biometric unlock from coming up immediately after a phone call or if there
    //        // is a dialog on top of lockscreen. It is only updated if the screen is off because if the
    //        // screen is on it's either because of an orientation change, or when it first boots.
    //        // In both those cases, we don't want to override the current value of
    //        // mSuppressBiometricUnlock and instead want to use the previous value.
    //        if (!mScreenOn) {
    //            mSuppressBiometricUnlock =
    //                    mUpdateMonitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE
    //                    || mHasDialog;
    //        }
    //
    //        // If the biometric unlock is not being used, we don't bother constructing it.  Then we can
    //        // simply check if it is null when deciding whether we should make calls to it.
    //        mBiometricUnlock = null;
    //        if (useBiometricUnlock()) {
    //            // TODO: make faceLockAreaView a more general biometricUnlockView
    //            // We will need to add our Face Unlock specific child views programmatically in
    //            // initializeView rather than having them in the XML files.
    //            View biometricUnlockView = view.findViewById(
    //                    com.android.internal.R.id.faceLockAreaView);
    //            if (biometricUnlockView != null) {
    //                mBiometricUnlock = new FaceUnlock(mContext, mUpdateMonitor, mLockPatternUtils,
    //                        mKeyguardScreenCallback);
    //                mBiometricUnlock.initializeView(biometricUnlockView);
    //
    //                // If this is being called because the screen turned off, we want to cover the
    //                // backup lock so it is covered when the screen turns back on.
    //                if (!mScreenOn) mBiometricUnlock.show(0);
    //            } else {
    //                Log.w(TAG, "Couldn't find biometric unlock view");
    //            }
    //        }
    //
    //        if (mBiometricUnlock != null && restartBiometricUnlock) {
    //            maybeStartBiometricUnlock();
    //        }
    //    }

    //    /**
    //     * Starts the biometric unlock if it should be started based on a number of factors including
    //     * the mSuppressBiometricUnlock flag.  If it should not be started, it hides the biometric
    //     * unlock area.
    //     */
    //    private void maybeStartBiometricUnlock() {
    //        if (mBiometricUnlock != null) {
    //            final boolean backupIsTimedOut = (mUpdateMonitor.getFailedAttempts() >=
    //                    LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
    //            if (!mSuppressBiometricUnlock
    //                    && mUpdateMonitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
    //                    && !mUpdateMonitor.getMaxBiometricUnlockAttemptsReached()
    //                    && !backupIsTimedOut) {
    //                mBiometricUnlock.start();
    //            } else {
    //                mBiometricUnlock.hide();
    //            }
    //        }
    //}

}
