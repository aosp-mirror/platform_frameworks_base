/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import android.annotation.UserIdInt;
import android.app.time.Capabilities.CapabilityState;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Holds configuration values that affect time behaviour.
 */
public final class ConfigurationInternal {

    private final @UserIdInt int mUserId;
    private final boolean mUserConfigAllowed;
    private final boolean mAutoDetectionEnabled;

    private ConfigurationInternal(Builder builder) {
        mUserId = builder.mUserId;
        mUserConfigAllowed = builder.mUserConfigAllowed;
        mAutoDetectionEnabled = builder.mAutoDetectionEnabled;
    }

    /** Returns a {@link TimeCapabilitiesAndConfig} objects based on configuration values. */
    public TimeCapabilitiesAndConfig capabilitiesAndConfig() {
        return new TimeCapabilitiesAndConfig(timeCapabilities(), timeConfiguration());
    }

    private TimeConfiguration timeConfiguration() {
        return new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(mAutoDetectionEnabled)
                .build();
    }

    private TimeCapabilities timeCapabilities() {
        @CapabilityState int configureAutoTimeDetectionEnabledCapability =
                mUserConfigAllowed
                        ? CAPABILITY_POSSESSED
                        : CAPABILITY_NOT_ALLOWED;

        @CapabilityState int suggestTimeManuallyCapability =
                mUserConfigAllowed
                        ? CAPABILITY_POSSESSED
                        : CAPABILITY_NOT_ALLOWED;

        return new TimeCapabilities.Builder(UserHandle.of(mUserId))
                .setConfigureAutoTimeDetectionEnabledCapability(
                        configureAutoTimeDetectionEnabledCapability)
                .setSuggestTimeManuallyCapability(suggestTimeManuallyCapability)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigurationInternal that = (ConfigurationInternal) o;
        return mUserId == that.mUserId
                && mUserConfigAllowed == that.mUserConfigAllowed
                && mAutoDetectionEnabled == that.mAutoDetectionEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId, mUserConfigAllowed, mAutoDetectionEnabled);
    }

    @Override
    public String toString() {
        return "ConfigurationInternal{"
                + "mUserId=" + mUserId
                + ", mUserConfigAllowed=" + mUserConfigAllowed
                + ", mAutoDetectionEnabled=" + mAutoDetectionEnabled
                + '}';
    }

    static final class Builder {
        private final @UserIdInt int mUserId;
        private boolean mUserConfigAllowed;
        private boolean mAutoDetectionEnabled;

        Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        Builder setUserConfigAllowed(boolean userConfigAllowed) {
            mUserConfigAllowed = userConfigAllowed;
            return this;
        }

        Builder setAutoDetectionEnabled(boolean autoDetectionEnabled) {
            mAutoDetectionEnabled = autoDetectionEnabled;
            return this;
        }

        ConfigurationInternal build() {
            return new ConfigurationInternal(this);
        }
    }

}
