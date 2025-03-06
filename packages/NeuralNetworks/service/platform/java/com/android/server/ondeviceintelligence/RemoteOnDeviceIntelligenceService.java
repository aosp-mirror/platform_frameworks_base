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
import android.service.ondeviceintelligence.IOnDeviceIntelligenceService;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService;

import com.android.internal.infra.ServiceConnector;

import java.util.concurrent.TimeUnit;

/**
 * Manages the connection to the remote on-device intelligence service. Also, handles unbinding
 * logic set by the service implementation via a Secure Settings flag.
 */
public class RemoteOnDeviceIntelligenceService extends
        ServiceConnector.Impl<IOnDeviceIntelligenceService> {
    private static final long LONG_TIMEOUT = TimeUnit.HOURS.toMillis(4);
    private static final String TAG =
            RemoteOnDeviceIntelligenceService.class.getSimpleName();

    RemoteOnDeviceIntelligenceService(Context context, ComponentName serviceName,
            int userId) {
        super(context, new Intent(
                        OnDeviceIntelligenceService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IOnDeviceIntelligenceService.Stub::asInterface);

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
                Settings.Secure.ON_DEVICE_INTELLIGENCE_UNBIND_TIMEOUT_MS,
                TimeUnit.SECONDS.toMillis(30),
                mContext.getUserId());
    }
}
