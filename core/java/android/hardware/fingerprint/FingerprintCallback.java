/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.hardware.fingerprint;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR_BASE;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_VENDOR_BASE;
import static android.hardware.fingerprint.FingerprintManager.getAcquiredString;
import static android.hardware.fingerprint.FingerprintManager.getErrorString;

import android.annotation.IntDef;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager.AuthenticationCallback;
import android.hardware.fingerprint.FingerprintManager.AuthenticationResult;
import android.hardware.fingerprint.FingerprintManager.CryptoObject;
import android.hardware.fingerprint.FingerprintManager.EnrollmentCallback;
import android.hardware.fingerprint.FingerprintManager.FingerprintDetectionCallback;
import android.hardware.fingerprint.FingerprintManager.GenerateChallengeCallback;
import android.hardware.fingerprint.FingerprintManager.RemovalCallback;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Encapsulates callbacks and client specific information for each fingerprint related request.
 * @hide
 */
public class FingerprintCallback {
    private static final String TAG = "FingerprintCallback";
    public static final int REMOVE_SINGLE = 1;
    public static final int REMOVE_ALL = 2;
    @IntDef({REMOVE_SINGLE, REMOVE_ALL})
    public @interface RemoveRequest {}
    @Nullable
    private AuthenticationCallback mAuthenticationCallback;
    @Nullable
    private EnrollmentCallback mEnrollmentCallback;
    @Nullable
    private RemovalCallback mRemovalCallback;
    @Nullable
    private GenerateChallengeCallback mGenerateChallengeCallback;
    @Nullable
    private FingerprintDetectionCallback mFingerprintDetectionCallback;
    @Nullable
    private CryptoObject mCryptoObject;
    @Nullable
    private @RemoveRequest int mRemoveRequest;
    @Nullable
    private Fingerprint mRemoveFingerprint;

    /**
     * Construction for fingerprint authentication client callback.
     */
    FingerprintCallback(@NonNull AuthenticationCallback authenticationCallback,
            @Nullable CryptoObject cryptoObject) {
        mAuthenticationCallback = authenticationCallback;
        mCryptoObject = cryptoObject;
    }

    /**
     * Construction for fingerprint detect client callback.
     */
    FingerprintCallback(@NonNull FingerprintDetectionCallback fingerprintDetectionCallback) {
        mFingerprintDetectionCallback = fingerprintDetectionCallback;
    }

    /**
     * Construction for fingerprint enroll client callback.
     */
    FingerprintCallback(@NonNull EnrollmentCallback enrollmentCallback) {
        mEnrollmentCallback = enrollmentCallback;
    }

    /**
     * Construction for fingerprint generate challenge client callback.
     */
    FingerprintCallback(@NonNull GenerateChallengeCallback generateChallengeCallback) {
        mGenerateChallengeCallback = generateChallengeCallback;
    }

    /**
     * Construction for fingerprint removal client callback.
     */
    FingerprintCallback(@NonNull RemovalCallback removalCallback, @RemoveRequest int removeRequest,
            @Nullable Fingerprint removeFingerprint) {
        mRemovalCallback = removalCallback;
        mRemoveRequest = removeRequest;
        mRemoveFingerprint = removeFingerprint;
    }

    /**
     * Propagate enroll progress via the callback.
     * @param remaining number of enrollment steps remaining
     */
    public void sendEnrollResult(int remaining) {
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentProgress(remaining);
        }
    }

    /**
     * Propagate remove face completed via the callback.
     * @param fingerprint removed identifier
     * @param remaining number of face enrollments remaining
     */
    public void sendRemovedResult(@Nullable Fingerprint fingerprint, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }

        if (mRemoveRequest == REMOVE_SINGLE) {
            if (fingerprint == null) {
                Slog.e(TAG, "Received MSG_REMOVED, but fingerprint is null");
                return;
            }

            if (mRemoveFingerprint == null) {
                Slog.e(TAG, "Missing fingerprint");
                return;
            }

            final int fingerId = fingerprint.getBiometricId();
            int reqFingerId = mRemoveFingerprint.getBiometricId();
            if (reqFingerId != 0 && fingerId != 0 && fingerId != reqFingerId) {
                Slog.w(TAG, "Finger id didn't match: " + fingerId + " != " + reqFingerId);
                return;
            }
        }

        mRemovalCallback.onRemovalSucceeded(fingerprint, remaining);
    }

    /**
     * Propagate authentication succeeded via the callback.
     * @param fingerprint matched identifier
     * @param userId id of the corresponding user
     * @param isStrongBiometric if the sensor is strong or not
     */
    public void sendAuthenticatedSucceeded(@NonNull Fingerprint fingerprint, int userId,
            boolean isStrongBiometric) {
        if (mAuthenticationCallback == null) {
            Slog.e(TAG, "Authentication succeeded but callback is null.");
            return;
        }

        final AuthenticationResult result = new AuthenticationResult(mCryptoObject, fingerprint,
                userId, isStrongBiometric);
        mAuthenticationCallback.onAuthenticationSucceeded(result);
    }

    /**
     * Propagate authentication failed via the callback.
     */
    public void sendAuthenticatedFailed() {
        if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationFailed();
        }
    }

    /**
     * Propagate acquired result via the callback.
     * @param context corresponding context
     * @param acquireInfo represents the framework acquired id
     * @param vendorCode represents the vendor acquired code
     */
    public void sendAcquiredResult(@NonNull Context context, int acquireInfo, int vendorCode) {
        if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);
        }
        if (mEnrollmentCallback != null && acquireInfo != FINGERPRINT_ACQUIRED_START) {
            mEnrollmentCallback.onAcquired(acquireInfo == FINGERPRINT_ACQUIRED_GOOD);
        }
        final String msg = getAcquiredString(context, acquireInfo, vendorCode);
        if (msg == null || msg.isEmpty()) {
            return;
        }
        // emulate HAL 2.1 behavior and send real acquiredInfo
        final int clientInfo = acquireInfo == FINGERPRINT_ACQUIRED_VENDOR
                ? (vendorCode + FINGERPRINT_ACQUIRED_VENDOR_BASE) : acquireInfo;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentHelp(clientInfo, msg);
        } else if (mAuthenticationCallback != null) {
            if (acquireInfo != FINGERPRINT_ACQUIRED_START) {
                mAuthenticationCallback.onAuthenticationHelp(clientInfo, msg);
            }
        }
    }

    /**
     * Propagate errors via the callback.
     * @param context corresponding context
     * @param errMsgId represents the framework error id
     * @param vendorCode represents the vendor error code
     */
    public void sendErrorResult(@NonNull Context context, int errMsgId, int vendorCode) {
        // emulate HAL 2.1 behavior and send real errMsgId
        final int clientErrMsgId = errMsgId == FINGERPRINT_ERROR_VENDOR
                ? (vendorCode + FINGERPRINT_ERROR_VENDOR_BASE) : errMsgId;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mRemovalCallback != null) {
            mRemovalCallback.onRemovalError(mRemoveFingerprint, clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mFingerprintDetectionCallback != null) {
            mFingerprintDetectionCallback.onDetectionError(errMsgId);
            mFingerprintDetectionCallback = null;
        }
    }

    /**
     * Propagate challenge generated completed via the callback.
     * @param sensorId id of the corresponding sensor
     * @param userId id of the corresponding sensor
     * @param challenge value of the challenge generated
     */
    public void sendChallengeGenerated(long challenge, int sensorId, int userId) {
        if (mGenerateChallengeCallback == null) {
            Slog.e(TAG, "sendChallengeGenerated, callback null");
            return;
        }
        mGenerateChallengeCallback.onChallengeGenerated(sensorId, userId, challenge);
    }

    /**
     * Propagate fingerprint detected completed via the callback.
     * @param sensorId id of the corresponding sensor
     * @param userId id of the corresponding user
     * @param isStrongBiometric if the sensor is strong or not
     */
    public void sendFingerprintDetected(int sensorId, int userId, boolean isStrongBiometric) {
        if (mFingerprintDetectionCallback == null) {
            Slog.e(TAG, "sendFingerprintDetected, callback null");
            return;
        }
        mFingerprintDetectionCallback.onFingerprintDetected(sensorId, userId, isStrongBiometric);
    }

    /**
     * Propagate udfps pointer down via the callback.
     * @param sensorId id of the corresponding sensor
     */
    public void sendUdfpsPointerDown(int sensorId) {
        if (mAuthenticationCallback == null) {
            Slog.e(TAG, "sendUdfpsPointerDown, callback null");
        } else {
            mAuthenticationCallback.onUdfpsPointerDown(sensorId);
        }

        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onUdfpsPointerDown(sensorId);
        }
    }

    /**
     * Propagate udfps pointer up via the callback.
     * @param sensorId id of the corresponding sensor
     */
    public void sendUdfpsPointerUp(int sensorId) {
        if (mAuthenticationCallback == null) {
            Slog.e(TAG, "sendUdfpsPointerUp, callback null");
        } else {
            mAuthenticationCallback.onUdfpsPointerUp(sensorId);
        }
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onUdfpsPointerUp(sensorId);
        }
    }

    /**
     * Propagate udfps overlay shown via the callback.
     */
    public void sendUdfpsOverlayShown() {
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onUdfpsOverlayShown();
        }
    }
}
