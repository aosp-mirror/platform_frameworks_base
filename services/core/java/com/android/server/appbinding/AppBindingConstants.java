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

import android.content.Context;
import android.util.KeyValueListParser;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Constants that are configurable via the global settings for {@link AppBindingService}.
 */
public class AppBindingConstants {
    private static final String TAG = AppBindingService.TAG;

    private static final String SERVICE_RECONNECT_BACKOFF_SEC_KEY =
            "service_reconnect_backoff_sec";

    private static final String SERVICE_RECONNECT_BACKOFF_INCREASE_KEY =
            "service_reconnect_backoff_increase";

    private static final String SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY =
            "service_reconnect_max_backoff_sec";

    private static final String SERVICE_STABLE_CONNECTION_THRESHOLD_SEC_KEY =
            "service_stable_connection_threshold_sec";

    private static final String SMS_SERVICE_ENABLED_KEY =
            "sms_service_enabled";

    private static final String SMS_APP_BIND_FLAGS_KEY =
            "sms_app_bind_flags";

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

    /**
     * If a connection lasts more than this duration, we reset the re-connect back-off time.
     */
    public final long SERVICE_STABLE_CONNECTION_THRESHOLD_SEC;

    /**
     * Whether to actually bind to the default SMS app service. (Feature flag)
     */
    public final boolean SMS_SERVICE_ENABLED;

    /**
     * Extra binding flags for SMS service.
     */
    public final int SMS_APP_BIND_FLAGS;

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
                SERVICE_RECONNECT_BACKOFF_SEC_KEY, 10);

        double serviceReconnectBackoffIncrease = parser.getFloat(
                SERVICE_RECONNECT_BACKOFF_INCREASE_KEY, 2f);

        long serviceReconnectMaxBackoffSec = parser.getLong(
                SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY, TimeUnit.HOURS.toSeconds(1));

        boolean smsServiceEnabled = parser.getBoolean(SMS_SERVICE_ENABLED_KEY, true);

        int smsAppBindFlags = parser.getInt(
                SMS_APP_BIND_FLAGS_KEY,
                Context.BIND_NOT_VISIBLE | Context.BIND_FOREGROUND_SERVICE);

        long serviceStableConnectionThresholdSec = parser.getLong(
                SERVICE_STABLE_CONNECTION_THRESHOLD_SEC_KEY, TimeUnit.MINUTES.toSeconds(2));

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
        SERVICE_STABLE_CONNECTION_THRESHOLD_SEC = serviceStableConnectionThresholdSec;
        SMS_SERVICE_ENABLED = smsServiceEnabled;
        SMS_APP_BIND_FLAGS = smsAppBindFlags;
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
        pw.print("Constants: ");
        pw.println(sourceSettings);

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_BACKOFF_SEC: ");
        pw.println(SERVICE_RECONNECT_BACKOFF_SEC);

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_BACKOFF_INCREASE: ");
        pw.println(SERVICE_RECONNECT_BACKOFF_INCREASE);

        pw.print(prefix);
        pw.print("  SERVICE_RECONNECT_MAX_BACKOFF_SEC: ");
        pw.println(SERVICE_RECONNECT_MAX_BACKOFF_SEC);

        pw.print(prefix);
        pw.print("  SERVICE_STABLE_CONNECTION_THRESHOLD_SEC: ");
        pw.println(SERVICE_STABLE_CONNECTION_THRESHOLD_SEC);

        pw.print(prefix);
        pw.print("  SMS_SERVICE_ENABLED: ");
        pw.println(SMS_SERVICE_ENABLED);

        pw.print(prefix);
        pw.print("  SMS_APP_BIND_FLAGS: 0x");
        pw.println(Integer.toHexString(SMS_APP_BIND_FLAGS));
    }
}
