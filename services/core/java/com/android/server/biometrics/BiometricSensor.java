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
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Wraps IBiometricAuthenticator implementation and stores information about the authenticator,
 * including its current state.
 * TODO(b/141025588): Consider refactoring the tests to not rely on this implementation detail.
 */
public class BiometricSensor {
    private static final String TAG = "BiometricService/Sensor";

    // State is unknown. Usually this means we need the sensor but have not requested for
    // it to be used yet (cookie not sent yet)
    static final int STATE_UNKNOWN = 0;
    // Cookie has been generated, and the relevant sensor service has been asked to prepare
    // for authentication. Awaiting "ack" from the sensor.
    static final int STATE_WAITING_FOR_COOKIE = 1;
    // The appropriate sensor service has "acked" notifying us that it's ready to be
    // started for authentication.
    static final int STATE_COOKIE_RETURNED = 2;
    // The sensor is being used for authentication.
    static final int STATE_AUTHENTICATING = 3;
    // Cancel has been requested, waiting for ERROR_CANCELED to be received from the HAL
    static final int STATE_CANCELING = 4;
    static final int STATE_STOPPED = 5;

    @IntDef({STATE_UNKNOWN,
            STATE_WAITING_FOR_COOKIE,
            STATE_COOKIE_RETURNED,
            STATE_AUTHENTICATING,
            STATE_CANCELING,
            STATE_STOPPED})
    @Retention(RetentionPolicy.SOURCE)
    @interface SensorState {}

    public final int id;
    public final int oemStrength; // strength as configured by the OEM
    public final int modality;
    public final IBiometricAuthenticator impl;

    private int mUpdatedStrength; // strength updated by BiometricStrengthController
    private @SensorState int mSensorState;
    private @BiometricConstants.Errors int mError;

    private int mCookie; // invalid during STATE_UNKNOWN

    BiometricSensor(int id, int modality, int strength,
            IBiometricAuthenticator impl) {
        this.id = id;
        this.modality = modality;
        this.oemStrength = strength;
        this.impl = impl;

        mUpdatedStrength = strength;
        goToStateUnknown();
    }

    void goToStateUnknown() {
        mSensorState = STATE_UNKNOWN;
        mCookie = 0;
        mError = BiometricConstants.BIOMETRIC_SUCCESS;
    }

    void goToStateWaitingForCookie(boolean requireConfirmation, IBinder token, long sessionId,
            int userId, IBiometricServiceReceiverInternal internalReceiver, String opPackageName,
            int cookie, int callingUid, int callingPid, int callingUserId)
            throws RemoteException {
        mCookie = cookie;
        impl.prepareForAuthentication(requireConfirmation, token,
                sessionId, userId, internalReceiver, opPackageName, mCookie,
                callingUid, callingPid, callingUserId);
        mSensorState = STATE_WAITING_FOR_COOKIE;
    }

    void goToStateCookieReturnedIfCookieMatches(int cookie) {
        if (cookie == mCookie) {
            Slog.d(TAG, "Sensor(" + id + ") matched cookie: " + cookie);
            mSensorState = STATE_COOKIE_RETURNED;
        }
    }

    void startSensor() throws RemoteException {
        impl.startPreparedClient(mCookie);
        mSensorState = STATE_AUTHENTICATING;
    }

    void goToStateCancelling(IBinder token, String opPackageName, int callingUid,
            int callingPid, int callingUserId, boolean fromClient) throws RemoteException {
        impl.cancelAuthenticationFromService(token, opPackageName, callingUid, callingPid,
                callingUserId, fromClient);
        mSensorState = STATE_CANCELING;
    }

    void goToStoppedStateIfCookieMatches(int cookie, int error) {
        if (cookie == mCookie) {
            Slog.d(TAG, "Sensor(" + id + ") now in STATE_STOPPED");
            mError = error;
            mSensorState = STATE_STOPPED;
        }
    }

    /**
     * Returns the actual strength, taking any updated strengths into effect. Since more bits
     * means lower strength, the resulting strength is never stronger than the OEM's configured
     * strength.
     * @return a bitfield, see {@link BiometricManager.Authenticators}
     */
    int getActualStrength() {
        return oemStrength | mUpdatedStrength;
    }

    @SensorState int getSensorState() {
        return mSensorState;
    }

    int getCookie() {
        return mCookie;
    }

    /**
     * Stores the updated strength, which takes effect whenever {@link #getActualStrength()}
     * is checked.
     * @param newStrength
     */
    void updateStrength(int newStrength) {
        String log = "updateStrength: Before(" + toString() + ")";
        mUpdatedStrength = newStrength;
        log += " After(" + toString() + ")";
        Slog.d(TAG, log);
    }

    @Override
    public String toString() {
        return "ID(" + id + ")"
                + " oemStrength: " + oemStrength
                + " updatedStrength: " + mUpdatedStrength
                + " modality " + modality
                + " authenticator: " + impl
                + " state: " + mSensorState
                + " cookie: " + mCookie;
    }
}
