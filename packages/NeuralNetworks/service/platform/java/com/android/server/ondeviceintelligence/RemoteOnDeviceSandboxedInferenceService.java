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
import android.provider.Settings;
import android.service.ondeviceintelligence.IOnDeviceSandboxedInferenceService;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;

import com.android.internal.infra.ServiceConnector;

import java.util.concurrent.TimeUnit;


/**
 * Manages the connection to the remote on-device sand boxed inference service. Also, handles
 * unbinding
 * logic set by the service implementation via a SecureSettings flag.
 */
public class RemoteOnDeviceSandboxedInferenceService extends
        ServiceConnector.Impl<IOnDeviceSandboxedInferenceService> {
    private static final long LONG_TIMEOUT = TimeUnit.HOURS.toMillis(1);

    /**
     * Creates an instance of {@link ServiceConnector}
     *
     * See {@code protected} methods for optional parameters you can override.
     *
     * @param context to be used for {@link Context#bindServiceAsUser binding} and
     *                {@link Context#unbindService unbinding}
     * @param userId  to be used for {@link Context#bindServiceAsUser binding}
     */
    RemoteOnDeviceSandboxedInferenceService(Context context, ComponentName serviceName,
            int userId) {
        super(context, new Intent(
                        OnDeviceSandboxedInferenceService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IOnDeviceSandboxedInferenceService.Stub::asInterface);

        // Bind right away
        connect();
    }

    @Override
    protected long getRequestTimeoutMs() {
        return LONG_TIMEOUT;
    }


    @Override
    protected long getAutoDisconnectTimeoutMs() {
        return Settings.Secure.getLongForUser(mContext.getContentResolver(),
                Settings.Secure.ON_DEVICE_INFERENCE_UNBIND_TIMEOUT_MS,
                TimeUnit.SECONDS.toMillis(30),
                mContext.getUserId());
    }
}
