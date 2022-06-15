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

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Holds configuration values that affect user-facing time zone behavior and some associated logic.
 * Some configuration is global, some is user scoped, but this class deliberately doesn't make a
 * distinction for simplicity.
 */
public final class ConfigurationInternal {

    private final boolean mTelephonyDetectionSupported;
    private final boolean mGeoDetectionSupported;
    private final boolean mAutoDetectionEnabled;
    private final @UserIdInt int mUserId;
    private final boolean mUserConfigAllowed;
    private final boolean mLocationEnabled;
    private final boolean mGeoDetectionEnabled;

    private ConfigurationInternal(Builder builder) {
        mTelephonyDetectionSupported = builder.mTelephonyDetectionSupported;
        mGeoDetectionSupported = builder.mGeoDetectionSupported;
        mAutoDetectionEnabled = builder.mAutoDetectionEnabled;

        mUserId = builder.mUserId;
        mUserConfigAllowed = builder.mUserConfigAllowed;
        mLocationEnabled = builder.mLocationEnabled;
        mGeoDetectionEnabled = builder.mGeoDetectionEnabled;
    }

    /** Returns true if the device supports any form of auto time zone detection. */
    public boolean isAutoDetectionSupported() {
        return mTelephonyDetectionSupported || mGeoDetectionSupported;
    }

    /** Returns true if the device supports telephony time zone detection. */
    public boolean isTelephonyDetectionSupported() {
        return mTelephonyDetectionSupported;
    }

    /** Returns true if the device supports geolocation time zone detection. */
    public boolean isGeoDetectionSupported() {
        return mGeoDetectionSupported;
    }

    /** Returns the value of the auto time zone detection enabled setting. */
    public boolean getAutoDetectionEnabledSetting() {
        return mAutoDetectionEnabled;
    }

    /**
     * Returns true if auto time zone detection behavior is actually enabled, which can be distinct
     * from the raw setting value.
     */
    public boolean getAutoDetectionEnabledBehavior() {
        return isAutoDetectionSupported() && mAutoDetectionEnabled;
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

    /** Returns true if user's location can be used generally. */
    public boolean isLocationEnabled() {
        return mLocationEnabled;
    }

    /** Returns the value of the geolocation time zone detection enabled setting. */
    public boolean getGeoDetectionEnabledSetting() {
        return mGeoDetectionEnabled;
    }

    /**
     * Returns true if geolocation time zone detection behavior is actually enabled, which can be
     * distinct from the raw setting value.
     */
    public boolean getGeoDetectionEnabledBehavior() {
        return getAutoDetectionEnabledBehavior()
                && isGeoDetectionSupported()
                && isLocationEnabled()
                && getGeoDetectionEnabledSetting();
    }

    /** Creates a {@link TimeZoneCapabilitiesAndConfig} object using the configuration values. */
    public TimeZoneCapabilitiesAndConfig createCapabilitiesAndConfig() {
        return new TimeZoneCapabilitiesAndConfig(asCapabilities(), asConfiguration());
    }

    @NonNull
    private TimeZoneCapabilities asCapabilities() {
        UserHandle userHandle = UserHandle.of(mUserId);
        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(userHandle);

        boolean allowConfigDateTime = isUserConfigAllowed();

        // Automatic time zone detection is only supported on devices if there is a telephony
        // network available or geolocation time zone detection is possible.
        boolean deviceHasAutoTimeZoneDetection = isAutoDetectionSupported();

        final int configureAutoDetectionEnabledCapability;
        if (!deviceHasAutoTimeZoneDetection) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!allowConfigDateTime) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_ALLOWED;
        } else {
            configureAutoDetectionEnabledCapability = CAPABILITY_POSSESSED;
        }
        builder.setConfigureAutoDetectionEnabledCapability(configureAutoDetectionEnabledCapability);

        boolean deviceHasLocationTimeZoneDetection = isGeoDetectionSupported();
        // Note: allowConfigDateTime does not restrict the ability to change location time zone
        // detection enabled. This is intentional as it has user privacy implications and so it
        // makes sense to leave this under a user's control.
        final int configureGeolocationDetectionEnabledCapability;
        if (!deviceHasLocationTimeZoneDetection) {
            configureGeolocationDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!mAutoDetectionEnabled || !isLocationEnabled()) {
            configureGeolocationDetectionEnabledCapability = CAPABILITY_NOT_APPLICABLE;
        } else {
            configureGeolocationDetectionEnabledCapability = CAPABILITY_POSSESSED;
        }
        builder.setConfigureGeoDetectionEnabledCapability(
                configureGeolocationDetectionEnabledCapability);

        // The ability to make manual time zone suggestions can also be restricted by policy. With
        // the current logic above, this could lead to a situation where a device hardware does not
        // support auto detection, the device has been forced into "auto" mode by an admin and the
        // user is unable to disable auto detection.
        final int suggestManualTimeZoneCapability;
        if (!allowConfigDateTime) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_ALLOWED;
        } else if (getAutoDetectionEnabledBehavior()) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_APPLICABLE;
        } else {
            suggestManualTimeZoneCapability = CAPABILITY_POSSESSED;
        }
        builder.setSuggestManualTimeZoneCapability(suggestManualTimeZoneCapability);

        return builder.build();
    }

    /** Returns a {@link TimeZoneConfiguration} from the configuration values. */
    private TimeZoneConfiguration asConfiguration() {
        return new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(getAutoDetectionEnabledSetting())
                .setGeoDetectionEnabled(getGeoDetectionEnabledSetting())
                .build();
    }

    /**
     * Merges the configuration values from this with any properties set in {@code
     * newConfiguration}. The new configuration has precedence. Used to apply user updates to
     * internal configuration.
     */
    public ConfigurationInternal merge(TimeZoneConfiguration newConfiguration) {
        Builder builder = new Builder(this);
        if (newConfiguration.hasIsAutoDetectionEnabled()) {
            builder.setAutoDetectionEnabled(newConfiguration.isAutoDetectionEnabled());
        }
        if (newConfiguration.hasIsGeoDetectionEnabled()) {
            builder.setGeoDetectionEnabled(newConfiguration.isGeoDetectionEnabled());
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ConfigurationInternal that = (ConfigurationInternal) o;
        return mUserId == that.mUserId
                && mUserConfigAllowed == that.mUserConfigAllowed
                && mTelephonyDetectionSupported == that.mTelephonyDetectionSupported
                && mGeoDetectionSupported == that.mGeoDetectionSupported
                && mAutoDetectionEnabled == that.mAutoDetectionEnabled
                && mLocationEnabled == that.mLocationEnabled
                && mGeoDetectionEnabled == that.mGeoDetectionEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId, mUserConfigAllowed, mTelephonyDetectionSupported,
                mGeoDetectionSupported, mAutoDetectionEnabled, mLocationEnabled,
                mGeoDetectionEnabled);
    }

    @Override
    public String toString() {
        return "ConfigurationInternal{"
                + "mUserId=" + mUserId
                + ", mUserConfigAllowed=" + mUserConfigAllowed
                + ", mTelephonyDetectionSupported=" + mTelephonyDetectionSupported
                + ", mGeoDetectionSupported=" + mGeoDetectionSupported
                + ", mAutoDetectionEnabled=" + mAutoDetectionEnabled
                + ", mLocationEnabled=" + mLocationEnabled
                + ", mGeoDetectionEnabled=" + mGeoDetectionEnabled
                + '}';
    }

    /**
     * A Builder for {@link ConfigurationInternal}.
     */
    public static class Builder {

        private final @UserIdInt int mUserId;

        private boolean mUserConfigAllowed;
        private boolean mTelephonyDetectionSupported;
        private boolean mGeoDetectionSupported;
        private boolean mAutoDetectionEnabled;
        private boolean mLocationEnabled;
        private boolean mGeoDetectionEnabled;

        /**
         * Creates a new Builder with only the userId set.
         */
        public Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Creates a new Builder by copying values from an existing instance.
         */
        public Builder(ConfigurationInternal toCopy) {
            this.mUserId = toCopy.mUserId;
            this.mUserConfigAllowed = toCopy.mUserConfigAllowed;
            this.mTelephonyDetectionSupported = toCopy.mTelephonyDetectionSupported;
            this.mGeoDetectionSupported = toCopy.mGeoDetectionSupported;
            this.mAutoDetectionEnabled = toCopy.mAutoDetectionEnabled;
            this.mLocationEnabled = toCopy.mLocationEnabled;
            this.mGeoDetectionEnabled = toCopy.mGeoDetectionEnabled;
        }

        /**
         * Sets whether the user is allowed to configure time zone settings on this device.
         */
        public Builder setUserConfigAllowed(boolean configAllowed) {
            mUserConfigAllowed = configAllowed;
            return this;
        }

        /**
         * Sets whether telephony time zone detection is supported on this device.
         */
        public Builder setTelephonyDetectionFeatureSupported(boolean supported) {
            mTelephonyDetectionSupported = supported;
            return this;
        }

        /**
         * Sets whether geolocation time zone detection is supported on this device.
         */
        public Builder setGeoDetectionFeatureSupported(boolean supported) {
            mGeoDetectionSupported = supported;
            return this;
        }

        /**
         * Sets the value of the automatic time zone detection enabled setting for this device.
         */
        public Builder setAutoDetectionEnabled(boolean enabled) {
            mAutoDetectionEnabled = enabled;
            return this;
        }

        /**
         * Sets the value of the location mode setting for this user.
         */
        public Builder setLocationEnabled(boolean enabled) {
            mLocationEnabled = enabled;
            return this;
        }

        /**
         * Sets the value of the geolocation time zone detection setting for this user.
         */
        public Builder setGeoDetectionEnabled(boolean enabled) {
            mGeoDetectionEnabled = enabled;
            return this;
        }

        /** Returns a new {@link ConfigurationInternal}. */
        @NonNull
        public ConfigurationInternal build() {
            return new ConfigurationInternal(this);
        }
    }
}
