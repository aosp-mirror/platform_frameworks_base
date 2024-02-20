/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.ondeviceintelligence;

import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.ondeviceintelligence.IOnDeviceTrustedInferenceService;
import android.service.ondeviceintelligence.OnDeviceTrustedInferenceService;

import com.android.internal.infra.ServiceConnector;


/**
 * Manages the connection to the remote on-device trusted inference service. Also, handles unbinding
 * logic set by the service implementation via a SecureSettings flag.
 */
public class RemoteOnDeviceTrustedInferenceService extends
        ServiceConnector.Impl<IOnDeviceTrustedInferenceService> {
    /**
     * Creates an instance of {@link ServiceConnector}
     *
     * See {@code protected} methods for optional parameters you can override.
     *
     * @param context to be used for {@link Context#bindServiceAsUser binding} and
     *                {@link Context#unbindService unbinding}
     * @param userId  to be used for {@link Context#bindServiceAsUser binding}
     */
    RemoteOnDeviceTrustedInferenceService(Context context, ComponentName serviceName,
            int userId) {
        super(context, new Intent(
                        OnDeviceTrustedInferenceService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IOnDeviceTrustedInferenceService.Stub::asInterface);

        // Bind right away
        connect();
    }


    @Override
    protected long getAutoDisconnectTimeoutMs() {
        // Disable automatic unbinding.
        // TODO: add logic to fetch this flag via SecureSettings.
        return -1;
    }
}
