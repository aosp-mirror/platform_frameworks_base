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
import android.content.Context;
import android.hardware.biometrics.fingerprint.Error;
import android.hardware.biometrics.fingerprint.IFingerprint;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitor;
import com.android.server.biometrics.sensors.Interruptable;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains the state of a single sensor within an instance of the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} HAL.
 */
class Sensor {
    @NonNull private final String mTag;
    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    @NonNull private final BiometricScheduler mScheduler;
    @NonNull private final LockoutCache mLockoutCache;
    @NonNull private final Map<Integer, Long> mAuthenticatorIds;

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

    Sensor(@NonNull String tag, @NonNull Context context, @NonNull Handler handler,
            @NonNull FingerprintSensorPropertiesInternal sensorProperties,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        mTag = tag;
        mContext = context;
        mHandler = handler;
        mSensorProperties = sensorProperties;
        mScheduler = new BiometricScheduler(tag, gestureAvailabilityDispatcher);
        mLockoutCache = new LockoutCache();
        mAuthenticatorIds = new HashMap<>();
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
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof AcquisitionClient)) {
                        Slog.e(mTag, "onAcquired for non-acquisition client: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final AcquisitionClient<?> acquisitionClient = (AcquisitionClient<?>) client;
                    acquisitionClient.onAcquired(info, vendorCode);
                });
            }

            @Override
            public void onError(byte error, int vendorCode) {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    Slog.d(mTag, "onError"
                            + ", client: " + Utils.getClientName(client)
                            + ", error: " + error
                            + ", vendorCode: " + vendorCode);
                    if (!(client instanceof Interruptable)) {
                        Slog.e(mTag, "onError for non-error consumer: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final Interruptable interruptable = (Interruptable) client;
                    interruptable.onError(error, vendorCode);

                    if (error == Error.HW_UNAVAILABLE) {
                        Slog.e(mTag, "Got ERROR_HW_UNAVAILABLE");
                        mCurrentSession = null;
                    }
                });
            }

            @Override
            public void onEnrollmentProgress(int enrollmentId, int remaining) {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof FingerprintEnrollClient)) {
                        Slog.e(mTag, "onEnrollmentProgress for non-enroll client: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final int currentUserId = client.getTargetUserId();
                    final CharSequence name = FingerprintUtils.getInstance()
                            .getUniqueName(mContext, currentUserId);
                    final Fingerprint fingerprint = new Fingerprint(name, enrollmentId, sensorId);

                    final FingerprintEnrollClient enrollClient = (FingerprintEnrollClient) client;
                    enrollClient.onEnrollResult(fingerprint, remaining);
                });
            }

            @Override
            public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof AuthenticationConsumer)) {
                        Slog.e(mTag, "onAuthenticationSucceeded for non-authentication consumer: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final AuthenticationConsumer authenticationConsumer =
                            (AuthenticationConsumer) client;
                    final Fingerprint fp = new Fingerprint("", enrollmentId, sensorId);
                    final byte[] byteArray = HardwareAuthTokenUtils.toByteArray(hat);
                    final ArrayList<Byte> byteList = new ArrayList<>();
                    for (byte b : byteArray) {
                        byteList.add(b);
                    }

                    authenticationConsumer.onAuthenticated(fp, true /* authenticated */, byteList);
                });
            }

            @Override
            public void onAuthenticationFailed() {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof AuthenticationConsumer)) {
                        Slog.e(mTag, "onAuthenticationFailed for non-authentication consumer: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final AuthenticationConsumer authenticationConsumer =
                            (AuthenticationConsumer) client;
                    final Fingerprint fp = new Fingerprint("", 0 /* enrollmentId */, sensorId);
                    authenticationConsumer
                            .onAuthenticated(fp, false /* authenticated */, null /* hat */);
                });
            }

            @Override
            public void onLockoutTimed(long durationMillis) {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof LockoutConsumer)) {
                        Slog.e(mTag, "onLockoutTimed for non-lockout consumer: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                    lockoutConsumer.onLockoutTimed(durationMillis);
                });
            }

            @Override
            public void onLockoutPermanent() {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof LockoutConsumer)) {
                        Slog.e(mTag, "onLockoutPermanent for non-lockout consumer: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final LockoutConsumer lockoutConsumer = (LockoutConsumer) client;
                    lockoutConsumer.onLockoutPermanent();
                });
            }

            @Override
            public void onLockoutCleared() {
                mHandler.post(() -> {
                    final ClientMonitor<?> client = mScheduler.getCurrentClient();
                    if (!(client instanceof FingerprintResetLockoutClient)) {
                        Slog.e(mTag, "onLockoutCleared for non-resetLockout client: "
                                + Utils.getClientName(client));
                        return;
                    }

                    final FingerprintResetLockoutClient resetLockoutClient =
                            (FingerprintResetLockoutClient) client;
                    resetLockoutClient.onLockoutCleared();
                });
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

    @NonNull LockoutCache getLockoutCache() {
        return mLockoutCache;
    }

    @NonNull Map<Integer, Long> getAuthenticatorIds() {
        return mAuthenticatorIds;
    }
}
