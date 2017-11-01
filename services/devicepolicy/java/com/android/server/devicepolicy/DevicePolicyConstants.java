/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.devicepolicy;

import android.util.KeyValueListParser;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Constants that are configurable via the global settings for {@link DevicePolicyManagerService}.
 *
 * Example of setting the values for testing.
 * adb shell settings put global device_policy_constants das_died_service_reconnect_backoff_sec=10,das_died_service_reconnect_backoff_increase=1.5,das_died_service_reconnect_max_backoff_sec=30
 */
public class DevicePolicyConstants {
    private static final String TAG = DevicePolicyManagerService.LOG_TAG;

    private static final String DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC_KEY
            = "das_died_service_reconnect_backoff_sec";

    private static final String DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE_KEY
            = "das_died_service_reconnect_backoff_increase";

    private static final String DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY
            = "das_died_service_reconnect_max_backoff_sec";

    /**
     * The back-off before re-connecting, when a service binding died, due to the owner
     * crashing repeatedly.
     */
    public final long DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC;

    /**
     * The exponential back-off increase factor when a binding dies multiple times.
     */
    public final double DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE;

    /**
     * The max back-off
     */
    public final long DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC;

    private DevicePolicyConstants(String settings) {

        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(settings);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on
            // with defaults.
            Slog.e(TAG, "Bad device policy settings: " + settings);
        }

        long dasDiedServiceReconnectBackoffSec = parser.getLong(
                DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC_KEY, TimeUnit.HOURS.toSeconds(1));

        double dasDiedServiceReconnectBackoffIncrease = parser.getFloat(
                DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE_KEY, 2f);

        long dasDiedServiceReconnectMaxBackoffSec = parser.getLong(
                DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY, TimeUnit.DAYS.toSeconds(1));

        // Set minimum: 5 seconds.
        dasDiedServiceReconnectBackoffSec = Math.max(5, dasDiedServiceReconnectBackoffSec);

        // Set minimum: 1.0.
        dasDiedServiceReconnectBackoffIncrease =
                Math.max(1, dasDiedServiceReconnectBackoffIncrease);

        // Make sure max >= default back off.
        dasDiedServiceReconnectMaxBackoffSec = Math.max(dasDiedServiceReconnectBackoffSec,
                dasDiedServiceReconnectMaxBackoffSec);

        DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC = dasDiedServiceReconnectBackoffSec;
        DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE = dasDiedServiceReconnectBackoffIncrease;
        DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC = dasDiedServiceReconnectMaxBackoffSec;

    }

    public static DevicePolicyConstants loadFromString(String settings) {
        return new DevicePolicyConstants(settings);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.println("Constants:");

        pw.print(prefix);
        pw.print("  DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC: ");
        pw.println( DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC);

        pw.print(prefix);
        pw.print("  DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE: ");
        pw.println( DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE);

        pw.print(prefix);
        pw.print("  DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC: ");
        pw.println( DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC);
    }
}
