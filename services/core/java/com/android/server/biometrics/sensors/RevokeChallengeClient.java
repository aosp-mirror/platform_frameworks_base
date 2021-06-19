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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;

import com.android.server.biometrics.BiometricsProto;

public abstract class RevokeChallengeClient<T> extends HalClientMonitor<T> {

    public RevokeChallengeClient(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @NonNull IBinder token, int userId, @NonNull String owner, int sensorId) {
        super(context, lazyDaemon, token, null /* listener */, userId, owner,
                0 /* cookie */, sensorId, BiometricsProtoEnums.MODALITY_UNKNOWN,
                BiometricsProtoEnums.ACTION_UNKNOWN, BiometricsProtoEnums.CLIENT_UNKNOWN);
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    public void start(@NonNull Callback callback) {
        super.start(callback);

        startHalOperation();
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_REVOKE_CHALLENGE;
    }
}
