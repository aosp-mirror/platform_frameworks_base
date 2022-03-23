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

package com.android.server.timezonedetector.location;

import android.annotation.NonNull;

import com.android.server.LocalServices;
import com.android.server.timezonedetector.ConfigurationChangeListener;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.ServiceConfigAccessor;
import com.android.server.timezonedetector.TimeZoneDetectorInternal;

import java.time.Duration;
import java.util.Objects;

/**
 * The real implementation of {@link LocationTimeZoneProviderController.Environment} used by
 * {@link ControllerImpl} to interact with other server components.
 */
class ControllerEnvironmentImpl extends LocationTimeZoneProviderController.Environment {

    @NonNull private final TimeZoneDetectorInternal mTimeZoneDetectorInternal;
    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;
    @NonNull private final ConfigurationChangeListener mConfigurationChangeListener;

    ControllerEnvironmentImpl(@NonNull ThreadingDomain threadingDomain,
            @NonNull ServiceConfigAccessor serviceConfigAccessor,
            @NonNull LocationTimeZoneProviderController controller) {
        super(threadingDomain);
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);
        mTimeZoneDetectorInternal = LocalServices.getService(TimeZoneDetectorInternal.class);

        // Listen for configuration changes.
        mConfigurationChangeListener = () -> mThreadingDomain.post(controller::onConfigChanged);
        mTimeZoneDetectorInternal.addConfigurationListener(mConfigurationChangeListener);
    }


    @Override
    void destroy() {
        mTimeZoneDetectorInternal.removeConfigurationListener(mConfigurationChangeListener);
    }

    @Override
    @NonNull
    ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mTimeZoneDetectorInternal.getCurrentUserConfigurationInternal();
    }

    @Override
    @NonNull
    Duration getProviderInitializationTimeout() {
        return mServiceConfigAccessor.getLocationTimeZoneProviderInitializationTimeout();
    }

    @Override
    @NonNull
    Duration getProviderInitializationTimeoutFuzz() {
        return mServiceConfigAccessor.getLocationTimeZoneProviderInitializationTimeoutFuzz();
    }

    @Override
    @NonNull
    Duration getUncertaintyDelay() {
        return mServiceConfigAccessor.getLocationTimeZoneUncertaintyDelay();
    }
}
