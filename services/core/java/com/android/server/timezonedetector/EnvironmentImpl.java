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

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;

import java.util.Objects;

/**
 * The real implementation of {@link TimeZoneDetectorStrategyImpl.Environment}.
 */
final class EnvironmentImpl implements TimeZoneDetectorStrategyImpl.Environment {

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;

    EnvironmentImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull ServiceConfigAccessor serviceConfigAccessor) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);
    }

    @Override
    public void setConfigurationInternalChangeListener(
            @NonNull ConfigurationChangeListener listener) {
        ConfigurationChangeListener configurationChangeListener =
                () -> mHandler.post(listener::onChange);
        mServiceConfigAccessor.addConfigurationInternalChangeListener(configurationChangeListener);
    }

    @Override
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mServiceConfigAccessor.getCurrentUserConfigurationInternal();
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
    public @ElapsedRealtimeLong long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }
}
