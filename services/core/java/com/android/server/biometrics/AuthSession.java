/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics;

import android.annotation.IntDef;
import android.hardware.biometrics.IBiometricServiceReceiver;
import android.os.Bundle;
import android.os.IBinder;

import java.util.HashMap;

/**
 * Class that defines the states of an authentication session invoked via
 * {@link android.hardware.biometrics.BiometricPrompt}, as well as all of the necessary
 * state information for such a session.
 */
final class AuthSession {

    /**
     * Authentication either just called and we have not transitioned to the CALLED state, or
     * authentication terminated (success or error).
     */
    static final int STATE_AUTH_IDLE = 0;
    /**
     * Authentication was called and we are waiting for the <Biometric>Services to return their
     * cookies before starting the hardware and showing the BiometricPrompt.
     */
    static final int STATE_AUTH_CALLED = 1;
    /**
     * Authentication started, BiometricPrompt is showing and the hardware is authenticating.
     */
    static final int STATE_AUTH_STARTED = 2;
    /**
     * Authentication is paused, waiting for the user to press "try again" button. Only
     * passive modalities such as Face or Iris should have this state. Note that for passive
     * modalities, the HAL enters the idle state after onAuthenticated(false) which differs from
     * fingerprint.
     */
    static final int STATE_AUTH_PAUSED = 3;
    /**
     * Authentication is successful, but we're waiting for the user to press "confirm" button.
     */
    static final int STATE_AUTH_PENDING_CONFIRM = 5;
    /**
     * Biometric authenticated, waiting for SysUI to finish animation
     */
    static final int STATE_AUTHENTICATED_PENDING_SYSUI = 6;
    /**
     * Biometric error, waiting for SysUI to finish animation
     */
    static final int STATE_ERROR_PENDING_SYSUI = 7;
    /**
     * Device credential in AuthController is showing
     */
    static final int STATE_SHOWING_DEVICE_CREDENTIAL = 8;
    @IntDef({
            STATE_AUTH_IDLE,
            STATE_AUTH_CALLED,
            STATE_AUTH_STARTED,
            STATE_AUTH_PAUSED,
            STATE_AUTH_PENDING_CONFIRM,
            STATE_AUTHENTICATED_PENDING_SYSUI,
            STATE_ERROR_PENDING_SYSUI,
            STATE_SHOWING_DEVICE_CREDENTIAL})
    @interface SessionState {}


    // Map of Authenticator/Cookie pairs. We expect to receive the cookies back from
    // <Biometric>Services before we can start authenticating. Pairs that have been returned
    // are moved to mModalitiesMatched.
    final HashMap<Integer, Integer> mModalitiesWaiting;
    // Pairs that have been matched.
    final HashMap<Integer, Integer> mModalitiesMatched = new HashMap<>();

    // The following variables are passed to authenticateInternal, which initiates the
    // appropriate <Biometric>Services.
    final IBinder mToken;
    final long mOperationId;
    final int mUserId;
    // Original receiver from BiometricPrompt.
    final IBiometricServiceReceiver mClientReceiver;
    final String mOpPackageName;
    // Info to be shown on BiometricDialog when all cookies are returned.
    final Bundle mBundle;
    final int mCallingUid;
    final int mCallingPid;
    final int mCallingUserId;
    // Continue authentication with the same modality/modalities after "try again" is
    // pressed
    final int mModality;
    final boolean mRequireConfirmation;

    // The current state, which can be either idle, called, or started
    @SessionState int mState = STATE_AUTH_IDLE;
    // For explicit confirmation, do not send to keystore until the user has confirmed
    // the authentication.
    byte[] mTokenEscrow;
    // Waiting for SystemUI to complete animation
    int mErrorEscrow;
    int mVendorCodeEscrow;

    // Timestamp when authentication started
    long mStartTimeMs;
    // Timestamp when hardware authentication occurred
    long mAuthenticatedTimeMs;

    AuthSession(HashMap<Integer, Integer> modalities, IBinder token, long operationId,
            int userId, IBiometricServiceReceiver receiver, String opPackageName,
            Bundle bundle, int callingUid, int callingPid, int callingUserId,
            int modality, boolean requireConfirmation) {
        mModalitiesWaiting = modalities;
        mToken = token;
        mOperationId = operationId;
        mUserId = userId;
        mClientReceiver = receiver;
        mOpPackageName = opPackageName;
        mBundle = bundle;
        mCallingUid = callingUid;
        mCallingPid = callingPid;
        mCallingUserId = callingUserId;
        mModality = modality;
        mRequireConfirmation = requireConfirmation;
    }

    boolean isCrypto() {
        return mOperationId != 0;
    }

    boolean containsCookie(int cookie) {
        if (mModalitiesWaiting != null && mModalitiesWaiting.containsValue(cookie)) {
            return true;
        }
        if (mModalitiesMatched != null && mModalitiesMatched.containsValue(cookie)) {
            return true;
        }
        return false;
    }

    boolean isAllowDeviceCredential() {
        return Utils.isCredentialRequested(mBundle);
    }
}
