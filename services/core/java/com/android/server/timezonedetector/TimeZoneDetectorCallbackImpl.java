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

import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_POSSESSED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AlarmManager;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import java.util.Objects;

/**
 * The real implementation of {@link TimeZoneDetectorStrategyImpl.Callback}.
 */
public final class TimeZoneDetectorCallbackImpl implements TimeZoneDetectorStrategyImpl.Callback {

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    private final Context mContext;
    private final ContentResolver mCr;
    private final UserManager mUserManager;

    TimeZoneDetectorCallbackImpl(Context context) {
        mContext = context;
        mCr = context.getContentResolver();
        mUserManager = context.getSystemService(UserManager.class);
    }

    @Override
    public TimeZoneCapabilities getCapabilities(@UserIdInt int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        boolean disallowConfigDateTime =
                mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, userHandle);

        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(userId);

        // Automatic time zone detection is only supported (currently) on devices if there is a
        // telephony network available.
        if (!deviceHasTelephonyNetwork()) {
            builder.setConfigureAutoDetectionEnabled(CAPABILITY_NOT_SUPPORTED);
        } else if (disallowConfigDateTime) {
            builder.setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED);
        } else {
            builder.setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED);
        }

        // The ability to make manual time zone suggestions can also be restricted by policy. With
        // the current logic above, this could lead to a situation where a device hardware does not
        // support auto detection, the device has been forced into "auto" mode by an admin and the
        // user is unable to disable auto detection.
        if (disallowConfigDateTime) {
            builder.setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED);
        } else if (isAutoDetectionEnabled()) {
            builder.setSuggestManualTimeZone(CAPABILITY_NOT_APPLICABLE);
        } else {
            builder.setSuggestManualTimeZone(CAPABILITY_POSSESSED);
        }
        return builder.build();
    }

    @Override
    public TimeZoneConfiguration getConfiguration(@UserIdInt int userId) {
        return new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(isAutoDetectionEnabled())
                .build();
    }

    @Override
    public void setConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration configuration) {
        Objects.requireNonNull(configuration);
        if (!configuration.isComplete()) {
            throw new IllegalArgumentException("configuration=" + configuration + " not complete");
        }

        // Avoid writing auto detection config for devices that do not support auto time zone
        // detection: if we wrote it down then we'd set the default explicitly. That might influence
        // what happens on later releases that do support auto detection on the same hardware.
        if (isAutoDetectionSupported()) {
            final int value = configuration.isAutoDetectionEnabled() ? 1 : 0;
            Settings.Global.putInt(mCr, Settings.Global.AUTO_TIME_ZONE, value);
        }
    }

    @Override
    public boolean isAutoDetectionEnabled() {
        // To ensure that TimeZoneConfiguration is "complete" for simplicity, devices that do not
        // support auto detection have safe, hard coded configuration values that make it look like
        // auto detection is turned off. It is therefore important that false is returned from this
        // method for devices that do not support auto time zone detection. Such devices will not
        // have a UI to turn the auto detection on/off. Returning true could prevent the user
        // entering information manually. On devices that do support auto time detection the default
        // is to turn auto detection on.
        if (isAutoDetectionSupported()) {
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE, 1 /* default */) > 0;
        }
        return false;
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

    private boolean isAutoDetectionSupported() {
        return deviceHasTelephonyNetwork();
    }

    private boolean deviceHasTelephonyNetwork() {
        // TODO b/150583524 Avoid the use of a deprecated API.
        return mContext.getSystemService(ConnectivityManager.class)
                .isNetworkSupported(ConnectivityManager.TYPE_MOBILE);
    }
}
