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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;
import android.os.IBinder;

import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalCleanupClient;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.sensors.RemovalClient;

import java.util.List;

/**
 * Face-specific internal cleanup client supporting the
 * {@link android.hardware.biometrics.face.V1_0} and {@link android.hardware.biometrics.face.V1_1}
 * HIDL interfaces.
 */
class FaceInternalCleanupClient extends InternalCleanupClient<IBiometricsFace> {

    FaceInternalCleanupClient(@NonNull Context context, int userId, boolean restricted,
            @NonNull String owner, int sensorId, int statsModality,
            @NonNull List<? extends BiometricAuthenticator.Identifier> enrolledList,
            @NonNull BiometricUtils utils) {
        super(context, userId, restricted, owner, sensorId, statsModality,
                enrolledList, utils);
    }

    @Override
    protected InternalEnumerateClient<IBiometricsFace> getEnumerateClient(Context context,
            IBinder token, int userId, boolean restricted, String owner,
            List<? extends BiometricAuthenticator.Identifier> enrolledList,
            BiometricUtils utils, int sensorId, int statsModality) {
        return new FaceInternalEnumerateClient(context, token, userId, restricted, owner,
                enrolledList, utils, sensorId, statsModality);
    }

    @Override
    protected RemovalClient<IBiometricsFace> getRemovalClient(Context context, IBinder token,
            int biometricId, int userId, boolean restricted, String owner, BiometricUtils utils,
            int sensorId, int statsModality) {
        // Internal remove does not need to send results to anyone. Cleanup (enumerate + remove)
        // is all done internally.
        return new FaceRemovalClient(context, token, null /* ClientMonitorCallbackConverter */,
                biometricId, userId, restricted, owner, utils, sensorId, statsModality);
    }
}
