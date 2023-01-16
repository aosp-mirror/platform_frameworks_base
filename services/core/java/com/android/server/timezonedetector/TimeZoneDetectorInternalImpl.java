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
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.content.Context;
import android.os.Handler;

import java.util.Objects;

/**
 * The real {@link TimeZoneDetectorInternal} local service implementation.
 *
 * @hide
 */
public final class TimeZoneDetectorInternalImpl implements TimeZoneDetectorInternal {

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final CurrentUserIdentityInjector mCurrentUserIdentityInjector;
    @NonNull private final TimeZoneDetectorStrategy mTimeZoneDetectorStrategy;

    public TimeZoneDetectorInternalImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull CurrentUserIdentityInjector currentUserIdentityInjector,
            @NonNull TimeZoneDetectorStrategy timeZoneDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCurrentUserIdentityInjector = Objects.requireNonNull(currentUserIdentityInjector);
        mTimeZoneDetectorStrategy = Objects.requireNonNull(timeZoneDetectorStrategy);
    }

    @Override
    @NonNull
    public TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfigForDpm() {
        int currentUserId = mCurrentUserIdentityInjector.getCurrentUserId();
        final boolean bypassUserPolicyChecks = true;
        return mTimeZoneDetectorStrategy.getCapabilitiesAndConfig(
                currentUserId, bypassUserPolicyChecks);
    }

    @Override
    public boolean updateConfigurationForDpm(@NonNull TimeZoneConfiguration configuration) {
        Objects.requireNonNull(configuration);

        int currentUserId = mCurrentUserIdentityInjector.getCurrentUserId();
        final boolean bypassUserPolicyChecks = true;
        return mTimeZoneDetectorStrategy.updateConfiguration(
                currentUserId, configuration, bypassUserPolicyChecks);
    }

    @Override
    public boolean setManualTimeZoneForDpm(@NonNull ManualTimeZoneSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        int currentUserId = mCurrentUserIdentityInjector.getCurrentUserId();
        final boolean bypassUserPolicyChecks = true;
        return mTimeZoneDetectorStrategy.suggestManualTimeZone(
                currentUserId, suggestion, bypassUserPolicyChecks);
    }

    @Override
    public void handleLocationAlgorithmEvent(
            @NonNull LocationAlgorithmEvent locationAlgorithmEvent) {
        Objects.requireNonNull(locationAlgorithmEvent);

        // This call can take place on the mHandler thread because there is no return value.
        mHandler.post(
                () -> mTimeZoneDetectorStrategy.handleLocationAlgorithmEvent(
                        locationAlgorithmEvent));
    }

    @Override
    @NonNull
    public MetricsTimeZoneDetectorState generateMetricsState() {
        return mTimeZoneDetectorStrategy.generateMetricsState();
    }
}
