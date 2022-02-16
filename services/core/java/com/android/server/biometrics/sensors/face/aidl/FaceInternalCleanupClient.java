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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.biometrics.face.IFace;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.os.IBinder;

import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalCleanupClient;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.sensors.RemovalClient;

import java.util.List;
import java.util.Map;

/**
 * Face-specific internal cleanup client for the {@link IFace} AIDL HAL interface.
 */
class FaceInternalCleanupClient extends InternalCleanupClient<Face, ISession> {

    FaceInternalCleanupClient(@NonNull Context context,
            @NonNull LazyDaemon<ISession> lazyDaemon, int userId, @NonNull String owner,
            int sensorId, @NonNull List<Face> enrolledList, @NonNull BiometricUtils<Face> utils,
            @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, userId, owner, sensorId, BiometricsProtoEnums.MODALITY_FACE,
                enrolledList, utils, authenticatorIds);
    }

    @Override
    protected InternalEnumerateClient<ISession> getEnumerateClient(Context context,
            LazyDaemon<ISession> lazyDaemon, IBinder token, int userId, String owner,
            List<Face> enrolledList, BiometricUtils<Face> utils, int sensorId) {
        return new FaceInternalEnumerateClient(context, lazyDaemon, token, userId, owner,
                enrolledList, utils, sensorId);
    }

    @Override
    protected RemovalClient<Face, ISession> getRemovalClient(Context context,
            LazyDaemon<ISession> lazyDaemon, IBinder token,
            int biometricId, int userId, String owner, BiometricUtils<Face> utils, int sensorId,
            Map<Integer, Long> authenticatorIds) {
        // Internal remove does not need to send results to anyone. Cleanup (enumerate + remove)
        // is all done internally.
        return new FaceRemovalClient(context, lazyDaemon, token,
                null /* ClientMonitorCallbackConverter */, new int[] {biometricId}, userId, owner,
                utils, sensorId, authenticatorIds);
    }
}
