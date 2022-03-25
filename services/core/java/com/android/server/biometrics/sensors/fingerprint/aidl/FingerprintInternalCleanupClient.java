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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.fingerprint.Fingerprint;
import android.os.IBinder;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.InternalCleanupClient;
import com.android.server.biometrics.sensors.InternalEnumerateClient;
import com.android.server.biometrics.sensors.RemovalClient;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fingerprint-specific internal cleanup client supporting the
 * {@link android.hardware.biometrics.fingerprint.IFingerprint} AIDL interface.
 */
class FingerprintInternalCleanupClient extends InternalCleanupClient<Fingerprint, AidlSession> {

    FingerprintInternalCleanupClient(@NonNull Context context,
            @NonNull Supplier<AidlSession> lazyDaemon,
            int userId, @NonNull String owner, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull List<Fingerprint> enrolledList,
            @NonNull FingerprintUtils utils, @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, userId, owner, sensorId, logger, biometricContext,
                enrolledList, utils, authenticatorIds);
    }

    @Override
    protected InternalEnumerateClient<AidlSession> getEnumerateClient(Context context,
            Supplier<AidlSession> lazyDaemon, IBinder token, int userId, String owner,
            List<Fingerprint> enrolledList, BiometricUtils<Fingerprint> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        return new FingerprintInternalEnumerateClient(context, lazyDaemon, token, userId, owner,
                enrolledList, utils, sensorId,
                logger.swapAction(context, BiometricsProtoEnums.ACTION_ENUMERATE),
                biometricContext);
    }

    @Override
    protected RemovalClient<Fingerprint, AidlSession> getRemovalClient(Context context,
            Supplier<AidlSession> lazyDaemon, IBinder token, int biometricId, int userId,
            String owner, BiometricUtils<Fingerprint> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            Map<Integer, Long> authenticatorIds) {
        return new FingerprintRemovalClient(context, lazyDaemon, token,
                null /* ClientMonitorCallbackConverter */, new int[] {biometricId}, userId, owner,
                utils, sensorId, logger.swapAction(context, BiometricsProtoEnums.ACTION_REMOVE),
                biometricContext, authenticatorIds);
    }
}
