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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.face.EnrollmentStageConfig;
import android.hardware.biometrics.face.FaceEnrollOptions;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.OptionalBool;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.common.NativeHandle;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.sensors.face.aidl.AidlConversionUtils;
import com.android.server.biometrics.sensors.face.aidl.AidlResponseHandler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Adapter to convert HIDL methods into AIDL interface {@link ISession}.
 */
public class HidlToAidlSessionAdapter implements ISession {

    private static final String TAG = "HidlToAidlSessionAdapter";

    private static final int CHALLENGE_TIMEOUT_SEC = 600;
    @DurationMillisLong
    private static final int GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS = 60 * 1000;
    @DurationMillisLong
    private static final int GENERATE_CHALLENGE_COUNTER_TTL_MILLIS = CHALLENGE_TIMEOUT_SEC * 1000;
    private static final int INVALID_VALUE = -1;
    @VisibleForTesting static final int ENROLL_TIMEOUT_SEC = 75;

    private final Clock mClock;
    private final List<Long> mGeneratedChallengeCount = new ArrayList<>();
    private final int mUserId;
    private final Context mContext;

    private long mGenerateChallengeCreatedAt = INVALID_VALUE;
    private long mGenerateChallengeResult = INVALID_VALUE;
    @NonNull private Supplier<IBiometricsFace> mSession;
    private HidlToAidlCallbackConverter mHidlToAidlCallbackConverter;
    private int mFeature = INVALID_VALUE;

    public HidlToAidlSessionAdapter(Context context, Supplier<IBiometricsFace> session,
            int userId, AidlResponseHandler aidlResponseHandler) {
        this(context, session, userId, aidlResponseHandler, Clock.systemUTC());
    }

    HidlToAidlSessionAdapter(Context context, Supplier<IBiometricsFace> session, int userId,
            AidlResponseHandler aidlResponseHandler, Clock clock) {
        mSession = session;
        mUserId = userId;
        mContext = context;
        mClock = clock;
        setCallback(aidlResponseHandler);
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    @Override
    public void generateChallenge() throws RemoteException {
        incrementChallengeCount();
        if (isGeneratedChallengeCacheValid()) {
            Slog.d(TAG, "Current challenge is cached and will be reused");
            mHidlToAidlCallbackConverter.onChallengeGenerated(mGenerateChallengeResult);
            return;
        }
        mGenerateChallengeCreatedAt = mClock.millis();
        mGenerateChallengeResult = mSession.get().generateChallenge(CHALLENGE_TIMEOUT_SEC).value;
        mHidlToAidlCallbackConverter.onChallengeGenerated(mGenerateChallengeResult);
    }

    @Override
    public void revokeChallenge(long challenge) throws RemoteException {
        final boolean shouldRevoke = decrementChallengeCount() == 0;
        if (!shouldRevoke) {
            Slog.w(TAG, "scheduleRevokeChallenge skipped - challenge still in use: "
                    + mGeneratedChallengeCount);
            mHidlToAidlCallbackConverter.onError(0 /* deviceId */, mUserId,
                    BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS, 0 /* vendorCode */);
            return;
        }
        mGenerateChallengeCreatedAt = INVALID_VALUE;
        mGenerateChallengeResult = INVALID_VALUE;
        mSession.get().revokeChallenge();
        mHidlToAidlCallbackConverter.onChallengeRevoked(0L);
    }

    @Override
    public EnrollmentStageConfig[] getEnrollmentConfig(byte enrollmentType) throws RemoteException {
        Slog.e(TAG, "getEnrollmentConfig unsupported in HIDL");
        return null;
    }

    @Override
    public ICancellationSignal enroll(HardwareAuthToken hat, byte type, byte[] features,
            NativeHandle previewSurface) throws RemoteException {
        final ArrayList<Byte> token = new ArrayList<>();
        final byte[] hardwareAuthTokenArray = HardwareAuthTokenUtils.toByteArray(hat);
        for (byte b : hardwareAuthTokenArray) {
            token.add(b);
        }
        final ArrayList<Integer> disabledFeatures = new ArrayList<>();
        for (byte b: features) {
            disabledFeatures.add(AidlConversionUtils.convertAidlToFrameworkFeature(b));
        }
        mSession.get().enroll(token, ENROLL_TIMEOUT_SEC, disabledFeatures);
        return new Cancellation();
    }

    @Override
    public ICancellationSignal authenticate(long operationId) throws RemoteException {
        mSession.get().authenticate(operationId);
        return new Cancellation();
    }

    @Override
    public ICancellationSignal detectInteraction() throws RemoteException {
        mSession.get().authenticate(0);
        return new Cancellation();
    }

    @Override
    public void enumerateEnrollments() throws RemoteException {
        mSession.get().enumerate();
    }

    @Override
    public void removeEnrollments(int[] enrollmentIds) throws RemoteException {
        mSession.get().remove(enrollmentIds[0]);
    }

    /**
     * Needs to be called before getFeatures is invoked.
     */
    public void setFeature(int feature) {
        mFeature = feature;
    }

    @Override
    public void getFeatures() throws RemoteException {
        final int faceId = getFaceId();
        if (faceId == INVALID_VALUE || mFeature == INVALID_VALUE) {
            return;
        }

        final OptionalBool result = mSession.get()
                .getFeature(mFeature, faceId);

        if (result.status == Status.OK && result.value) {
            mHidlToAidlCallbackConverter.onFeatureGet(new byte[]{AidlConversionUtils
                    .convertFrameworkToAidlFeature(mFeature)});
        } else if (result.status == Status.OK) {
            mHidlToAidlCallbackConverter.onFeatureGet(new byte[]{});
        } else {
            mHidlToAidlCallbackConverter.onError(0 /* deviceId */, mUserId,
                    BiometricFaceConstants.FACE_ERROR_UNKNOWN, 0 /* vendorCode */);
        }

        mFeature = INVALID_VALUE;
    }

    @Override
    public void setFeature(HardwareAuthToken hat, byte feature, boolean enabled)
            throws RemoteException {
        final int faceId = getFaceId();
        if (faceId == INVALID_VALUE) {
            return;
        }
        ArrayList<Byte> hardwareAuthTokenList = new ArrayList<>();
        for (byte b: HardwareAuthTokenUtils.toByteArray(hat)) {
            hardwareAuthTokenList.add(b);
        }
        final int result = mSession.get().setFeature(
                AidlConversionUtils.convertAidlToFrameworkFeature(feature),
                enabled, hardwareAuthTokenList, faceId);
        if (result == Status.OK) {
            mHidlToAidlCallbackConverter.onFeatureSet(feature);
        } else {
            mHidlToAidlCallbackConverter.onError(0 /* deviceId */, mUserId,
                    BiometricFaceConstants.FACE_ERROR_UNKNOWN, 0 /* vendorCode */);
        }
    }

    @Override
    public void getAuthenticatorId() throws RemoteException {
        long authenticatorId = mSession.get().getAuthenticatorId().value;
        mHidlToAidlCallbackConverter.onAuthenticatorIdRetrieved(authenticatorId);
    }

    @Override
    public void invalidateAuthenticatorId() throws RemoteException {
        Slog.e(TAG, "invalidateAuthenticatorId unsupported in HIDL");
        mHidlToAidlCallbackConverter.onUnsupportedClientScheduled();
    }

    @Override
    public void resetLockout(HardwareAuthToken hat) throws RemoteException {
        ArrayList<Byte> hardwareAuthToken = new ArrayList<>();
        for (byte b : HardwareAuthTokenUtils.toByteArray(hat)) {
            hardwareAuthToken.add(b);
        }
        mSession.get().resetLockout(hardwareAuthToken);
    }

    @Override
    public void close() throws RemoteException {
        Slog.e(TAG, "close unsupported in HIDL");
    }

    @Override
    public ICancellationSignal authenticateWithContext(long operationId, OperationContext context)
            throws RemoteException {
        Slog.e(TAG, "authenticateWithContext unsupported in HIDL");
        return authenticate(operationId);
    }

    @Override
    public ICancellationSignal enrollWithContext(HardwareAuthToken hat, byte type, byte[] features,
            NativeHandle previewSurface, OperationContext context) throws RemoteException {
        Slog.e(TAG, "enrollWithContext unsupported in HIDL");
        return enroll(hat, type, features, previewSurface);
    }

    @Override
    public ICancellationSignal detectInteractionWithContext(OperationContext context)
            throws RemoteException {
        Slog.e(TAG, "detectInteractionWithContext unsupported in HIDL");
        return detectInteraction();
    }

    @Override
    public void onContextChanged(OperationContext context) throws RemoteException {
        Slog.e(TAG, "onContextChanged unsupported in HIDL");
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        Slog.e(TAG, "getInterfaceVersion unsupported in HIDL");
        return 0;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        Slog.e(TAG, "getInterfaceHash unsupported in HIDL");
        return null;
    }

    @Override
    public ICancellationSignal enrollWithOptions(FaceEnrollOptions options) {
        Slog.e(TAG, "enrollWithOptions unsupported in HIDL");
        return null;
    }

    private boolean isGeneratedChallengeCacheValid() {
        return mGenerateChallengeCreatedAt != INVALID_VALUE
                && mGenerateChallengeResult != INVALID_VALUE
                && mClock.millis() - mGenerateChallengeCreatedAt
                < GENERATE_CHALLENGE_REUSE_INTERVAL_MILLIS;
    }

    private void incrementChallengeCount() {
        mGeneratedChallengeCount.add(0, mClock.millis());
    }

    private int decrementChallengeCount() {
        final long now = mClock.millis();
        // ignore values that are old in case generate/revoke calls are not matched
        // this doesn't ensure revoke if calls are mismatched but it keeps the list from growing
        mGeneratedChallengeCount.removeIf(x -> now - x > GENERATE_CHALLENGE_COUNTER_TTL_MILLIS);
        if (!mGeneratedChallengeCount.isEmpty()) {
            mGeneratedChallengeCount.remove(0);
        }
        return mGeneratedChallengeCount.size();
    }

    private void setCallback(AidlResponseHandler aidlResponseHandler) {
        mHidlToAidlCallbackConverter = new HidlToAidlCallbackConverter(aidlResponseHandler);
        try {
            if (mSession.get() != null) {
                long halId = mSession.get().setCallback(mHidlToAidlCallbackConverter).value;
                Slog.d(TAG, "Face HAL ready, HAL ID: " + halId);
                if (halId == 0) {
                    Slog.d(TAG, "Unable to set HIDL callback.");
                }
            } else {
                Slog.e(TAG, "Unable to set HIDL callback. HIDL daemon is null.");
            }
        } catch (RemoteException e) {
            Slog.d(TAG, "Failed to set callback");
        }
    }

    private int getFaceId() {
        FaceManager faceManager = mContext.getSystemService(FaceManager.class);
        List<Face> faces = faceManager.getEnrolledFaces(mUserId);
        if (faces.isEmpty()) {
            Slog.d(TAG, "No faces to get feature from.");
            mHidlToAidlCallbackConverter.onError(0 /* deviceId */, mUserId,
                    BiometricFaceConstants.FACE_ERROR_NOT_ENROLLED, 0 /* vendorCode */);
            return INVALID_VALUE;
        }

        return faces.get(0).getBiometricId();
    }

    /**
     * Cancellation in HIDL occurs for the entire session, instead of a specific client.
     */
    private class Cancellation extends ICancellationSignal.Stub {

        Cancellation() {}
        @Override
        public void cancel() throws RemoteException {
            try {
                mSession.get().cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
            }
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return 0;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return null;
        }
    }
}
