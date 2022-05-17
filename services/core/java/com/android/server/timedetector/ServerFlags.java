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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
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
     */
    @StringDef(prefix = "KEY_", value = {
            KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
            KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED,
            KEY_PRIMARY_LTZP_MODE_OVERRIDE,
            KEY_SECONDARY_LTZP_MODE_OVERRIDE,
            KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
            KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS,
            KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS,
            KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS,
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE,
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT,
            KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE,
            KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE,
            KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED,
            KEY_ENHANCED_METRICS_COLLECTION_ENABLED,
    })
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @Retention(RetentionPolicy.SOURCE)
    @interface DeviceConfigKey {}

    /**
     * Controls whether the location time zone manager service will be started. Only observed if
     * the device build is configured to support location-based time zone detection. See
     * {@link ServiceConfigAccessor#isGeoTimeZoneDetectionFeatureSupportedInConfig()} and {@link
     * ServiceConfigAccessor#isGeoTimeZoneDetectionFeatureSupported()}.
     */
    public static final @DeviceConfigKey String KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED =
            "location_time_zone_detection_feature_supported";

    /**
     * Controls whether location time zone detection should run all the time on supported devices,
     * even when the user has not enabled it explicitly in settings. Enabled for internal testing
     * only.
     */
    public static final @DeviceConfigKey String
            KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED =
            "location_time_zone_detection_run_in_background_enabled";

    /**
     * The key for the server flag that can override the device config for whether the primary
     * location time zone provider is enabled, disabled, or (for testing) in simulation mode.
     */
    public static final @DeviceConfigKey String KEY_PRIMARY_LTZP_MODE_OVERRIDE =
            "primary_location_time_zone_provider_mode_override";

    /**
     * The key for the server flag that can override the device config for whether the secondary
     * location time zone provider is enabled or disabled, or (for testing) in simulation mode.
     */
    public static final @DeviceConfigKey String KEY_SECONDARY_LTZP_MODE_OVERRIDE =
            "secondary_location_time_zone_provider_mode_override";

    /**
     * The key for the minimum delay after location time zone detection has been enabled before the
     * location time zone manager can report it is uncertain about the time zone.
     */
    public static final @DeviceConfigKey String
            KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS =
            "location_time_zone_detection_uncertainty_delay_millis";

    /**
     * The key for the timeout passed to a location time zone provider that tells it how long it has
     * to provide an explicit first suggestion without being declared uncertain.
     */
    public static final @DeviceConfigKey String KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS =
            "ltzp_init_timeout_millis";

    /**
     * The key for the extra time added to {@link
     * #KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS} by the location time zone
     * manager before the location time zone provider will actually be declared uncertain.
     */
    public static final @DeviceConfigKey String KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS =
            "ltzp_init_timeout_fuzz_millis";

    /** The key for the setting that controls rate limiting of provider events. */
    public static final @DeviceConfigKey String KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS =
            "ltzp_event_filtering_age_threshold_millis";

    /**
     * The key for the server flag that can override location time zone detection being enabled for
     * a user. Only intended for use during release testing with droidfooders. The user can still
     * disable the feature by turning off the master location switch, or by disabling automatic time
     * zone detection.
     */
    public static final @DeviceConfigKey String
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE =
            "location_time_zone_detection_setting_enabled_override";

    /**
     * The key for the default value used to determine whether location time zone detection is
     * enabled when the user hasn't explicitly set it yet.
     */
    public static final @DeviceConfigKey String
            KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT =
            "location_time_zone_detection_setting_enabled_default";

    /**
     * The key to control support for time zone detection falling back to telephony detection under
     * certain circumstances.
     */
    public static final @DeviceConfigKey String
            KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED =
            "time_zone_detector_telephony_fallback_supported";

    /**
     * The key to override the time detector origin priorities configuration. A comma-separated list
     * of strings that will be passed to {@link TimeDetectorStrategy#stringToOrigin(String)}.
     * All values must be recognized or the override value will be ignored.
     */
    public static final @DeviceConfigKey String KEY_TIME_DETECTOR_ORIGIN_PRIORITIES_OVERRIDE =
            "time_detector_origin_priorities_override";

    /**
     * The key to override the time detector lower bound configuration. The value is the number of
     * milliseconds since the beginning of the Unix epoch.
     */
    public static final @DeviceConfigKey String KEY_TIME_DETECTOR_LOWER_BOUND_MILLIS_OVERRIDE =
            "time_detector_lower_bound_millis_override";

    /**
     * The key to allow extra metrics / telemetry information to be collected from internal testers.
     */
    public static final @DeviceConfigKey String KEY_ENHANCED_METRICS_COLLECTION_ENABLED =
            "enhanced_metrics_collection_enabled";

    /**
     * The registered listeners and the keys to trigger on. The value is explicitly a HashSet to
     * ensure O(1) lookup performance when working out whether a listener should trigger.
     */
    @GuardedBy("mListeners")
    private final ArrayMap<ConfigurationChangeListener, HashSet<String>> mListeners =
            new ArrayMap<>();

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
            for (Map.Entry<ConfigurationChangeListener, HashSet<String>> listenerEntry
                    : mListeners.entrySet()) {
                // It's unclear which set of the following two Sets is going to be larger in the
                // average case: monitoredKeys will be a subset of the set of possible keys, but
                // only changed keys are reported. Because we guarantee the type / lookup behavior
                // of the monitoredKeys by making that a HashSet, that is used as the haystack Set,
                // while the changed keys is treated as the needles Iterable. At the time of
                // writing, properties.getKeyset() actually returns a HashSet, so iteration isn't
                // super efficient and the use of HashSet for monitoredKeys may be redundant, but
                // neither set will be enormous.
                HashSet<String> monitoredKeys = listenerEntry.getValue();
                Iterable<String> modifiedKeys = properties.getKeyset();
                if (containsAny(monitoredKeys, modifiedKeys)) {
                    listenerEntry.getKey().onChange();
                }
            }
        }
    }

    private static boolean containsAny(
            @NonNull Set<String> haystack, @NonNull Iterable<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
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

        // Make a defensive copy and use a well-defined Set implementation to provide predictable
        // performance on the lookup.
        HashSet<String> keysCopy = new HashSet<>(keys);
        synchronized (mListeners) {
            mListeners.put(listener, keysCopy);
        }
    }

    /**
     * Returns an optional string value from {@link DeviceConfig} from the system_time
     * namespace, returns {@link Optional#empty()} if there is no explicit value set.
     */
    @NonNull
    public Optional<String> getOptionalString(@DeviceConfigKey String key) {
        String value = DeviceConfig.getProperty(NAMESPACE_SYSTEM_TIME, key);
        return Optional.ofNullable(value);
    }

    /**
     * Returns an optional string array value from {@link DeviceConfig} from the system_time
     * namespace, returns {@link Optional#empty()} if there is no explicit value set.
     */
    @NonNull
    public Optional<String[]> getOptionalStringArray(@DeviceConfigKey String key) {
        Optional<String> string = getOptionalString(key);
        if (!string.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(string.get().split(","));
    }

    /**
     * Returns an {@link Instant} from {@link DeviceConfig} from the system_time
     * namespace, returns the {@code defaultValue} if the value is missing or invalid.
     */
    @NonNull
    public Optional<Instant> getOptionalInstant(@DeviceConfigKey String key) {
        String value = DeviceConfig.getProperty(NAMESPACE_SYSTEM_TIME, key);
        if (value == null) {
            return Optional.empty();
        }

        try {
            long millis = Long.parseLong(value);
            return Optional.of(Instant.ofEpochMilli(millis));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
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
