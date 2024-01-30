/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.fingerprint.ISessionCallback;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.keymaster.HardwareAuthToken;
import android.util.Slog;

import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AcquisitionClient;
import com.android.server.biometrics.sensors.AuthSessionCoordinator;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.EnumerateConsumer;
import com.android.server.biometrics.sensors.ErrorConsumer;
import com.android.server.biometrics.sensors.LockoutCache;
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import java.util.ArrayList;
import java.util.function.Consumer;


/**
 * Response handler for the {@link ISessionCallback} HAL AIDL interface.
 */
public class AidlResponseHandler extends ISessionCallback.Stub {

    /**
     * Interface to send results to the AidlResponseHandler's owner.
     */
    public interface HardwareUnavailableCallback {
        /**
         * Invoked when the HAL sends ERROR_HW_UNAVAILABLE.
         */
        void onHardwareUnavailable();
    }

    private static final String TAG = "AidlResponseHandler";

    @NonNull
    private final Context mContext;
    @NonNull
    private final BiometricScheduler mScheduler;
    private final int mSensorId;
    private final int mUserId;
    @NonNull
    private final LockoutCache mLockoutCache;
    @NonNull
    private final LockoutResetDispatcher mLockoutResetDispatcher;
    @NonNull
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @NonNull
    private final HardwareUnavailableCallback mHardwareUnavailableCallback;

    public AidlResponseHandler(@NonNull Context context,
            @NonNull BiometricScheduler scheduler, int sensorId, int userId,
            @NonNull LockoutCache lockoutTracker,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull AuthSessionCoordinator authSessionCoordinator,
            @NonNull HardwareUnavailableCallback hardwareUnavailableCallback) {
        mContext = context;
        mScheduler = scheduler;
        mSensorId = sensorId;
        mUserId = userId;
        mLockoutCache = lockoutTracker;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mAuthSessionCoordinator = authSessionCoordinator;
        mHardwareUnavailableCallback = hardwareUnavailableCallback;
    }

    @Override
    public int getInterfaceVersion() {
        return this.VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return this.HASH;
    }

    @Override
    public void onChallengeGenerated(long challenge) {
        handleResponse(FingerprintGenerateChallengeClient.class, (c) -> c.onChallengeGenerated(
                mSensorId, mUserId, challenge), null);
    }

    @Override
    public void onChallengeRevoked(long challenge) {
        handleResponse(FingerprintRevokeChallengeClient.class, (c) -> c.onChallengeRevoked(
                challenge), null);
    }

    /**
     * Handles acquired messages sent by the HAL (specifically for HIDL HAL).
     */
    public void onAcquired(int acquiredInfo, int vendorCode) {
        handleResponse(AcquisitionClient.class, (c) -> c.onAcquired(acquiredInfo, vendorCode),
                null);
    }

    @Override
    public void onAcquired(byte info, int vendorCode) {
        handleResponse(AcquisitionClient.class, (c) -> c.onAcquired(
                AidlConversionUtils.toFrameworkAcquiredInfo(info), vendorCode), null);
    }

    /**
     * Handle error messages from the HAL.
     */
    public void onError(int error, int vendorCode) {
        handleResponse(ErrorConsumer.class, (c) -> {
            c.onError(error, vendorCode);
            if (error == Error.HW_UNAVAILABLE) {
                mHardwareUnavailableCallback.onHardwareUnavailable();
            }
        }, null);
    }

    @Override
    public void onError(byte error, int vendorCode) {
        onError(AidlConversionUtils.toFrameworkError(error), vendorCode);
    }

    @Override
    public void onEnrollmentProgress(int enrollmentId, int remaining) {
        BaseClientMonitor client = mScheduler.getCurrentClient();
        final int currentUserId;
        if (client == null) {
            return;
        } else {
            currentUserId = client.getTargetUserId();
        }
        final CharSequence name = FingerprintUtils.getInstance(mSensorId)
                .getUniqueName(mContext, currentUserId);
        final Fingerprint fingerprint = new Fingerprint(name, currentUserId,
                enrollmentId, mSensorId);
        handleResponse(FingerprintEnrollClient.class, (c) -> c.onEnrollResult(fingerprint,
                remaining), null);
    }

    @Override
    public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {
        final Fingerprint fp = new Fingerprint("", enrollmentId, mSensorId);
        final byte[] byteArray = HardwareAuthTokenUtils.toByteArray(hat);
        final ArrayList<Byte> byteList = new ArrayList<>();
        for (byte b : byteArray) {
            byteList.add(b);
        }
        handleResponse(AuthenticationConsumer.class, (c) -> c.onAuthenticated(fp,
                true /* authenticated */, byteList), (c) -> onInteractionDetected());
    }

    @Override
    public void onAuthenticationFailed() {
        final Fingerprint fp = new Fingerprint("", 0 /* enrollmentId */, mSensorId);
        handleResponse(AuthenticationConsumer.class, (c) -> c.onAuthenticated(fp,
                false /* authenticated */, null /* hardwareAuthToken */),
                (c) -> onInteractionDetected());
    }

    @Override
    public void onLockoutTimed(long durationMillis) {
        handleResponse(LockoutConsumer.class, (c) -> c.onLockoutTimed(durationMillis),
                null);
    }

    @Override
    public void onLockoutPermanent() {
        handleResponse(LockoutConsumer.class, LockoutConsumer::onLockoutPermanent, null);
    }

    @Override
    public void onLockoutCleared() {
        handleResponse(FingerprintResetLockoutClient.class,
                FingerprintResetLockoutClient::onLockoutCleared,
                (c) -> FingerprintResetLockoutClient.resetLocalLockoutStateToNone(
                        mSensorId, mUserId, mLockoutCache, mLockoutResetDispatcher,
                        mAuthSessionCoordinator, Utils.getCurrentStrength(mSensorId),
                        -1 /* requestId */));
    }

    @Override
    public void onInteractionDetected() {
        handleResponse(FingerprintDetectClient.class,
                FingerprintDetectClient::onInteractionDetected, null);
    }

    @Override
    public void onEnrollmentsEnumerated(int[] enrollmentIds) {
        if (enrollmentIds.length > 0) {
            for (int i = 0; i < enrollmentIds.length; i++) {
                final Fingerprint fp = new Fingerprint("", enrollmentIds[i], mSensorId);
                int finalI = i;
                handleResponse(EnumerateConsumer.class, (c) -> c.onEnumerationResult(fp,
                        enrollmentIds.length - finalI - 1), null);
            }
        } else {
            handleResponse(EnumerateConsumer.class, (c) -> c.onEnumerationResult(null,
                    0), null);
        }
    }

    @Override
    public void onEnrollmentsRemoved(int[] enrollmentIds) {
        if (enrollmentIds.length > 0) {
            for (int i  = 0; i < enrollmentIds.length; i++) {
                final Fingerprint fp = new Fingerprint("", enrollmentIds[i], mSensorId);
                int finalI = i;
                handleResponse(RemovalConsumer.class, (c) -> c.onRemoved(fp,
                        enrollmentIds.length - finalI - 1), null);
            }
        } else {
            handleResponse(RemovalConsumer.class, (c) -> c.onRemoved(null, 0),
                    null);
        }
    }

    @Override
    public void onAuthenticatorIdRetrieved(long authenticatorId) {
        handleResponse(FingerprintGetAuthenticatorIdClient.class,
                (c) -> c.onAuthenticatorIdRetrieved(authenticatorId), null);
    }

    @Override
    public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
        handleResponse(FingerprintInvalidationClient.class, (c) -> c.onAuthenticatorIdInvalidated(
                newAuthenticatorId), null);
    }

    private <T> void handleResponse(@NonNull Class<T> className,
            @NonNull Consumer<T> action,
            @Nullable Consumer<BaseClientMonitor> alternateAction) {
        mScheduler.getHandler().post(() -> {
            final BaseClientMonitor client = mScheduler.getCurrentClient();
            if (className.isInstance(client)) {
                action.accept((T) client);
            } else {
                Slog.e(TAG, "Client monitor is not an instance of " + className.getName());
                if (alternateAction != null) {
                    alternateAction.accept(client);
                }
            }
        });
    }

    @Override
    public void onSessionClosed() {
        mScheduler.getHandler().post(mScheduler::onUserStopped);
    }
}
