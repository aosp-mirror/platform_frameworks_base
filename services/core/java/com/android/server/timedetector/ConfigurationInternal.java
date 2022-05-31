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
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.Capabilities.CapabilityState;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Holds configuration values that affect user-facing time behavior and some associated logic.
 * Some configuration is global, some is user scoped, but this class deliberately doesn't make a
 * distinction for simplicity.
 */
public final class ConfigurationInternal {

    private final boolean mAutoDetectionSupported;
    private final boolean mAutoDetectionEnabledSetting;
    private final @UserIdInt int mUserId;
    private final boolean mUserConfigAllowed;

    private ConfigurationInternal(Builder builder) {
        mAutoDetectionSupported = builder.mAutoDetectionSupported;
        mAutoDetectionEnabledSetting = builder.mAutoDetectionEnabledSetting;

        mUserId = builder.mUserId;
        mUserConfigAllowed = builder.mUserConfigAllowed;
    }

    /** Returns true if the device supports any form of auto time detection. */
    public boolean isAutoDetectionSupported() {
        return mAutoDetectionSupported;
    }

    /** Returns the value of the auto time detection enabled setting. */
    public boolean getAutoDetectionEnabledSetting() {
        return mAutoDetectionEnabledSetting;
    }

    /**
     * Returns true if auto time detection behavior is actually enabled, which can be distinct
     * from the raw setting value.
     */
    public boolean getAutoDetectionEnabledBehavior() {
        return isAutoDetectionSupported() && mAutoDetectionEnabledSetting;
    }

    /** Returns the ID of the user this configuration is associated with. */
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /** Returns the handle of the user this configuration is associated with. */
    @NonNull
    public UserHandle getUserHandle() {
        return UserHandle.of(mUserId);
    }

    /** Returns true if the user allowed to modify time zone configuration. */
    public boolean isUserConfigAllowed() {
        return mUserConfigAllowed;
    }

    /** Returns a {@link TimeCapabilitiesAndConfig} objects based on configuration values. */
    public TimeCapabilitiesAndConfig capabilitiesAndConfig() {
        return new TimeCapabilitiesAndConfig(timeCapabilities(), timeConfiguration());
    }

    private TimeCapabilities timeCapabilities() {
        UserHandle userHandle = UserHandle.of(mUserId);
        TimeCapabilities.Builder builder = new TimeCapabilities.Builder(userHandle);

        boolean allowConfigDateTime = isUserConfigAllowed();

        boolean deviceHasAutoTimeDetection = isAutoDetectionSupported();
        final @CapabilityState int configureAutoDetectionEnabledCapability;
        if (!deviceHasAutoTimeDetection) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!allowConfigDateTime) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_ALLOWED;
        } else {
            configureAutoDetectionEnabledCapability = CAPABILITY_POSSESSED;
        }
        builder.setConfigureAutoDetectionEnabledCapability(configureAutoDetectionEnabledCapability);

        // The ability to make manual time suggestions can also be restricted by policy. With the
        // current logic above, this could lead to a situation where a device hardware does not
        // support auto detection, the device has been forced into "auto" mode by an admin and the
        // user is unable to disable auto detection.
        final @CapabilityState int suggestManualTimeZoneCapability;
        if (!allowConfigDateTime) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_ALLOWED;
        } else if (getAutoDetectionEnabledBehavior()) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_APPLICABLE;
        } else {
            suggestManualTimeZoneCapability = CAPABILITY_POSSESSED;
        }
        builder.setSuggestManualTimeCapability(suggestManualTimeZoneCapability);

        return builder.build();
    }

    /** Returns a {@link TimeConfiguration} from the configuration values. */
    private TimeConfiguration timeConfiguration() {
        return new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(getAutoDetectionEnabledSetting())
                .build();
    }

    /**
     * Merges the configuration values from this with any properties set in {@code
     * newConfiguration}. The new configuration has precedence. Used to apply user updates to
     * internal configuration.
     */
    public ConfigurationInternal merge(TimeConfiguration newConfiguration) {
        Builder builder = new Builder(this);
        if (newConfiguration.hasIsAutoDetectionEnabled()) {
            builder.setAutoDetectionEnabledSetting(newConfiguration.isAutoDetectionEnabled());
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigurationInternal that = (ConfigurationInternal) o;
        return mAutoDetectionSupported == that.mAutoDetectionSupported
                && mUserId == that.mUserId
                && mUserConfigAllowed == that.mUserConfigAllowed
                && mAutoDetectionEnabledSetting == that.mAutoDetectionEnabledSetting;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAutoDetectionSupported, mUserId,
                mUserConfigAllowed, mAutoDetectionEnabledSetting);
    }

    @Override
    public String toString() {
        return "ConfigurationInternal{"
                + "mAutoDetectionSupported=" + mAutoDetectionSupported
                + "mUserId=" + mUserId
                + ", mUserConfigAllowed=" + mUserConfigAllowed
                + ", mAutoDetectionEnabled=" + mAutoDetectionEnabledSetting
                + '}';
    }

    static final class Builder {
        private final @UserIdInt int mUserId;

        private boolean mUserConfigAllowed;
        private boolean mAutoDetectionSupported;
        private boolean mAutoDetectionEnabledSetting;

        Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Creates a new Builder by copying values from an existing instance.
         */
        Builder(ConfigurationInternal toCopy) {
            this.mUserId = toCopy.mUserId;
            this.mUserConfigAllowed = toCopy.mUserConfigAllowed;
            this.mAutoDetectionSupported = toCopy.mAutoDetectionSupported;
            this.mAutoDetectionEnabledSetting = toCopy.mAutoDetectionEnabledSetting;
        }

        /**
         * Sets whether the user is allowed to configure time settings on this device.
         */
        Builder setUserConfigAllowed(boolean userConfigAllowed) {
            mUserConfigAllowed = userConfigAllowed;
            return this;
        }

        /**
         * Sets whether automatic time detection is supported on this device.
         */
        public Builder setAutoDetectionSupported(boolean supported) {
            mAutoDetectionSupported = supported;
            return this;
        }

        /**
         * Sets the value of the automatic time detection enabled setting for this device.
         */
        Builder setAutoDetectionEnabledSetting(boolean autoDetectionEnabledSetting) {
            mAutoDetectionEnabledSetting = autoDetectionEnabledSetting;
            return this;
        }

        /** Returns a new {@link ConfigurationInternal}. */
        @NonNull
        ConfigurationInternal build() {
            return new ConfigurationInternal(this);
        }
    }

}
