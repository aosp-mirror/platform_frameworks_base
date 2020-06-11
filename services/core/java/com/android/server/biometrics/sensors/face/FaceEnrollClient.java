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

package com.android.server.biometrics.sensors.face;

import android.content.Context;
import android.hardware.face.FaceManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;

import com.android.internal.R;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BiometricServiceBase;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.Constants;
import com.android.server.biometrics.sensors.EnrollClient;

/**
 * Face-specific enrollment client.
 */
public class FaceEnrollClient extends EnrollClient {

    private final int[] mEnrollIgnoreList;
    private final int[] mEnrollIgnoreListVendor;

    FaceEnrollClient(Context context,
            Constants constants,
            BiometricServiceBase.DaemonWrapper daemon,
            IBinder token,
            ClientMonitorCallbackConverter listener, int userId,
            int groupId, byte[] cryptoToken, boolean restricted, String owner,
            BiometricUtils utils, int[] disabledFeatures,
            int timeoutSec, int statsModality, PowerManager powerManager,
            Surface surface, int sensorId, boolean shouldVibrate) {
        super(context, constants, daemon, token, listener, userId, groupId, cryptoToken, restricted,
                owner, utils, disabledFeatures, timeoutSec, statsModality, powerManager, surface,
                sensorId, shouldVibrate);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
    }

    @Override
    public boolean onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend;
        if (acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR) {
            shouldSend = !Utils.listContains(mEnrollIgnoreListVendor, vendorCode);
        } else {
            shouldSend = !Utils.listContains(mEnrollIgnoreList, acquireInfo);
        }
        return onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }
}
