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

import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalCleanupClient;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.sensors.RemovalClient;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

class FaceInternalCleanupClient extends InternalCleanupClient<Face, IFaceService> {
    FaceInternalCleanupClient(Context context, Supplier<IFaceService> lazyDaemon, int userId, String owner, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, List<Face> enrolledList, BiometricUtils<Face> utils, Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, userId, owner, sensorId, biometricLogger, biometricContext, enrolledList, utils, authenticatorIds);
    }

    @Override
    protected InternalEnumerateClient<IFaceService> getEnumerateClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, int userId, String owner, List<Face> enrolledList, BiometricUtils<Face> utils, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext) {
        return new FaceInternalEnumerateClient(context, lazyDaemon, token, userId, owner, enrolledList, utils, sensorId, biometricLogger, biometricContext);
    }

    @Override
    protected RemovalClient<Face, IFaceService> getRemovalClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, int biometricId, int userId, String owner, BiometricUtils<Face> utils, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, Map<Integer, Long> authenticatorIds) {
        return new FaceRemovalClient(context, lazyDaemon, token, null, biometricId, userId, owner, utils, sensorId, biometricLogger, biometricContext, authenticatorIds);
    }
}
