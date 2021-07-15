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

package com.android.server.wifi;

import android.annotation.SystemApi;
import android.os.SystemService;
import android.util.Log;

import java.util.NoSuchElementException;

/**
 * Wrapper to start/stop supplicant daemon using init system.
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public class SupplicantManager {
    private static final String TAG = "SupplicantManager";

    private static final String WPA_SUPPLICANT_DAEMON_NAME = "wpa_supplicant";

    private SupplicantManager() {}

    /**
     * Start the wpa_supplicant daemon.
     * Note: This uses the init system to start the "wpa_supplicant" service.
     *
     * @throws NoSuchElementException if supplicant daemon failed to start
     */
    public static void start() {
        try {
            SystemService.start(WPA_SUPPLICANT_DAEMON_NAME);
        } catch (RuntimeException e) {
            // likely a "failed to set system property" runtime exception
            throw new NoSuchElementException("Failed to start Supplicant");
        }
    }

    /**
     * Stop the wpa_supplicant daemon.
     * Note: This uses the init system to stop the "wpa_supplicant" service.
     */
    public static void stop() {
        try {
            SystemService.stop(WPA_SUPPLICANT_DAEMON_NAME);
        } catch (RuntimeException e) {
            // likely a "failed to set system property" runtime exception
            Log.w(TAG, "Failed to stop Supplicant", e);
        }
    }
}
