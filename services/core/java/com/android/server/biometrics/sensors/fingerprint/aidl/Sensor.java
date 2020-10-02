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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

/**
 * Maintains the state of a single sensor within an instance of the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} HAL.
 */
class Sensor {
    @NonNull private final String mTag;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    @NonNull private final BiometricScheduler mScheduler;

    @Nullable private Session mCurrentSession; // TODO: Death recipient
    @NonNull private final ClientMonitor.LazyDaemon<ISession> mLazySession;

    private static class Session {
        @NonNull private final String mTag;
        @NonNull private final ISession mSession;
        private final int mUserId;
        private final ISessionCallback mSessionCallback;

        Session(@NonNull String tag, @NonNull ISession session, int userId,
                @NonNull ISessionCallback sessionCallback) {
            mTag = tag;
            mSession = session;
            mUserId = userId;
            mSessionCallback = sessionCallback;
            Slog.d(mTag, "New session created for user: " + userId);
        }
    }

    Sensor(@NonNull String tag,
            @NonNull FingerprintSensorPropertiesInternal sensorProperties,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        mTag = tag;
        mSensorProperties = sensorProperties;
        mScheduler = new BiometricScheduler(tag, gestureAvailabilityDispatcher);
        mLazySession = () -> mCurrentSession != null ? mCurrentSession.mSession : null;
    }

    @NonNull ClientMonitor.LazyDaemon<ISession> getLazySession() {
        return mLazySession;
    }

    @NonNull FingerprintSensorPropertiesInternal getSensorProperties() {
        return mSensorProperties;
    }

    boolean hasSessionForUser(int userId) {
        return mCurrentSession != null && mCurrentSession.mUserId == userId;
    }

    void createNewSession(@NonNull IFingerprint daemon, int sensorId, int userId)
            throws RemoteException {
        final ISessionCallback callback = new ISessionCallback.Stub() {
            @Override
            public void onStateChanged(int cookie, byte state) {

            }

            @Override
            public void onAcquired(byte info, int vendorCode) {

            }

            @Override
            public void onError(byte error, int vendorCode) {

            }

            @Override
            public void onEnrollmentProgress(int enrollmentId, int remaining) {

            }

            @Override
            public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {

            }

            @Override
            public void onAuthenticationFailed() {

            }

            @Override
            public void onLockoutTimed(long durationMillis) {

            }

            @Override
            public void onLockoutPermanent() {

            }

            @Override
            public void onLockoutCleared() {

            }

            @Override
            public void onInteractionDetected() {

            }

            @Override
            public void onEnrollmentsEnumerated(int[] enrollmentIds) {

            }

            @Override
            public void onEnrollmentsRemoved(int[] enrollmentIds) {

            }

            @Override
            public void onAuthenticatorIdRetrieved(long authenticatorId) {

            }

            @Override
            public void onAuthenticatorIdInvalidated() {

            }
        };

        final ISession newSession = daemon.createSession(sensorId, userId, callback);
        mCurrentSession = new Session(mTag, newSession, userId, callback);
    }

    @NonNull BiometricScheduler getScheduler() {
        return mScheduler;
    }
}
