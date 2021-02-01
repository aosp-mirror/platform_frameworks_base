/*
 * Copyright 2021 The Android Open Source Project
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
package com.android.server.timedetector;

import static android.provider.DeviceConfig.NAMESPACE_SYSTEM_TIME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * A helper class for reading / monitoring the {@link
 * android.provider.DeviceConfig#NAMESPACE_SYSTEM_TIME} namespace for server-configured flags.
 */
public final class DeviceConfig {

    /**
     * An annotation used to indicate when a {@link
     * android.provider.DeviceConfig#NAMESPACE_SYSTEM_TIME} key is required.
     *
     * <p>Note that the com.android.geotz module deployment of the Offline LocationTimeZoneProvider
     * also shares the {@link android.provider.DeviceConfig#NAMESPACE_SYSTEM_TIME}, and uses the
     * prefix "geotz_" on all of its key strings.
     */
    @StringDef(prefix = "KEY_", value = {
            KEY_FORCE_LOCATION_TIME_ZONE_DETECTION_ENABLED,
            KEY_LOCATION_TIME_ZONE_DETECTION_ENABLED_DEFAULT,
            KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS,
            KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
            KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS,
    })
    @interface DeviceConfigKey {}

    /**
     * The key to force location time zone detection on for a device. Only intended for use during
     * release testing with droidfooders. The user can still disable the feature by turning off the
     * master location switch, or disabling automatic time zone detection.
     */
    @DeviceConfigKey
    public static final String KEY_FORCE_LOCATION_TIME_ZONE_DETECTION_ENABLED =
            "force_location_time_zone_detection_enabled";

    /**
     * The key for the default value used to determine whether location time zone detection is
     * enabled when the user hasn't explicitly set it yet.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_DETECTION_ENABLED_DEFAULT =
            "location_time_zone_detection_enabled_default";

    /**
     * The key for the minimum delay after location time zone detection has been enabled before the
     * location time zone manager can report it is uncertain about the time zone.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS =
            "location_time_zone_detection_uncertainty_delay_millis";

    /**
     * The key for the timeout passed to a location time zone provider that tells it how long it has
     * to provide an explicit first suggestion without being declared uncertain.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS =
            "ltpz_init_timeout_millis";

    /**
     * The key for the extra time added to {@link
     * #KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS} by the location time zone
     * manager before the location time zone provider will actually be declared uncertain.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS =
            "ltpz_init_timeout_fuzz_millis";

    /** Creates an instance. */
    public DeviceConfig() {}

    /** Adds a listener for the system_time namespace. */
    public void addListener(
            @NonNull Executor handlerExecutor, @NonNull Runnable listener) {
        android.provider.DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE_SYSTEM_TIME,
                handlerExecutor,
                properties -> listener.run());
    }

    /**
     * Returns a boolean value from {@link android.provider.DeviceConfig} from the system_time
     * namespace, or {@code defaultValue} if there is no explicit value set.
     */
    public boolean getBoolean(@DeviceConfigKey String key, boolean defaultValue) {
        return android.provider.DeviceConfig.getBoolean(NAMESPACE_SYSTEM_TIME, key, defaultValue);
    }

    /**
     * Returns a positive duration from {@link android.provider.DeviceConfig} from the system_time
     * namespace, or {@code defaultValue} if there is no explicit value set.
     */
    @Nullable
    public Duration getDurationFromMillis(
            @DeviceConfigKey String key, @Nullable Duration defaultValue) {
        long deviceConfigValue =
                android.provider.DeviceConfig.getLong(NAMESPACE_SYSTEM_TIME, key, -1);
        if (deviceConfigValue < 0) {
            return defaultValue;
        }
        return Duration.ofMillis(deviceConfigValue);
    }
}
