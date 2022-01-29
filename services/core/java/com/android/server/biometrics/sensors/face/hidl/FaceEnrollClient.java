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

package com.android.server.biometrics.sensors.face.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.biometrics.face.V1_0.Status;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnrollClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Face-specific enroll client supporting the {@link android.hardware.biometrics.face.V1_0} HIDL
 * interface.
 */
public class FaceEnrollClient extends EnrollClient<IBiometricsFace> {

    private static final String TAG = "FaceEnrollClient";

    @NonNull private final int[] mDisabledFeatures;
    @NonNull private final int[] mEnrollIgnoreList;
    @NonNull private final int[] mEnrollIgnoreListVendor;

    FaceEnrollClient(@NonNull Context context, @NonNull Supplier<IBiometricsFace> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String owner, long requestId,
            @NonNull BiometricUtils<Face> utils, @NonNull int[] disabledFeatures, int timeoutSec,
            @Nullable Surface previewSurface, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils,
                timeoutSec, sensorId, false /* shouldVibrate */, logger, biometricContext);
        setRequestId(requestId);
        mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(
                getLogger().createALSCallback(true /* startWithClient */), callback);
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        final int limit = getContext().getResources().getInteger(
                com.android.internal.R.integer.config_faceMaxTemplatesPerUser);
        final int enrolled = mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId())
                .size();
        if (enrolled >= limit) {
            Slog.w(TAG, "Too many faces registered, user: " + getTargetUserId());
            return true;
        }
        return false;
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend;
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            shouldSend = !Utils.listContains(mEnrollIgnoreListVendor, vendorCode);
        } else {
            shouldSend = !Utils.listContains(mEnrollIgnoreList, acquireInfo);
        }
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    @Override
    protected void startHalOperation() {
        final ArrayList<Byte> token = new ArrayList<>();
        for (byte b : mHardwareAuthToken) {
            token.add(b);
        }
        final ArrayList<Integer> disabledFeatures = new ArrayList<>();
        for (int disabledFeature : mDisabledFeatures) {
            disabledFeatures.add(disabledFeature);
        }

        try {
            final int status = getFreshDaemon().enroll(token, mTimeoutSec, disabledFeatures);

            if (status != Status.OK) {
                onError(BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS, 0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    @Override
    protected void stopHalOperation() {
        try {
            getFreshDaemon().cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }
}
