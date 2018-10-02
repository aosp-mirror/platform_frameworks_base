/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.appbinding;

import android.util.KeyValueListParser;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Constants that are configurable via the global settings for {@link AppBindingService}.
 */
class AppBindingConstants {
    private static final String TAG = AppBindingService.TAG;

    private static final String SERVICE_RECONNECT_BACKOFF_SEC_KEY =
            "service_reconnect_backoff_sec";

    private static final String SERVICE_RECONNECT_BACKOFF_INCREASE_KEY =
            "service_reconnect_backoff_increase";

    private static final String SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY =
            "service_reconnect_max_backoff_sec";

    public final String sourceSettings;

    /**
     * The back-off before re-connecting, when a service binding died, due to the app
     * crashing repeatedly.
     */
    public final long SERVICE_RECONNECT_BACKOFF_SEC;

    /**
     * The exponential back-off increase factor when a binding dies multiple times.
     */
    public final double SERVICE_RECONNECT_BACKOFF_INCREASE;

    /**
     * The max back-off
     */
    public final long SERVICE_RECONNECT_MAX_BACKOFF_SEC;

    private AppBindingConstants(String settings) {
        sourceSettings = settings;

        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(settings);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on
            // with defaults.
            Slog.e(TAG, "Bad setting: " + settings);
        }

        long serviceReconnectBackoffSec = parser.getLong(
                SERVICE_RECONNECT_BACKOFF_SEC_KEY, TimeUnit.HOURS.toSeconds(1));

        double serviceReconnectBackoffIncrease = parser.getFloat(
                SERVICE_RECONNECT_BACKOFF_INCREASE_KEY, 2f);

        long serviceReconnectMaxBackoffSec = parser.getLong(
                SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY, TimeUnit.DAYS.toSeconds(1));

        // Set minimum: 5 seconds.
        serviceReconnectBackoffSec = Math.max(5, serviceReconnectBackoffSec);

        // Set minimum: 1.0.
        serviceReconnectBackoffIncrease =
                Math.max(1, serviceReconnectBackoffIncrease);

        // Make sure max >= default back off.
        serviceReconnectMaxBackoffSec = Math.max(serviceReconnectBackoffSec,
                serviceReconnectMaxBackoffSec);

        SERVICE_RECONNECT_BACKOFF_SEC = serviceReconnectBackoffSec;
        SERVICE_RECONNECT_BACKOFF_INCREASE = serviceReconnectBackoffIncrease;
        SERVICE_RECONNECT_MAX_BACKOFF_SEC = serviceReconnectMaxBackoffSec;
    }

    /**
     * Create a new instance from a settings string.
     */
    public static AppBindingConstants initializeFromString(String settings) {
        return new AppBindingConstants(settings);
    }

    /**
     * dumpsys support.
     */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.println("Constants:");

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_BACKOFF_SEC: ");
        pw.println(SERVICE_RECONNECT_BACKOFF_SEC);

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_BACKOFF_INCREASE: ");
        pw.println(SERVICE_RECONNECT_BACKOFF_INCREASE);

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_MAX_BACKOFF_SEC: ");
        pw.println(SERVICE_RECONNECT_MAX_BACKOFF_SEC);
    }
}
