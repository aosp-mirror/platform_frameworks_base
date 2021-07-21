/*
 * Copyright 2019 The Android Open Source Project
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
import android.app.AlarmManager;
import android.app.time.TimeZoneConfiguration;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

import java.util.Objects;
import java.util.Optional;

/**
 * The real implementation of {@link TimeZoneDetectorStrategyImpl.Environment}.
 */
final class EnvironmentImpl implements TimeZoneDetectorStrategyImpl.Environment {

    private static final String LOG_TAG = TimeZoneDetectorService.TAG;
    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final ContentResolver mCr;
    @NonNull private final UserManager mUserManager;
    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;
    @NonNull private final LocationManager mLocationManager;

    // @NonNull after setConfigChangeListener() is called.
    @GuardedBy("this")
    private ConfigurationChangeListener mConfigChangeListener;

    EnvironmentImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull ServiceConfigAccessor serviceConfigAccessor) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCr = context.getContentResolver();
        mUserManager = context.getSystemService(UserManager.class);
        mLocationManager = context.getSystemService(LocationManager.class);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);

        // Wire up the config change listeners. All invocations are performed on the mHandler
        // thread.

        // Listen for the user changing / the user's location mode changing.
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USER_SWITCHED);
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleConfigChangeOnHandlerThread();
            }
        }, filter, null, mHandler);

        // Add async callbacks for global settings being changed.
        ContentResolver contentResolver = mContext.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        handleConfigChangeOnHandlerThread();
                    }
                });

        // Add async callbacks for user scoped location settings being changed.
        contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED),
                true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        handleConfigChangeOnHandlerThread();
                    }
                }, UserHandle.USER_ALL);
    }

    private void handleConfigChangeOnHandlerThread() {
        synchronized (this) {
            if (mConfigChangeListener == null) {
                Slog.wtf(LOG_TAG, "mConfigChangeListener is unexpectedly null");
            }
            mConfigChangeListener.onChange();
        }
    }

    @Override
    public void setConfigChangeListener(@NonNull ConfigurationChangeListener listener) {
        synchronized (this) {
            mConfigChangeListener = Objects.requireNonNull(listener);
        }
    }

    @Override
    public ConfigurationInternal getConfigurationInternal(@UserIdInt int userId) {
        return new ConfigurationInternal.Builder(userId)
                .setTelephonyDetectionFeatureSupported(
                        mServiceConfigAccessor.isTelephonyTimeZoneDetectionFeatureSupported())
                .setGeoDetectionFeatureSupported(
                        mServiceConfigAccessor.isGeoTimeZoneDetectionFeatureSupported())
                .setAutoDetectionEnabled(isAutoDetectionEnabled())
                .setUserConfigAllowed(isUserConfigAllowed(userId))
                .setLocationEnabled(isLocationEnabled(userId))
                .setGeoDetectionEnabled(isGeoDetectionEnabled(userId))
                .build();
    }

    @Override
    public @UserIdInt int getCurrentUserId() {
        return LocalServices.getService(ActivityManagerInternal.class).getCurrentUserId();
    }

    @Override
    public boolean isDeviceTimeZoneInitialized() {
        // timezone.equals("GMT") will be true and only true if the time zone was
        // set to a default value by the system server (when starting, system server
        // sets the persist.sys.timezone to "GMT" if it's not set). "GMT" is not used by
        // any code that sets it explicitly (in case where something sets GMT explicitly,
        // "Etc/GMT" Olson ID would be used).

        String timeZoneId = getDeviceTimeZone();
        return timeZoneId != null && timeZoneId.length() > 0 && !timeZoneId.equals("GMT");
    }

    @Override
    @Nullable
    public String getDeviceTimeZone() {
        return SystemProperties.get(TIMEZONE_PROPERTY);
    }

    @Override
    public void setDeviceTimeZone(String zoneId) {
        AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
        alarmManager.setTimeZone(zoneId);
    }

    @Override
    public void storeConfiguration(@UserIdInt int userId, TimeZoneConfiguration configuration) {
        Objects.requireNonNull(configuration);

        // Avoid writing the auto detection enabled setting for devices that do not support auto
        // time zone detection: if we wrote it down then we'd set the value explicitly, which would
        // prevent detecting "default" later. That might influence what happens on later releases
        // that support new types of auto detection on the same hardware.
        if (mServiceConfigAccessor.isAutoDetectionFeatureSupported()) {
            final boolean autoDetectionEnabled = configuration.isAutoDetectionEnabled();
            setAutoDetectionEnabledIfRequired(autoDetectionEnabled);

            // Avoid writing the geo detection enabled setting for devices with settings that
            // are currently overridden by server flags: otherwise we might overwrite a droidfood
            // user's real setting permanently.
            // Also avoid writing the geo detection enabled setting for devices that do not support
            // geo time zone detection: if we wrote it down then we'd set the value explicitly,
            // which would prevent detecting "default" later. That might influence what happens on
            // later releases that start to support geo detection on the same hardware.
            if (!mServiceConfigAccessor.getGeoDetectionSettingEnabledOverride().isPresent()
                    && mServiceConfigAccessor.isGeoTimeZoneDetectionFeatureSupported()) {
                final boolean geoTzDetectionEnabled = configuration.isGeoDetectionEnabled();
                setGeoDetectionEnabledIfRequired(userId, geoTzDetectionEnabled);
            }
        }
    }

    private boolean isUserConfigAllowed(@UserIdInt int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        return !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, userHandle);
    }

    private boolean isAutoDetectionEnabled() {
        return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE, 1 /* default */) > 0;
    }

    private void setAutoDetectionEnabledIfRequired(boolean enabled) {
        // This check is racey, but the whole settings update process is racey. This check prevents
        // a ConfigurationChangeListener callback triggering due to ContentObserver's still
        // triggering *sometimes* for no-op updates. Because callbacks are async this is necessary
        // for stable behavior during tests.
        if (isAutoDetectionEnabled() != enabled) {
            Settings.Global.putInt(mCr, Settings.Global.AUTO_TIME_ZONE, enabled ? 1 : 0);
        }
    }

    private boolean isLocationEnabled(@UserIdInt int userId) {
        return mLocationManager.isLocationEnabledForUser(UserHandle.of(userId));
    }

    private boolean isGeoDetectionEnabled(@UserIdInt int userId) {
        // We may never use this, but it gives us a way to force location-based time zone detection
        // on/off for testers (but only where their other settings would allow them to turn it on
        // for themselves).
        Optional<Boolean> override = mServiceConfigAccessor.getGeoDetectionSettingEnabledOverride();
        if (override.isPresent()) {
            return override.get();
        }

        final boolean geoDetectionEnabledByDefault =
                mServiceConfigAccessor.isGeoDetectionEnabledForUsersByDefault();
        return Settings.Secure.getIntForUser(mCr,
                Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED,
                (geoDetectionEnabledByDefault ? 1 : 0) /* defaultValue */, userId) != 0;
    }

    private void setGeoDetectionEnabledIfRequired(@UserIdInt int userId, boolean enabled) {
        // See comment in setAutoDetectionEnabledIfRequired. http://b/171953500
        if (isGeoDetectionEnabled(userId) != enabled) {
            Settings.Secure.putIntForUser(mCr, Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED,
                    enabled ? 1 : 0, userId);
        }
    }
}
