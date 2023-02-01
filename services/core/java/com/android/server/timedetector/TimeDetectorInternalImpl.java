/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.timedetector.ManualTimeSuggestion;
import android.content.Context;
import android.os.Handler;

import com.android.server.timezonedetector.CurrentUserIdentityInjector;

import java.util.Objects;

/**
 * The real {@link TimeDetectorInternal} local service implementation.
 *
 * @hide
 */
public class TimeDetectorInternalImpl implements TimeDetectorInternal {

    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final CurrentUserIdentityInjector mCurrentUserIdentityInjector;
    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;

    public TimeDetectorInternalImpl(@NonNull Context context, @NonNull Handler handler,
            @NonNull CurrentUserIdentityInjector currentUserIdentityInjector,
            @NonNull ServiceConfigAccessor serviceConfigAccessor,
            @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCurrentUserIdentityInjector = Objects.requireNonNull(currentUserIdentityInjector);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
    }

    @Override
    @NonNull
    public TimeCapabilitiesAndConfig getCapabilitiesAndConfigForDpm() {
        int currentUserId = mCurrentUserIdentityInjector.getCurrentUserId();
        final boolean bypassUserPolicyCheck = true;
        ConfigurationInternal configurationInternal =
                mServiceConfigAccessor.getConfigurationInternal(currentUserId);
        return configurationInternal.createCapabilitiesAndConfig(bypassUserPolicyCheck);
    }

    @Override
    public boolean updateConfigurationForDpm(@NonNull TimeConfiguration configuration) {
        Objects.requireNonNull(configuration);

        int currentUserId = mCurrentUserIdentityInjector.getCurrentUserId();
        final boolean bypassUserPolicyCheck = true;
        return mServiceConfigAccessor.updateConfiguration(
                currentUserId, configuration, bypassUserPolicyCheck);
    }

    @Override
    public boolean setManualTimeForDpm(@NonNull ManualTimeSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        int userId = mCurrentUserIdentityInjector.getCurrentUserId();
        return mTimeDetectorStrategy.suggestManualTime(userId, suggestion, false);
    }

    @Override
    public void suggestNetworkTime(@NonNull NetworkTimeSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> mTimeDetectorStrategy.suggestNetworkTime(suggestion));
    }

    @Override
    public void suggestGnssTime(@NonNull GnssTimeSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> mTimeDetectorStrategy.suggestGnssTime(suggestion));
    }
}
