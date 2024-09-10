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

package android.hardware.face;

import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_VENDOR;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_VENDOR_BASE;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_VENDOR;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_VENDOR_BASE;
import static android.hardware.face.FaceManager.getAuthHelpMessage;
import static android.hardware.face.FaceManager.getEnrollHelpMessage;
import static android.hardware.face.FaceManager.getErrorString;

import android.content.Context;
import android.hardware.biometrics.CryptoObject;
import android.hardware.face.FaceManager.AuthenticationCallback;
import android.hardware.face.FaceManager.EnrollmentCallback;
import android.hardware.face.FaceManager.FaceDetectionCallback;
import android.hardware.face.FaceManager.GenerateChallengeCallback;
import android.hardware.face.FaceManager.GetFeatureCallback;
import android.hardware.face.FaceManager.RemovalCallback;
import android.hardware.face.FaceManager.SetFeatureCallback;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Encapsulates callbacks and client specific information for each face related request.
 * @hide
 */
public class FaceCallback {
    private static final String TAG = " FaceCallback";

    @Nullable
    private AuthenticationCallback mAuthenticationCallback;
    @Nullable
    private EnrollmentCallback mEnrollmentCallback;
    @Nullable
    private RemovalCallback mRemovalCallback;
    @Nullable
    private GenerateChallengeCallback mGenerateChallengeCallback;
    @Nullable
    private FaceDetectionCallback mFaceDetectionCallback;
    @Nullable
    private SetFeatureCallback mSetFeatureCallback;
    @Nullable
    private GetFeatureCallback mGetFeatureCallback;
    @Nullable
    private Face mRemovalFace;
    @Nullable
    private CryptoObject mCryptoObject;

    /**
     * Construction for face authentication client callback.
     */
    FaceCallback(AuthenticationCallback authenticationCallback, CryptoObject cryptoObject) {
        mAuthenticationCallback = authenticationCallback;
        mCryptoObject = cryptoObject;
    }

    /**
     * Construction for face detect client callback.
     */
    FaceCallback(FaceDetectionCallback faceDetectionCallback) {
        mFaceDetectionCallback = faceDetectionCallback;
    }

    /**
     * Construction for face enroll client callback.
     */
    FaceCallback(EnrollmentCallback enrollmentCallback) {
        mEnrollmentCallback = enrollmentCallback;
    }

    /**
     * Construction for face generate challenge client callback.
     */
    FaceCallback(GenerateChallengeCallback generateChallengeCallback) {
        mGenerateChallengeCallback = generateChallengeCallback;
    }

    /**
     * Construction for face set feature client callback.
     */
    FaceCallback(SetFeatureCallback setFeatureCallback) {
        mSetFeatureCallback = setFeatureCallback;
    }

    /**
     * Construction for face get feature client callback.
     */
    FaceCallback(GetFeatureCallback getFeatureCallback) {
        mGetFeatureCallback = getFeatureCallback;
    }

    /**
     * Construction for single face removal client callback.
     */
    FaceCallback(RemovalCallback removalCallback, Face removalFace) {
        mRemovalCallback = removalCallback;
        mRemovalFace = removalFace;
    }

    /**
     * Construction for all face removal client callback.
     */
    FaceCallback(RemovalCallback removalCallback) {
        mRemovalCallback = removalCallback;
    }

    /**
     * Propagate set feature completed via the callback.
     * @param success if the operation was completed successfully
     * @param feature the feature that was set
     */
    public void sendSetFeatureCompleted(boolean success, int feature) {
        if (mSetFeatureCallback == null) {
            return;
        }
        mSetFeatureCallback.onCompleted(success, feature);
    }

    /**
     * Propagate get feature completed via the callback.
     * @param success if the operation was completed successfully
     * @param features list of features available
     * @param featureState status of the features corresponding to the previous parameter
     */
    public void sendGetFeatureCompleted(boolean success, int[] features, boolean[] featureState) {
        if (mGetFeatureCallback == null) {
            return;
        }
        mGetFeatureCallback.onCompleted(success, features, featureState);
    }

    /**
     * Propagate challenge generated completed via the callback.
     * @param sensorId id of the corresponding sensor
     * @param userId id of the corresponding sensor
     * @param challenge value of the challenge generated
     */
    public void sendChallengeGenerated(int sensorId, int userId, long challenge) {
        if (mGenerateChallengeCallback == null) {
            return;
        }
        mGenerateChallengeCallback.onGenerateChallengeResult(sensorId, userId, challenge);
    }

    /**
     * Propagate face detected completed via the callback.
     * @param sensorId id of the corresponding sensor
     * @param userId id of the corresponding user
     * @param isStrongBiometric if the sensor is strong or not
     */
    public void sendFaceDetected(int sensorId, int userId, boolean isStrongBiometric) {
        if (mFaceDetectionCallback == null) {
            Slog.e(TAG, "sendFaceDetected, callback null");
            return;
        }
        mFaceDetectionCallback.onFaceDetected(sensorId, userId, isStrongBiometric);
    }

    /**
     * Propagate remove face completed via the callback.
     * @param face removed identifier
     * @param remaining number of face enrollments remaining
     */
    public void sendRemovedResult(Face face, int remaining) {
        if (mRemovalCallback == null) {
            return;
        }
        mRemovalCallback.onRemovalSucceeded(face, remaining);
    }

    /**
     * Propagate errors via the callback.
     * @param context corresponding context
     * @param errMsgId represents the framework error id
     * @param vendorCode represents the vendor error code
     */
    public void sendErrorResult(Context context, int errMsgId, int vendorCode) {
        // emulate HAL 2.1 behavior and send real errMsgId
        final int clientErrMsgId = errMsgId == FACE_ERROR_VENDOR
                ? (vendorCode + FACE_ERROR_VENDOR_BASE) : errMsgId;
        if (mEnrollmentCallback != null) {
            mEnrollmentCallback.onEnrollmentError(clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mAuthenticationCallback != null) {
            mAuthenticationCallback.onAuthenticationError(clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mRemovalCallback != null) {
            mRemovalCallback.onRemovalError(mRemovalFace, clientErrMsgId,
                    getErrorString(context, errMsgId, vendorCode));
        } else if (mFaceDetectionCallback != null) {
            mFaceDetectionCallback.onDetectionError(errMsgId);
            mFaceDetectionCallback = null;
        }
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
     * Propagate authentication succeeded via the callback.
     * @param face matched identifier
     * @param userId id of the corresponding user
     * @param isStrongBiometric if the sensor is strong or not
     */
    public void sendAuthenticatedSucceeded(Face face, int userId, boolean isStrongBiometric) {
        if (mAuthenticationCallback != null) {
            final FaceManager.AuthenticationResult result = new FaceManager.AuthenticationResult(
                    mCryptoObject, face, userId, isStrongBiometric);
            mAuthenticationCallback.onAuthenticationSucceeded(result);
        }
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
    public void sendAcquiredResult(Context context, int acquireInfo, int vendorCode) {
        if (mAuthenticationCallback != null) {
            final FaceAuthenticationFrame frame = new FaceAuthenticationFrame(
                    new FaceDataFrame(acquireInfo, vendorCode));
            sendAuthenticationFrame(context, frame);
        } else if (mEnrollmentCallback != null) {
            final FaceEnrollFrame frame = new FaceEnrollFrame(
                    null /* cell */,
                    FaceEnrollStages.UNKNOWN,
                    new FaceDataFrame(acquireInfo, vendorCode));
            sendEnrollmentFrame(context, frame);
        }
    }

    /**
     * Propagate authentication frame via the callback.
     * @param context corresponding context
     * @param frame authentication frame to be sent
     */
    public void sendAuthenticationFrame(@NonNull Context context,
            @Nullable FaceAuthenticationFrame frame) {
        if (frame == null) {
            Slog.w(TAG, "Received null authentication frame");
        } else if (mAuthenticationCallback != null) {
            // TODO(b/178414967): Send additional frame data to callback
            final int acquireInfo = frame.getData().getAcquiredInfo();
            final int vendorCode = frame.getData().getVendorCode();
            final int helpCode = getHelpCode(acquireInfo, vendorCode);
            final String helpMessage = getAuthHelpMessage(context, acquireInfo, vendorCode);
            mAuthenticationCallback.onAuthenticationAcquired(acquireInfo);

            // Ensure that only non-null help messages are sent.
            if (helpMessage != null) {
                mAuthenticationCallback.onAuthenticationHelp(helpCode, helpMessage);
            }
        }
    }

    /**
     * Propagate enrollment via the callback.
     * @param context corresponding context
     * @param frame enrollment frame to be sent
     */
    public void sendEnrollmentFrame(Context context, @Nullable FaceEnrollFrame frame) {
        if (frame == null) {
            Slog.w(TAG, "Received null enrollment frame");
        } else if (mEnrollmentCallback != null) {
            final FaceDataFrame data = frame.getData();
            final int acquireInfo = data.getAcquiredInfo();
            final int vendorCode = data.getVendorCode();
            final int helpCode = getHelpCode(acquireInfo, vendorCode);
            final String helpMessage = getEnrollHelpMessage(context, acquireInfo, vendorCode);
            mEnrollmentCallback.onEnrollmentFrame(
                    helpCode,
                    helpMessage,
                    frame.getCell(),
                    frame.getStage(),
                    data.getPan(),
                    data.getTilt(),
                    data.getDistance());
        }
    }

    private static int getHelpCode(int acquireInfo, int vendorCode) {
        return acquireInfo == FACE_ACQUIRED_VENDOR
                ? vendorCode + FACE_ACQUIRED_VENDOR_BASE
                : acquireInfo;
    }
}
