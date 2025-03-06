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

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE_MODULE;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;

/**
 * Exposes APIs to {@code system_server} components outside of the module boundaries.
 * <p> This API should be access using {@link com.android.server.LocalManagerRegistry}. </p>
 *
 * @hide
 */
@SystemApi(client = Client.SYSTEM_SERVER)
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE_MODULE)
public interface OnDeviceIntelligenceManagerLocal {
    /**
     * Gets the uid for the process that is currently hosting the
     * {@link android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService} registered on
     * the device.
     */
    int getInferenceServiceUid();
}