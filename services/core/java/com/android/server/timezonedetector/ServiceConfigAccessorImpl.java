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

import static android.content.Intent.ACTION_USER_SWITCHED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.timedetector.ServerFlags;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A singleton implementation of {@link ServiceConfigAccessor}.
 */
public final class ServiceConfigAccessorImpl implements ServiceConfigAccessor {

    /**
     * Device config keys that can affect the content of {@link ConfigurationInternal}.
     */
    private static final Set<String> CONFIGURATION_INTERNAL_SERVER_FLAGS_KEYS_TO_WATCH = Set.of(
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
            ServerFlags.KEY_PRIMARY_LTZP_MODE_OVERRIDE,
            ServerFlags.KEY_SECONDARY_LTZP_MODE_OVERRIDE,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED,
            ServerFlags.KEY_ENHANCED_METRICS_COLLECTION_ENABLED,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE,
            ServerFlags.KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED
    );

    /**
     * Device config keys that can affect {@link
     * com.android.server.timezonedetector.location.LocationTimeZoneManagerService} behavior.
     */
    private static final Set<String> LOCATION_TIME_ZONE_MANAGER_SERVER_FLAGS_KEYS_TO_WATCH = Set.of(
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE,
            ServerFlags.KEY_PRIMARY_LTZP_MODE_OVERRIDE,
            ServerFlags.KEY_SECONDARY_LTZP_MODE_OVERRIDE,
            ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS,
            ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
            ServerFlags.KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS,
            ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS
    );

    private static final Duration DEFAULT_LTZP_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_LTZP_INITIALIZATION_TIMEOUT_FUZZ = Duration.ofMinutes(1);
    private static final Duration DEFAULT_LTZP_UNCERTAINTY_DELAY = Duration.ofMinutes(5);
    private static final Duration DEFAULT_LTZP_EVENT_FILTER_AGE_THRESHOLD = Duration.ofMinutes(1);

    private static final Object SLOCK = new Object();

    /** The singleton instance. Initialized once in {@link #getInstance(Context)}. */
    @GuardedBy("SLOCK")
    @Nullable
    private static ServiceConfigAccessor sInstance;

    @NonNull private final Context mContext;
    @NonNull private final ServerFlags mServerFlags;
    @NonNull private final ContentResolver mCr;
    @NonNull private final UserManager mUserManager;
    @NonNull private final LocationManager mLocationManager;

    @GuardedBy("this")
    @NonNull private final List<ConfigurationChangeListener> mConfigurationInternalListeners =
            new ArrayList<>();

    /**
     * The mode to use for the primary location time zone provider in a test. Setting this
     * disables some permission checks.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test provider accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    @GuardedBy("this")
    @Nullable
    private String mTestPrimaryLocationTimeZoneProviderMode;

    /**
     * The package name to use for the primary location time zone provider in a test.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test provider accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    @GuardedBy("this")
    @Nullable
    private String mTestPrimaryLocationTimeZoneProviderPackageName;

    /**
     * See {@link #mTestPrimaryLocationTimeZoneProviderMode}; this is the equivalent for the
     * secondary provider.
     */
    @GuardedBy("this")
    @Nullable
    private String mTestSecondaryLocationTimeZoneProviderMode;

    /**
     * See {@link #mTestPrimaryLocationTimeZoneProviderPackageName}; this is the equivalent for the
     * secondary provider.
     */
    @GuardedBy("this")
    @Nullable
    private String mTestSecondaryLocationTimeZoneProviderPackageName;

    /**
     * Whether to record state changes for tests.
     * This state is volatile: it is never written to storage / never survives a reboot. This is to
     * avoid a test state accidentally being left configured on a device.
     * See also {@link #resetVolatileTestConfig()}.
     */
    @GuardedBy("this")
    private boolean mRecordStateChangesForTests;

    private ServiceConfigAccessorImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mCr = context.getContentResolver();
        mUserManager = context.getSystemService(UserManager.class);
        mLocationManager = context.getSystemService(LocationManager.class);
        mServerFlags = ServerFlags.getInstance(mContext);

        // Wire up the config change listeners for anything that could affect ConfigurationInternal.
        // Use the main thread for event delivery, listeners can post to their chosen thread.

        // Listen for the user changing / the user's location mode changing. Report on the main
        // thread.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USER_SWITCHED);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleConfigurationInternalChangeOnMainThread();
            }
        }, filter, null, null /* main thread */);

        // Add async callbacks for global settings being changed.
        ContentResolver contentResolver = mContext.getContentResolver();
        ContentObserver contentObserver = new ContentObserver(mContext.getMainThreadHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                handleConfigurationInternalChangeOnMainThread();
            }
        };
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true, contentObserver);

        // Add async callbacks for user scoped location settings being changed.
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED),
                true, contentObserver, UserHandle.USER_ALL);

        // Watch server flags.
        mServerFlags.addListener(this::handleConfigurationInternalChangeOnMainThread,
                CONFIGURATION_INTERNAL_SERVER_FLAGS_KEYS_TO_WATCH);
    }

    /** Returns the singleton instance. */
    public static ServiceConfigAccessor getInstance(Context context) {
        synchronized (SLOCK) {
            if (sInstance == null) {
                sInstance = new ServiceConfigAccessorImpl(context);
            }
            return sInstance;
        }
    }

    private synchronized void handleConfigurationInternalChangeOnMainThread() {
        for (ConfigurationChangeListener changeListener : mConfigurationInternalListeners) {
            changeListener.onChange();
        }
    }

    @Override
    public synchronized void addConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener) {
        mConfigurationInternalListeners.add(Objects.requireNonNull(listener));
    }

    @Override
    public synchronized void removeConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener) {
        mConfigurationInternalListeners.remove(Objects.requireNonNull(listener));
    }

    @Override
    @NonNull
    public synchronized ConfigurationInternal getCurrentUserConfigurationInternal() {
        int currentUserId =
                LocalServices.getService(ActivityManagerInternal.class).getCurrentUserId();
        return getConfigurationInternal(currentUserId);
    }

    @Override
    public synchronized boolean updateConfiguration(@UserIdInt int userId,
            @NonNull TimeZoneConfiguration requestedConfiguration) {
        Objects.requireNonNull(requestedConfiguration);

        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                getConfigurationInternal(userId).createCapabilitiesAndConfig();
        TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        TimeZoneConfiguration oldConfiguration = capabilitiesAndConfig.getConfiguration();

        final TimeZoneConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(oldConfiguration, requestedConfiguration);
        if (newConfiguration == null) {
            // The changes could not be made because the user's capabilities do not allow it.
            return false;
        }

        // Store the configuration / notify as needed. This will cause the mEnvironment to invoke
        // handleConfigChanged() asynchronously.
        storeConfiguration(userId, newConfiguration);

        return true;
    }

    /**
     * Stores the configuration properties contained in {@code newConfiguration}.
     * All checks about user capabilities must be done by the caller and
     * {@link TimeZoneConfiguration#isComplete()} must be {@code true}.
     */
    @GuardedBy("this")
    private void storeConfiguration(@UserIdInt int userId,
            @NonNull TimeZoneConfiguration configuration) {
        Objects.requireNonNull(configuration);

        // Avoid writing the auto detection enabled setting for devices that do not support auto
        // time zone detection: if we wrote it down then we'd set the value explicitly, which would
        // prevent detecting "default" later. That might influence what happens on later releases
        // that support new types of auto detection on the same hardware.
        if (isAutoDetectionFeatureSupported()) {
            final boolean autoDetectionEnabled = configuration.isAutoDetectionEnabled();
            setAutoDetectionEnabledIfRequired(autoDetectionEnabled);

            // Avoid writing the geo detection enabled setting for devices with settings that
            // are currently overridden by server flags: otherwise we might overwrite a droidfood
            // user's real setting permanently.
            // Also avoid writing the geo detection enabled setting for devices that do not support
            // geo time zone detection: if we wrote it down then we'd set the value explicitly,
            // which would prevent detecting "default" later. That might influence what happens on
            // later releases that start to support geo detection on the same hardware.
            if (!getGeoDetectionSettingEnabledOverride().isPresent()
                    && isGeoTimeZoneDetectionFeatureSupported()) {
                final boolean geoDetectionEnabledSetting = configuration.isGeoDetectionEnabled();
                setGeoDetectionEnabledSettingIfRequired(userId, geoDetectionEnabledSetting);
            }
        }
    }

    @Override
    @NonNull
    public synchronized ConfigurationInternal getConfigurationInternal(@UserIdInt int userId) {
        return new ConfigurationInternal.Builder(userId)
                .setTelephonyDetectionFeatureSupported(
                        isTelephonyTimeZoneDetectionFeatureSupported())
                .setGeoDetectionFeatureSupported(isGeoTimeZoneDetectionFeatureSupported())
                .setTelephonyFallbackSupported(isTelephonyFallbackSupported())
                .setGeoDetectionRunInBackgroundEnabled(getGeoDetectionRunInBackgroundEnabled())
                .setEnhancedMetricsCollectionEnabled(isEnhancedMetricsCollectionEnabled())
                .setAutoDetectionEnabledSetting(getAutoDetectionEnabledSetting())
                .setUserConfigAllowed(isUserConfigAllowed(userId))
                .setLocationEnabledSetting(getLocationEnabledSetting(userId))
                .setGeoDetectionEnabledSetting(getGeoDetectionEnabledSetting(userId))
                .build();
    }

    private void setAutoDetectionEnabledIfRequired(boolean enabled) {
        // This check is racey, but the whole settings update process is racey. This check prevents
        // a ConfigurationChangeListener callback triggering due to ContentObserver's still
        // triggering *sometimes* for no-op updates. Because callbacks are async this is necessary
        // for stable behavior during tests.
        if (getAutoDetectionEnabledSetting() != enabled) {
            Settings.Global.putInt(mCr, Settings.Global.AUTO_TIME_ZONE, enabled ? 1 : 0);
        }
    }

    private boolean getLocationEnabledSetting(@UserIdInt int userId) {
        return mLocationManager.isLocationEnabledForUser(UserHandle.of(userId));
    }

    private boolean isUserConfigAllowed(@UserIdInt int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, userHandle);
    }

    private boolean getAutoDetectionEnabledSetting() {
        return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE, 1 /* default */) > 0;
    }

    private boolean getGeoDetectionEnabledSetting(@UserIdInt int userId) {
        // We may never use this, but it gives us a way to force location-based time zone detection
        // on/off for testers (but only where their other settings would allow them to turn it on
        // for themselves).
        Optional<Boolean> override = getGeoDetectionSettingEnabledOverride();
        if (override.isPresent()) {
            return override.get();
        }

        final boolean geoDetectionEnabledByDefault = isGeoDetectionEnabledForUsersByDefault();
        return Settings.Secure.getIntForUser(mCr,
                Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED,
                (geoDetectionEnabledByDefault ? 1 : 0) /* defaultValue */, userId) != 0;
    }

    private void setGeoDetectionEnabledSettingIfRequired(@UserIdInt int userId, boolean enabled) {
        // See comment in setAutoDetectionEnabledIfRequired. http://b/171953500
        if (getGeoDetectionEnabledSetting(userId) != enabled) {
            Settings.Secure.putIntForUser(mCr, Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED,
                    enabled ? 1 : 0, userId);
        }
    }

    @Override
    public void addLocationTimeZoneManagerConfigListener(
            @NonNull ConfigurationChangeListener listener) {
        mServerFlags.addListener(listener, LOCATION_TIME_ZONE_MANAGER_SERVER_FLAGS_KEYS_TO_WATCH);
    }

    /** Returns {@code true} if any form of automatic time zone detection is supported. */
    private boolean isAutoDetectionFeatureSupported() {
        return isTelephonyTimeZoneDetectionFeatureSupported()
                || isGeoTimeZoneDetectionFeatureSupported();
    }

    @Override
    public boolean isTelephonyTimeZoneDetectionFeatureSupported() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    @Override
    public boolean isGeoTimeZoneDetectionFeatureSupportedInConfig() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableGeolocationTimeZoneDetection);
    }

    @Override
    public boolean isGeoTimeZoneDetectionFeatureSupported() {
        // For the feature to be enabled it must:
        // 1) Be turned on in config.
        // 2) Not be turned off via a server flag.
        // 3) There must be at least one location time zone provider enabled / configured.
        return isGeoTimeZoneDetectionFeatureSupportedInConfig()
                && isGeoTimeZoneDetectionFeatureSupportedInternal()
                && atLeastOneProviderIsEnabled();
    }

    private boolean atLeastOneProviderIsEnabled() {
        return !(Objects.equals(getPrimaryLocationTimeZoneProviderMode(), PROVIDER_MODE_DISABLED)
                && Objects.equals(getSecondaryLocationTimeZoneProviderMode(),
                PROVIDER_MODE_DISABLED));
    }

    /**
     * Returns {@code true} if the location-based time zone detection feature is not explicitly
     * disabled by a server flag.
     */
    private boolean isGeoTimeZoneDetectionFeatureSupportedInternal() {
        final boolean defaultEnabled = true;
        return mServerFlags.getBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_FEATURE_SUPPORTED,
                defaultEnabled);
    }

    /**
     * Returns {@code true} if location time zone detection should run all the time on supported
     * devices, even when the user has not enabled it explicitly in settings. Enabled for internal
     * testing only.
     */
    private boolean getGeoDetectionRunInBackgroundEnabled() {
        final boolean defaultEnabled = false;
        return mServerFlags.getBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_RUN_IN_BACKGROUND_ENABLED,
                defaultEnabled);
    }

    /**
     * Returns {@code true} if extra metrics / telemetry information can be collected. Used for
     * internal testers.
     */
    private boolean isEnhancedMetricsCollectionEnabled() {
        final boolean defaultEnabled = false;
        return mServerFlags.getBoolean(
                ServerFlags.KEY_ENHANCED_METRICS_COLLECTION_ENABLED,
                defaultEnabled);
    }

    @Override
    @NonNull
    public synchronized String getPrimaryLocationTimeZoneProviderPackageName() {
        if (mTestPrimaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestPrimaryLocationTimeZoneProviderPackageName;
        }
        return mContext.getResources().getString(
                R.string.config_primaryLocationTimeZoneProviderPackageName);
    }

    @Override
    public synchronized void setTestPrimaryLocationTimeZoneProviderPackageName(
            @Nullable String testPrimaryLocationTimeZoneProviderPackageName) {
        mTestPrimaryLocationTimeZoneProviderPackageName =
                testPrimaryLocationTimeZoneProviderPackageName;
        mTestPrimaryLocationTimeZoneProviderMode =
                mTestPrimaryLocationTimeZoneProviderPackageName == null
                        ? PROVIDER_MODE_DISABLED : PROVIDER_MODE_ENABLED;
        // Changing this state can affect the content of ConfigurationInternal, so listeners need to
        // be informed.
        mContext.getMainThreadHandler().post(this::handleConfigurationInternalChangeOnMainThread);
    }

    @Override
    public synchronized boolean isTestPrimaryLocationTimeZoneProvider() {
        return mTestPrimaryLocationTimeZoneProviderMode != null;
    }

    @Override
    @NonNull
    public synchronized String getSecondaryLocationTimeZoneProviderPackageName() {
        if (mTestSecondaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestSecondaryLocationTimeZoneProviderPackageName;
        }
        return mContext.getResources().getString(
                R.string.config_secondaryLocationTimeZoneProviderPackageName);
    }

    @Override
    public synchronized void setTestSecondaryLocationTimeZoneProviderPackageName(
            @Nullable String testSecondaryLocationTimeZoneProviderPackageName) {
        mTestSecondaryLocationTimeZoneProviderPackageName =
                testSecondaryLocationTimeZoneProviderPackageName;
        mTestSecondaryLocationTimeZoneProviderMode =
                mTestSecondaryLocationTimeZoneProviderPackageName == null
                        ? PROVIDER_MODE_DISABLED : PROVIDER_MODE_ENABLED;
        // Changing this state can affect the content of ConfigurationInternal, so listeners need to
        // be informed.
        mContext.getMainThreadHandler().post(this::handleConfigurationInternalChangeOnMainThread);
    }

    @Override
    public synchronized boolean isTestSecondaryLocationTimeZoneProvider() {
        return mTestSecondaryLocationTimeZoneProviderMode != null;
    }

    @Override
    public synchronized void setRecordStateChangesForTests(boolean enabled) {
        mRecordStateChangesForTests = enabled;
    }

    @Override
    public synchronized boolean getRecordStateChangesForTests() {
        return mRecordStateChangesForTests;
    }

    @Override
    @NonNull
    public synchronized @ProviderMode String getPrimaryLocationTimeZoneProviderMode() {
        if (mTestPrimaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestPrimaryLocationTimeZoneProviderMode;
        }
        return mServerFlags.getOptionalString(ServerFlags.KEY_PRIMARY_LTZP_MODE_OVERRIDE)
                .orElse(getPrimaryLocationTimeZoneProviderModeFromConfig());
    }

    @NonNull
    private synchronized @ProviderMode String getPrimaryLocationTimeZoneProviderModeFromConfig() {
        int providerEnabledConfigId = R.bool.config_enablePrimaryLocationTimeZoneProvider;
        return getConfigBoolean(providerEnabledConfigId)
                ? PROVIDER_MODE_ENABLED : PROVIDER_MODE_DISABLED;
    }

    @Override
    public synchronized @ProviderMode String getSecondaryLocationTimeZoneProviderMode() {
        if (mTestSecondaryLocationTimeZoneProviderMode != null) {
            // In test mode: use the test setting value.
            return mTestSecondaryLocationTimeZoneProviderMode;
        }
        return mServerFlags.getOptionalString(ServerFlags.KEY_SECONDARY_LTZP_MODE_OVERRIDE)
                .orElse(getSecondaryLocationTimeZoneProviderModeFromConfig());
    }

    @NonNull
    private synchronized @ProviderMode String getSecondaryLocationTimeZoneProviderModeFromConfig() {
        int providerEnabledConfigId = R.bool.config_enableSecondaryLocationTimeZoneProvider;
        return getConfigBoolean(providerEnabledConfigId)
                ? PROVIDER_MODE_ENABLED : PROVIDER_MODE_DISABLED;
    }

    @Override
    public boolean isGeoDetectionEnabledForUsersByDefault() {
        return mServerFlags.getBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_DEFAULT, false);
    }

    @Override
    @NonNull
    public Optional<Boolean> getGeoDetectionSettingEnabledOverride() {
        return mServerFlags.getOptionalBoolean(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_SETTING_ENABLED_OVERRIDE);
    }

    @Override
    @NonNull
    public Duration getLocationTimeZoneProviderInitializationTimeout() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_MILLIS,
                DEFAULT_LTZP_INITIALIZATION_TIMEOUT);
    }

    @Override
    @NonNull
    public Duration getLocationTimeZoneProviderInitializationTimeoutFuzz() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LTZP_INITIALIZATION_TIMEOUT_FUZZ_MILLIS,
                DEFAULT_LTZP_INITIALIZATION_TIMEOUT_FUZZ);
    }

    @Override
    @NonNull
    public Duration getLocationTimeZoneUncertaintyDelay() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LOCATION_TIME_ZONE_DETECTION_UNCERTAINTY_DELAY_MILLIS,
                DEFAULT_LTZP_UNCERTAINTY_DELAY);
    }

    @Override
    @NonNull
    public Duration getLocationTimeZoneProviderEventFilteringAgeThreshold() {
        return mServerFlags.getDurationFromMillis(
                ServerFlags.KEY_LTZP_EVENT_FILTERING_AGE_THRESHOLD_MILLIS,
                DEFAULT_LTZP_EVENT_FILTER_AGE_THRESHOLD);
    }

    @Override
    public synchronized void resetVolatileTestConfig() {
        mTestPrimaryLocationTimeZoneProviderPackageName = null;
        mTestPrimaryLocationTimeZoneProviderMode = null;
        mTestSecondaryLocationTimeZoneProviderPackageName = null;
        mTestSecondaryLocationTimeZoneProviderMode = null;
        mRecordStateChangesForTests = false;

        // Changing LTZP config can affect the content of ConfigurationInternal, so listeners
        // need to be informed.
        mContext.getMainThreadHandler().post(this::handleConfigurationInternalChangeOnMainThread);
    }

    private boolean isTelephonyFallbackSupported() {
        return mServerFlags.getBoolean(
                ServerFlags.KEY_TIME_ZONE_DETECTOR_TELEPHONY_FALLBACK_SUPPORTED,
                getConfigBoolean(
                        com.android.internal.R.bool.config_supportTelephonyTimeZoneFallback));
    }

    private boolean getConfigBoolean(int providerEnabledConfigId) {
        Resources resources = mContext.getResources();
        return resources.getBoolean(providerEnabledConfigId);
    }
}
