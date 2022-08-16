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

import static android.hardware.biometrics.BiometricManager.Authenticators;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.IBiometricAuthenticator;
import android.hardware.biometrics.IBiometricSensorReceiver;
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
public abstract class BiometricSensor {
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

    @NonNull private final Context mContext;
    public final int id;
    public final @Authenticators.Types int oemStrength; // strength as configured by the OEM
    public final int modality;
    public final IBiometricAuthenticator impl;

    private @Authenticators.Types int mUpdatedStrength; // updated by BiometricStrengthController
    private @SensorState int mSensorState;
    private @BiometricConstants.Errors int mError;

    private int mCookie; // invalid during STATE_UNKNOWN

    /**
     * @return true if the user's system settings specifies that this sensor always requires
     * confirmation.
     */
    abstract boolean confirmationAlwaysRequired(int userId);

    /**
     * @return true if confirmation is supported by this sensor.
     */
    abstract boolean confirmationSupported();

    BiometricSensor(@NonNull Context context, int id, int modality,
            @Authenticators.Types int strength, IBiometricAuthenticator impl) {
        this.mContext = context;
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
            int userId, IBiometricSensorReceiver sensorReceiver, String opPackageName,
            long requestId, int cookie, boolean allowBackgroundAuthentication)
            throws RemoteException {
        mCookie = cookie;
        impl.prepareForAuthentication(requireConfirmation, token,
                sessionId, userId, sensorReceiver, opPackageName, requestId, mCookie,
                allowBackgroundAuthentication);
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

    void goToStateCancelling(IBinder token, String opPackageName, long requestId)
            throws RemoteException {
        if (mSensorState != STATE_CANCELING) {
            impl.cancelAuthenticationFromService(token, opPackageName, requestId);
            mSensorState = STATE_CANCELING;
        }
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
     * @return a bitfield, see {@link android.hardware.biometrics.BiometricManager.Authenticators}
     */
    @Authenticators.Types int getCurrentStrength() {
        return oemStrength | mUpdatedStrength;
    }

    @SensorState int getSensorState() {
        return mSensorState;
    }

    int getCookie() {
        return mCookie;
    }

    /**
     * Stores the updated strength, which takes effect whenever {@link #getCurrentStrength()}
     * is checked.
     * @param newStrength
     */
    void updateStrength(@Authenticators.Types int newStrength) {
        String log = "updateStrength: Before(" + this + ")";
        mUpdatedStrength = newStrength;
        log += " After(" + this + ")";
        Slog.d(TAG, log);
    }

    @Override
    public String toString() {
        return "ID(" + id + ")"
                + ", oemStrength: " + oemStrength
                + ", updatedStrength: " + mUpdatedStrength
                + ", modality " + modality
                + ", state: " + mSensorState
                + ", cookie: " + mCookie;
    }
}
