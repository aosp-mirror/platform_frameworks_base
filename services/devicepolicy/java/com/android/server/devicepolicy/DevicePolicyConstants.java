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

import android.util.IndentingPrintWriter;
import android.util.KeyValueListParser;

import com.android.server.utils.Slogf;

import java.util.concurrent.TimeUnit;

/**
 * Constants that are configurable via the global settings for {@link DevicePolicyManagerService}.
 *
 * Example of setting the values for testing.
 * adb shell settings put global device_policy_constants das_died_service_reconnect_backoff_sec=10,das_died_service_reconnect_backoff_increase=1.5,das_died_service_reconnect_max_backoff_sec=30
 */
public class DevicePolicyConstants {
    private static final String TAG = DevicePolicyManagerService.LOG_TAG;

    private static final String DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC_KEY =
            "das_died_service_reconnect_backoff_sec";

    private static final String DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE_KEY =
            "das_died_service_reconnect_backoff_increase";

    private static final String DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY =
            "das_died_service_reconnect_max_backoff_sec";

    private static final String DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC_KEY =
            "das_died_service_stable_connection_threshold_sec";

    private static final String BATTERY_THRESHOLD_NOT_CHARGING_KEY =
            "battery_threshold_not_charging";

    private static final String BATTERY_THRESHOLD_CHARGING_KEY =
            "battery_threshold_charging";

    // TODO(b/182994391): Replace with more generic solution to override the supervision
    // component.
    private static final String USE_TEST_ADMIN_AS_SUPERVISION_COMPONENT_KEY =
            "use_test_admin_as_supervision_component";

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

    /**
     * If a connection lasts more than this duration, we reset the re-connect back-off time.
     */
    public final long DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC;

    /**
     * Battery threshold for installing system update while the device is not charging.
     */
    public final int BATTERY_THRESHOLD_NOT_CHARGING;

    /**
     * Battery threshold for installing system update while the device is charging.
     */
    public final int BATTERY_THRESHOLD_CHARGING;

    /**
     * Whether to default to considering the current DO/PO as the supervision component
     * if they are a testOnly admin.
     */
    public final boolean USE_TEST_ADMIN_AS_SUPERVISION_COMPONENT;


    private DevicePolicyConstants(String settings) {

        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(settings);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on
            // with defaults.
            Slogf.e(TAG, "Bad device policy settings: %s", settings);
        }

        long dasDiedServiceReconnectBackoffSec = parser.getLong(
                DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC_KEY, TimeUnit.HOURS.toSeconds(1));

        double dasDiedServiceReconnectBackoffIncrease = parser.getFloat(
                DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE_KEY, 2f);

        long dasDiedServiceReconnectMaxBackoffSec = parser.getLong(
                DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC_KEY, TimeUnit.DAYS.toSeconds(1));

        long dasDiedServiceStableConnectionThresholdSec = parser.getLong(
                DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC_KEY,
                TimeUnit.MINUTES.toSeconds(2));

        int batteryThresholdNotCharging = parser.getInt(
                BATTERY_THRESHOLD_NOT_CHARGING_KEY, 40);

        int batteryThresholdCharging = parser.getInt(
                BATTERY_THRESHOLD_CHARGING_KEY, 20);

        boolean useTestAdminAsSupervisionComponent = parser.getBoolean(
                USE_TEST_ADMIN_AS_SUPERVISION_COMPONENT_KEY, false);

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
        DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC =
                dasDiedServiceStableConnectionThresholdSec;
        BATTERY_THRESHOLD_NOT_CHARGING = batteryThresholdNotCharging;
        BATTERY_THRESHOLD_CHARGING = batteryThresholdCharging;
        USE_TEST_ADMIN_AS_SUPERVISION_COMPONENT = useTestAdminAsSupervisionComponent;
    }

    public static DevicePolicyConstants loadFromString(String settings) {
        return new DevicePolicyConstants(settings);
    }

    /** Dump constants */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Constants:");

        pw.increaseIndent();
        pw.print("DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC: ");
        pw.println(DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC);

        pw.print("DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE: ");
        pw.println(DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE);

        pw.print("DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC: ");
        pw.println(DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC);

        pw.print("DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC: ");
        pw.println(DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC);
        pw.decreaseIndent();
    }
}
