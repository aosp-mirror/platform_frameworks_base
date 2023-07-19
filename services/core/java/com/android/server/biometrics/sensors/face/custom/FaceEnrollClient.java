/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.content.Context;
import android.hardware.face.Face;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;

class FaceEnrollClient extends EnrollClient<IFaceService> {
    private static final String TAG = "FaceEnrollClient";
    private final int[] mDisabledFeatures;
    private final int[] mEnrollIgnoreList = getContext().getResources().getIntArray(R.array.config_face_acquire_enroll_ignorelist);
    private final int[] mEnrollIgnoreListVendor = getContext().getResources().getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
    private final Surface mPreviewSurface;

    FaceEnrollClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, ClientMonitorCallbackConverter listener, int userId, byte[] hardwareAuthToken, String owner, BiometricUtils<Face> utils, int[] disabledFeatures, int timeoutSec, Surface previewSurface, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, owner, utils, timeoutSec, sensorId, false /* shouldVibrate */, biometricLogger, biometricContext);
        mDisabledFeatures = Arrays.copyOf(disabledFeatures, disabledFeatures.length);
        mPreviewSurface = previewSurface;
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        if (mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).size() < getContext().getResources().getInteger(R.integer.config_faceMaxTemplatesPerUser)) {
            return false;
        }
        Slog.w(TAG, "Too many faces registered, user: " + getTargetUserId());
        return true;
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        boolean shouldSend;
        if (acquireInfo == 22) {
            shouldSend = !Utils.listContains(mEnrollIgnoreListVendor, vendorCode);
        } else {
            shouldSend = !Utils.listContains(mEnrollIgnoreList, acquireInfo);
        }
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    @Override
    protected void startHalOperation() {
        ArrayList<Byte> token = new ArrayList<>();
        for (byte b : mHardwareAuthToken) {
            token.add(b);
        }
        ArrayList<Integer> disabledFeatures = new ArrayList<>();
        for (int disabledFeature : mDisabledFeatures) {
            disabledFeatures.add(disabledFeature);
        }
        try {
            getFreshDaemon().enroll(ArrayUtils.toByteArray(token), mTimeoutSec, ArrayUtils.toIntArray(disabledFeatures));
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting enroll", e);
            onError(2, 0);
            mCallback.onClientFinished(this, false);
        }
    }

    @Override
    protected void stopHalOperation() {
        try {
            getFreshDaemon().cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            onError(1, 0);
            mCallback.onClientFinished(this, false);
        }
    }
}
