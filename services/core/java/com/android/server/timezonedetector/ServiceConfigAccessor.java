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
package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneConfiguration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Optional;

/**
 * An interface that provides access to service configuration for time zone detection. This hides
 * how configuration is split between static, compile-time config, dynamic server-pushed flags and
 * user settings. It provides listeners to signal when values that affect different components have
 * changed.
 */
public interface ServiceConfigAccessor {

    @StringDef(prefix = "PROVIDER_MODE_",
            value = { PROVIDER_MODE_DISABLED, PROVIDER_MODE_ENABLED })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface ProviderMode {
    }

    /**
     * The "disabled" provider mode. For use with {@link #getPrimaryLocationTimeZoneProviderMode()}
     * and {@link #getSecondaryLocationTimeZoneProviderMode()}.
     */
    @ProviderMode String PROVIDER_MODE_DISABLED = "disabled";

    /**
     * The "enabled" provider mode. For use with {@link #getPrimaryLocationTimeZoneProviderMode()}
     * and {@link #getSecondaryLocationTimeZoneProviderMode()}.
     */
    @ProviderMode String PROVIDER_MODE_ENABLED = "enabled";

    /**
     * Adds a listener that will be invoked when {@link ConfigurationInternal} may have changed.
     * The listener is invoked on the main thread.
     */
    void addConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener);

    /**
     * Removes a listener previously added via {@link
     * #addConfigurationInternalChangeListener(ConfigurationChangeListener)}.
     */
    void removeConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener);

    /**
     * Returns a snapshot of the {@link ConfigurationInternal} for the current user. This is only a
     * snapshot so callers must use {@link
     * #addConfigurationInternalChangeListener(ConfigurationChangeListener)} to be notified when it
     * changes.
     */
    @NonNull
    ConfigurationInternal getCurrentUserConfigurationInternal();

    /**
     * Updates the configuration properties that control a device's time zone behavior.
     *
     * <p>This method returns {@code true} if the configuration was changed,
     * {@code false} otherwise.
     */
    boolean updateConfiguration(@UserIdInt int userId,
            @NonNull TimeZoneConfiguration requestedConfiguration);

    /**
     * Returns a snapshot of the configuration that controls time zone detector behavior for the
     * specified user.
     */
    @NonNull
    ConfigurationInternal getConfigurationInternal(@UserIdInt int userId);

    /**
     * Adds a listener that will be called when server flags related to location_time_zone_manager
     * change. The callbacks are delivered on the main looper thread.
     *
     * <p>Note: Currently only for use by long-lived objects; there is no associated remove method.
     */
    void addLocationTimeZoneManagerConfigListener(
            @NonNull ConfigurationChangeListener listener);

    /**
     * Returns {@code true} if the telephony-based time zone detection feature is supported on the
     * device.
     */
    boolean isTelephonyTimeZoneDetectionFeatureSupported();

    /**
     * Returns {@code true} if the location-based time zone detection feature can be supported on
     * this device at all according to config. When {@code false}, implies that various other
     * location-based services and settings will be turned off or rendered meaningless.
     *
     * <p>This is the ultimate "feature switch" for location-based time zone detection. If this is
     * {@code false}, the device cannot support the feature without a config change or a reboot:
     * This affects what services are started on boot to minimize expense when the feature is not
     * wanted.
     *
     * Typically {@link #isGeoTimeZoneDetectionFeatureSupported()} should be used except during
     * boot.
     */
    boolean isGeoTimeZoneDetectionFeatureSupportedInConfig();

    /**
     * Returns {@code true} if the location-based time zone detection feature is supported on the
     * device.
     */
    boolean isGeoTimeZoneDetectionFeatureSupported();

    /** Returns the package name of the app hosting the primary location time zone provider. */
    @NonNull
    String getPrimaryLocationTimeZoneProviderPackageName();

    /**
     * Sets the package name of the app hosting the primary location time zone provider for tests.
     * Setting a {@code null} value means the provider is to be disabled.
     * The values are reset with {@link #resetVolatileTestConfig()}.
     */
    void setTestPrimaryLocationTimeZoneProviderPackageName(
            @Nullable String testPrimaryLocationTimeZoneProviderPackageName);

    /**
     * Returns {@code true} if the usual permission checks are to be bypassed for the primary
     * provider. Returns {@code true} only if {@link
     * #setTestPrimaryLocationTimeZoneProviderPackageName} has been called.
     */
    boolean isTestPrimaryLocationTimeZoneProvider();

    /** Returns the package name of the app hosting the secondary location time zone provider. */
    @NonNull
    String getSecondaryLocationTimeZoneProviderPackageName();

    /**
     * Sets the package name of the app hosting the secondary location time zone provider for tests.
     * Setting a {@code null} value means the provider is to be disabled.
     * The values are reset with {@link #resetVolatileTestConfig()}.
     */
    void setTestSecondaryLocationTimeZoneProviderPackageName(
            @Nullable String testSecondaryLocationTimeZoneProviderPackageName);

    /**
     * Returns {@code true} if the usual permission checks are to be bypassed for the secondary
     * provider. Returns {@code true} only if {@link
     * #setTestSecondaryLocationTimeZoneProviderPackageName} has been called.
     */
    boolean isTestSecondaryLocationTimeZoneProvider();

    /**
     * Enables/disables the state recording mode for tests. The value is reset with {@link
     * #resetVolatileTestConfig()}.
     */
    void setRecordStateChangesForTests(boolean enabled);

    /**
     * Returns {@code true} if the controller / providers are expected to record their state changes
     * for tests.
     */
    boolean getRecordStateChangesForTests();

    /**
     * Returns the mode for the primary location time zone provider.
     */
    @NonNull
    @ProviderMode String getPrimaryLocationTimeZoneProviderMode();

    /**
     * Returns the mode for the secondary location time zone provider.
     */
    @ProviderMode String getSecondaryLocationTimeZoneProviderMode();

    /**
     * Returns whether location time zone detection is enabled for users when there's no setting
     * value. Intended for use during feature release testing to "opt-in" users that haven't shown
     * an explicit preference.
     */
    boolean isGeoDetectionEnabledForUsersByDefault();

    /**
     * Returns whether location time zone detection is force enabled/disabled for users. Intended
     * for use during feature release testing to force a given state.
     */
    @NonNull
    Optional<Boolean> getGeoDetectionSettingEnabledOverride();

    /**
     * Returns the time to send to a location time zone provider that informs it how long it has
     * to return its first time zone suggestion.
     */
    @NonNull
    Duration getLocationTimeZoneProviderInitializationTimeout();

    /**
     * Returns the time added to {@link #getLocationTimeZoneProviderInitializationTimeout()} by the
     * server before unilaterally declaring the provider is uncertain.
     */
    @NonNull
    Duration getLocationTimeZoneProviderInitializationTimeoutFuzz();

    /**
     * Returns the time after uncertainty is detected by providers before the location time zone
     * manager makes a suggestion to the time zone detector.
     */
    @NonNull
    Duration getLocationTimeZoneUncertaintyDelay();

    /**
     * Returns the time between equivalent events before the provider process will send the event
     * to the system server.
     */
    @NonNull
    Duration getLocationTimeZoneProviderEventFilteringAgeThreshold();

    /** Clears all in-memory test config. */
    void resetVolatileTestConfig();
}
