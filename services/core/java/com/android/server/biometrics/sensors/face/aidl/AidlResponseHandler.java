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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.biometrics.face.Error;
import android.hardware.biometrics.face.ISessionCallback;
import android.hardware.face.Face;
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
import com.android.server.biometrics.sensors.LockoutConsumer;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.RemovalConsumer;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Response handler for the {@link ISessionCallback} HAL AIDL interface.
 */
public class AidlResponseHandler extends ISessionCallback.Stub {
    /**
     * Interface to send results to the AidlResponseHandler's owner.
     */
    public interface AidlResponseHandlerCallback {
        /**
         * Invoked when enrollment is successful.
         */
        void onEnrollSuccess();
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
    private final LockoutTracker mLockoutTracker;
    @NonNull
    private final LockoutResetDispatcher mLockoutResetDispatcher;

    @NonNull
    private final AuthSessionCoordinator mAuthSessionCoordinator;
    @NonNull
    private final AidlResponseHandlerCallback mAidlResponseHandlerCallback;

    public AidlResponseHandler(@NonNull Context context,
            @NonNull BiometricScheduler scheduler, int sensorId, int userId,
            @NonNull LockoutTracker lockoutTracker,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull AuthSessionCoordinator authSessionCoordinator,
            @NonNull AidlResponseHandlerCallback aidlResponseHandlerCallback) {
        mContext = context;
        mScheduler = scheduler;
        mSensorId = sensorId;
        mUserId = userId;
        mLockoutTracker = lockoutTracker;
        mLockoutResetDispatcher = lockoutResetDispatcher;
        mAuthSessionCoordinator = authSessionCoordinator;
        mAidlResponseHandlerCallback = aidlResponseHandlerCallback;
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
        handleResponse(FaceGenerateChallengeClient.class, (c) -> c.onChallengeGenerated(mSensorId,
                mUserId, challenge));
    }

    @Override
    public void onChallengeRevoked(long challenge) {
        handleResponse(FaceRevokeChallengeClient.class, (c) -> c.onChallengeRevoked(mSensorId,
                mUserId, challenge));
    }

    @Override
    public void onAuthenticationFrame(AuthenticationFrame frame) {
        handleResponse(FaceAuthenticationClient.class, (c) -> {
            if (frame == null) {
                Slog.e(TAG, "Received null enrollment frame for face authentication client.");
                return;
            }
            c.onAuthenticationFrame(AidlConversionUtils.toFrameworkAuthenticationFrame(frame));
        });
    }

    @Override
    public void onEnrollmentFrame(EnrollmentFrame frame) {
        handleResponse(FaceEnrollClient.class, (c) -> {
            if (frame == null) {
                Slog.e(TAG, "Received null enrollment frame for face enroll client.");
                return;
            }
            c.onEnrollmentFrame(AidlConversionUtils.toFrameworkEnrollmentFrame(frame));
        });
    }

    @Override
    public void onError(byte error, int vendorCode) {
        onError(AidlConversionUtils.toFrameworkError(error), vendorCode);
    }

    /**
     * Handle error messages from the HAL.
     */
    public void onError(int error, int vendorCode) {
        handleResponse(ErrorConsumer.class, (c) -> {
            c.onError(error, vendorCode);
            if (error == Error.HW_UNAVAILABLE) {
                mAidlResponseHandlerCallback.onHardwareUnavailable();
            }
        });
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
        final CharSequence name = FaceUtils.getInstance(mSensorId)
                .getUniqueName(mContext, currentUserId);
        final Face face = new Face(name, enrollmentId, mSensorId);

        handleResponse(FaceEnrollClient.class, (c) -> {
            c.onEnrollResult(face, remaining);
            if (remaining == 0) {
                mAidlResponseHandlerCallback.onEnrollSuccess();
            }
        });
    }

    @Override
    public void onAuthenticationSucceeded(int enrollmentId, HardwareAuthToken hat) {
        final Face face = new Face("" /* name */, enrollmentId, mSensorId);
        final byte[] byteArray = HardwareAuthTokenUtils.toByteArray(hat);
        final ArrayList<Byte> byteList = new ArrayList<>();
        for (byte b : byteArray) {
            byteList.add(b);
        }
        handleResponse(AuthenticationConsumer.class, (c) -> c.onAuthenticated(face,
                true /* authenticated */, byteList));
    }

    @Override
    public void onAuthenticationFailed() {
        final Face face = new Face("" /* name */, 0 /* faceId */, mSensorId);
        handleResponse(AuthenticationConsumer.class, (c) -> c.onAuthenticated(face,
                false /* authenticated */, null /* hat */));
    }

    @Override
    public void onLockoutTimed(long durationMillis) {
        handleResponse(LockoutConsumer.class, (c) -> c.onLockoutTimed(durationMillis));
    }

    @Override
    public void onLockoutPermanent() {
        handleResponse(LockoutConsumer.class, LockoutConsumer::onLockoutPermanent);
    }

    @Override
    public void onLockoutCleared() {
        handleResponse(FaceResetLockoutClient.class, FaceResetLockoutClient::onLockoutCleared,
                (c) -> FaceResetLockoutClient.resetLocalLockoutStateToNone(mSensorId, mUserId,
                        mLockoutTracker, mLockoutResetDispatcher, mAuthSessionCoordinator,
                        Utils.getCurrentStrength(mSensorId), -1 /* requestId */));
    }

    @Override
    public void onInteractionDetected() {
        handleResponse(FaceDetectClient.class, FaceDetectClient::onInteractionDetected);
    }

    @Override
    public void onEnrollmentsEnumerated(int[] enrollmentIds) {
        if (enrollmentIds.length > 0) {
            for (int i = 0; i < enrollmentIds.length; ++i) {
                final Face face = new Face("" /* name */, enrollmentIds[i], mSensorId);
                final int finalI = i;
                handleResponse(EnumerateConsumer.class, (c) -> c.onEnumerationResult(face,
                        enrollmentIds.length - finalI - 1 /* remaining */));
            }
        } else {
            handleResponse(EnumerateConsumer.class, (c) -> c.onEnumerationResult(
                    null /* identifier */, 0 /* remaining */));
        }
    }

    @Override
    public void onFeaturesRetrieved(byte[] features) {
        handleResponse(FaceGetFeatureClient.class, (c) -> c.onFeatureGet(true /* success */,
                features));
    }

    @Override
    public void onFeatureSet(byte feature) {
        handleResponse(FaceSetFeatureClient.class, (c) -> c.onFeatureSet(true /* success */));
    }

    @Override
    public void onEnrollmentsRemoved(int[] enrollmentIds) {
        if (enrollmentIds.length > 0) {
            for (int i = 0; i < enrollmentIds.length; i++) {
                final Face face = new Face("" /* name */, enrollmentIds[i], mSensorId);
                final int finalI = i;
                handleResponse(RemovalConsumer.class,
                        (c) -> c.onRemoved(face,
                                enrollmentIds.length - finalI - 1 /* remaining */));
            }
        } else {
            handleResponse(RemovalConsumer.class, (c) -> c.onRemoved(null /* identifier */,
                    0 /* remaining */));
        }
    }

    @Override
    public void onAuthenticatorIdRetrieved(long authenticatorId) {
        handleResponse(FaceGetAuthenticatorIdClient.class, (c) -> c.onAuthenticatorIdRetrieved(
                authenticatorId));
    }

    @Override
    public void onAuthenticatorIdInvalidated(long newAuthenticatorId) {
        handleResponse(FaceInvalidationClient.class, (c) -> c.onAuthenticatorIdInvalidated(
                newAuthenticatorId));
    }

    /**
     * Handles acquired messages sent by the HAL (specifically for HIDL HAL).
     */
    public void onAcquired(int acquiredInfo, int vendorCode) {
        handleResponse(AcquisitionClient.class, (c) -> c.onAcquired(acquiredInfo, vendorCode));
    }

    /**
     * Handles lockout changed messages sent by the HAL (specifically for HIDL HAL).
     */
    public void onLockoutChanged(long duration) {
        mScheduler.getHandler().post(() -> {
            @LockoutTracker.LockoutMode final int lockoutMode;
            if (duration == 0) {
                lockoutMode = LockoutTracker.LOCKOUT_NONE;
            } else if (duration == -1 || duration == Long.MAX_VALUE) {
                lockoutMode = LockoutTracker.LOCKOUT_PERMANENT;
            } else {
                lockoutMode = LockoutTracker.LOCKOUT_TIMED;
            }

            mLockoutTracker.setLockoutModeForUser(mUserId, lockoutMode);

            if (duration == 0) {
                mLockoutResetDispatcher.notifyLockoutResetCallbacks(mSensorId);
            }
        });
    }

    /**
     * Handle clients which are not supported in HIDL HAL. For face, FaceInvalidationClient
     * is the only AIDL client which is not supported in HIDL.
     */
    public void onUnsupportedClientScheduled() {
        Slog.e(TAG, "FaceInvalidationClient is not supported in the HAL.");
        handleResponse(FaceInvalidationClient.class, BaseClientMonitor::cancel);
    }

    private <T> void handleResponse(@NonNull Class<T> className,
            @NonNull Consumer<T> action) {
        handleResponse(className, action, null /* alternateAction */);
    }

    private <T> void handleResponse(@NonNull Class<T> className,
            @NonNull Consumer<T> actionIfClassMatchesClient,
            @Nullable Consumer<BaseClientMonitor> alternateAction) {
        mScheduler.getHandler().post(() -> {
            final BaseClientMonitor client = mScheduler.getCurrentClient();
            if (className.isInstance(client)) {
                actionIfClassMatchesClient.accept((T) client);
            } else {
                Slog.d(TAG, "Current client is not an instance of " + className.getName());
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
