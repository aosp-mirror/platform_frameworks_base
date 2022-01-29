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
import android.content.Context;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.hardware.face.Face;
import android.os.IBinder;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalCleanupClient;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.sensors.RemovalClient;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Face-specific internal cleanup client supporting the
 * {@link android.hardware.biometrics.face.V1_0} HIDL interface.
 */
class FaceInternalCleanupClient extends InternalCleanupClient<Face, IBiometricsFace> {

    FaceInternalCleanupClient(@NonNull Context context,
            @NonNull Supplier<IBiometricsFace> lazyDaemon, int userId, @NonNull String owner,
            int sensorId, @NonNull BiometricLogger logger,
            @NonNull BiometricContext biometricContext, @NonNull List<Face> enrolledList,
            @NonNull BiometricUtils<Face> utils, @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, userId, owner, sensorId, logger, biometricContext,
                enrolledList, utils, authenticatorIds);
    }

    @Override
    protected InternalEnumerateClient<IBiometricsFace> getEnumerateClient(Context context,
            Supplier<IBiometricsFace> lazyDaemon, IBinder token, int userId, String owner,
            List<Face> enrolledList, BiometricUtils<Face> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        return new FaceInternalEnumerateClient(context, lazyDaemon, token, userId, owner,
                enrolledList, utils, sensorId, logger, biometricContext);
    }

    @Override
    protected RemovalClient<Face, IBiometricsFace> getRemovalClient(Context context,
            Supplier<IBiometricsFace> lazyDaemon, IBinder token,
            int biometricId, int userId, String owner, BiometricUtils<Face> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            Map<Integer, Long> authenticatorIds) {
        // Internal remove does not need to send results to anyone. Cleanup (enumerate + remove)
        // is all done internally.
        return new FaceRemovalClient(context, lazyDaemon, token,
                null /* ClientMonitorCallbackConverter */, biometricId, userId, owner, utils,
                sensorId, logger, biometricContext, authenticatorIds);
    }
}
