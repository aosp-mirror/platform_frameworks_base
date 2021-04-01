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
import android.content.Context;
import android.provider.DeviceConfig;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.server.timezonedetector.ConfigurationChangeListener;
import com.android.server.timezonedetector.ServiceConfigAccessor;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A helper class for reading / monitoring the {@link DeviceConfig#NAMESPACE_SYSTEM_TIME} namespace
 * for server-configured flags.
 */
public final class ServerFlags {

    private static final Optional<Boolean> OPTIONAL_TRUE = Optional.of(true);
    private static final Optional<Boolean> OPTIONAL_FALSE = Optional.of(false);

    /**
     * An annotation used to indicate when a {@link DeviceConfig#NAMESPACE_SYSTEM_TIME} key is
     * required.
     *
     * <p>Note that the com.android.geotz module deployment of the Offline LocationTimeZoneProvider
     * also shares the {@link DeviceConfig#NAMESPACE_SYSTEM_TIME}, and uses the
     * prefix "geotz_" on all of its key strings.
     */
    @StringDef(prefix = "KEY_", value = {
            KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
            KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_ENABLED_OVERRIDE,
            KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_ENABLED_OVERRIDE,
            KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
            KEY_LOCATION_TIME_ZONE_PROVIDER_INITIALIZATION_TIMEOUT_MILLIS,
            KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS,
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE,
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT,
    })
    @interface DeviceConfigKey {}

    /**
     * Controls whether the location time zone manager service will be started. Only observed if
     * the device build is configured to support location-based time zone detection. See
     * {@link ServiceConfigAccessor#isGeoTimeZoneDetectionFeatureSupportedInConfig()} and {@link
     * ServiceConfigAccessor#isGeoTimeZoneDetectionFeatureSupported()}.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED =
            "location_time_zone_detection_feature_supported";

    /**
     * The key for the server flag that can override the device config for whether the primary
     * location time zone provider is enabled or disabled.
     */
    @DeviceConfigKey
    public static final String KEY_PRIMARY_LOCATION_TIME_ZONE_PROVIDER_ENABLED_OVERRIDE =
            "primary_location_time_zone_provider_enabled_override";

    /**
     * The key for the server flag that can override the device config for whether the secondary
     * location time zone provider is enabled or disabled.
     */
    @DeviceConfigKey
    public static final String KEY_SECONDARY_LOCATION_TIME_ZONE_PROVIDER_ENABLED_OVERRIDE =
            "secondary_location_time_zone_provider_enabled_override";

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

    /**
     * The key for the server flag that can override location time zone detection being enabled for
     * a user. Only intended for use during release testing with droidfooders. The user can still
     * disable the feature by turning off the master location switch, or by disabling automatic time
     * zone detection.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE =
            "location_time_zone_detection_setting_enabled_override";

    /**
     * The key for the default value used to determine whether location time zone detection is
     * enabled when the user hasn't explicitly set it yet.
     */
    @DeviceConfigKey
    public static final String KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT =
            "location_time_zone_detection_setting_enabled_default";

    @GuardedBy("mListeners")
    private final ArrayMap<ConfigurationChangeListener, Set<String>> mListeners = new ArrayMap<>();

    private static final Object SLOCK = new Object();

    @GuardedBy("SLOCK")
    @Nullable
    private static ServerFlags sInstance;

    private ServerFlags(Context context) {
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE_SYSTEM_TIME,
                context.getMainExecutor(),
                this::handlePropertiesChanged);
    }

    /** Returns the singleton instance. */
    public static ServerFlags getInstance(Context context) {
        synchronized (SLOCK) {
            if (sInstance == null) {
                sInstance = new ServerFlags(context);
            }
            return sInstance;
        }
    }

    private void handlePropertiesChanged(@NonNull DeviceConfig.Properties properties) {
        synchronized (mListeners) {
            for (Map.Entry<ConfigurationChangeListener, Set<String>> listenerEntry
                    : mListeners.entrySet()) {
                if (intersects(listenerEntry.getValue(), properties.getKeyset())) {
                    listenerEntry.getKey().onChange();
                }
            }
        }
    }

    private static boolean intersects(@NonNull Set<String> one, @NonNull Set<String> two) {
        for (String toFind : one) {
            if (two.contains(toFind)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a listener for the system_time namespace that will trigger if any of the specified keys
     * change. Listener callbacks are delivered on the main looper thread.
     *
     * <p>Note: Only for use by long-lived objects like other singletons. There is deliberately no
     * associated remove method.
     */
    public void addListener(@NonNull ConfigurationChangeListener listener,
            @NonNull Set<String> keys) {
        Objects.requireNonNull(listener);
        Objects.requireNonNull(keys);

        synchronized (mListeners) {
            mListeners.put(listener, keys);
        }
    }

    /**
     * Returns an optional boolean value from {@link DeviceConfig} from the system_time
     * namespace, returns {@link Optional#empty()} if there is no explicit value set.
     */
    @NonNull
    public Optional<Boolean> getOptionalBoolean(@DeviceConfigKey String key) {
        String value = DeviceConfig.getProperty(NAMESPACE_SYSTEM_TIME, key);
        return parseOptionalBoolean(value);
    }

    @NonNull
    private static Optional<Boolean> parseOptionalBoolean(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        } else {
            return Boolean.parseBoolean(value) ? OPTIONAL_TRUE : OPTIONAL_FALSE;
        }
    }

    /**
     * Returns a boolean value from {@link DeviceConfig} from the system_time
     * namespace, or {@code defaultValue} if there is no explicit value set.
     */
    public boolean getBoolean(@DeviceConfigKey String key, boolean defaultValue) {
        return DeviceConfig.getBoolean(NAMESPACE_SYSTEM_TIME, key, defaultValue);
    }

    /**
     * Returns a positive duration from {@link DeviceConfig} from the system_time
     * namespace, or {@code defaultValue} if there is no explicit value set.
     */
    @Nullable
    public Duration getDurationFromMillis(
            @DeviceConfigKey String key, @Nullable Duration defaultValue) {
        long deviceConfigValue = DeviceConfig.getLong(NAMESPACE_SYSTEM_TIME, key, -1);
        if (deviceConfigValue < 0) {
            return defaultValue;
        }
        return Duration.ofMillis(deviceConfigValue);
    }
}
