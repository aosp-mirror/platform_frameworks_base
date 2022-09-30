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
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.server.AlarmManagerInternal;
import com.android.server.LocalServices;
import com.android.server.SystemTimeZone;
import com.android.server.SystemTimeZone.TimeZoneConfidence;

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
    @NonNull
    public String getDeviceTimeZone() {
        return SystemProperties.get(TIMEZONE_PROPERTY);
    }

    @Override
    public @TimeZoneConfidence int getDeviceTimeZoneConfidence() {
        return SystemTimeZone.getTimeZoneConfidence();
    }

    @Override
    public void setDeviceTimeZoneAndConfidence(
            @NonNull String zoneId, @TimeZoneConfidence int confidence) {
        AlarmManagerInternal alarmManagerInternal =
                LocalServices.getService(AlarmManagerInternal.class);
        alarmManagerInternal.setTimeZone(zoneId, confidence);
    }

    @Override
    public @ElapsedRealtimeLong long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }
}
