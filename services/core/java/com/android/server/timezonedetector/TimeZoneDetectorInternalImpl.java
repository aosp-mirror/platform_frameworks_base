/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The real {@link TimeZoneDetectorInternal} local service implementation.
 *
 * @hide
 */
public final class TimeZoneDetectorInternalImpl implements TimeZoneDetectorInternal {

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final TimeZoneDetectorStrategy mTimeZoneDetectorStrategy;
    @NonNull private final List<ConfigurationChangeListener> mConfigurationListeners =
            new ArrayList<>();

    public TimeZoneDetectorInternalImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull TimeZoneDetectorStrategy timeZoneDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mTimeZoneDetectorStrategy = Objects.requireNonNull(timeZoneDetectorStrategy);

        // Wire up a change listener so that any downstream listeners can be notified when
        // the configuration changes for any reason.
        mTimeZoneDetectorStrategy.addConfigChangeListener(this::handleConfigurationChanged);
    }

    private void handleConfigurationChanged() {
        synchronized (mConfigurationListeners) {
            for (ConfigurationChangeListener listener : mConfigurationListeners) {
                listener.onChange();
            }
        }
    }

    @Override
    public void addDumpable(@NonNull Dumpable dumpable) {
        mTimeZoneDetectorStrategy.addDumpable(dumpable);
    }

    @Override
    public void addConfigurationListener(ConfigurationChangeListener listener) {
        synchronized (mConfigurationListeners) {
            mConfigurationListeners.add(Objects.requireNonNull(listener));
        }
    }

    @Override
    public void removeConfigurationListener(ConfigurationChangeListener listener) {
        synchronized (mConfigurationListeners) {
            mConfigurationListeners.remove(Objects.requireNonNull(listener));
        }
    }

    @Override
    @NonNull
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mTimeZoneDetectorStrategy.getCurrentUserConfigurationInternal();
    }

    @Override
    public void suggestGeolocationTimeZone(
            @NonNull GeolocationTimeZoneSuggestion timeZoneSuggestion) {
        Objects.requireNonNull(timeZoneSuggestion);

        // This call can take place on the mHandler thread because there is no return value.
        mHandler.post(
                () -> mTimeZoneDetectorStrategy.suggestGeolocationTimeZone(timeZoneSuggestion));
    }

    @Override
    @NonNull
    public MetricsTimeZoneDetectorState generateMetricsState() {
        return mTimeZoneDetectorStrategy.generateMetricsState();
    }
}
