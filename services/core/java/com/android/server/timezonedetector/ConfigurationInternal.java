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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.Capabilities.CapabilityState;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneConfiguration;
import android.os.UserHandle;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/**
 * Holds configuration values that affect user-facing time zone behavior and some associated logic.
 * Some configuration is global, some is user scoped, but this class deliberately doesn't make a
 * distinction for simplicity.
 */
public final class ConfigurationInternal {

    @IntDef(prefix = "DETECTION_MODE_",
            value = { DETECTION_MODE_UNKNOWN, DETECTION_MODE_MANUAL, DETECTION_MODE_GEO,
                    DETECTION_MODE_TELEPHONY }
    )
    @Retention(RetentionPolicy.SOURCE)
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @interface DetectionMode {};

    public static final @DetectionMode int DETECTION_MODE_UNKNOWN = 0;
    public static final @DetectionMode int DETECTION_MODE_MANUAL = 1;
    public static final @DetectionMode int DETECTION_MODE_GEO = 2;
    public static final @DetectionMode int DETECTION_MODE_TELEPHONY = 3;

    private final boolean mTelephonyDetectionSupported;
    private final boolean mGeoDetectionSupported;
    private final boolean mTelephonyFallbackSupported;
    private final boolean mGeoDetectionRunInBackgroundEnabled;
    private final boolean mEnhancedMetricsCollectionEnabled;
    private final boolean mAutoDetectionEnabledSetting;
    private final @UserIdInt int mUserId;
    private final boolean mUserConfigAllowed;
    private final boolean mLocationEnabledSetting;
    private final boolean mGeoDetectionEnabledSetting;

    private ConfigurationInternal(Builder builder) {
        mTelephonyDetectionSupported = builder.mTelephonyDetectionSupported;
        mGeoDetectionSupported = builder.mGeoDetectionSupported;
        mTelephonyFallbackSupported = builder.mTelephonyFallbackSupported;
        mGeoDetectionRunInBackgroundEnabled = builder.mGeoDetectionRunInBackgroundEnabled;
        mEnhancedMetricsCollectionEnabled = builder.mEnhancedMetricsCollectionEnabled;
        mAutoDetectionEnabledSetting = builder.mAutoDetectionEnabledSetting;

        mUserId = Objects.requireNonNull(builder.mUserId, "userId must be set");
        mUserConfigAllowed = builder.mUserConfigAllowed;
        mLocationEnabledSetting = builder.mLocationEnabledSetting;
        mGeoDetectionEnabledSetting = builder.mGeoDetectionEnabledSetting;
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

    /**
     * Returns true if the device supports time zone detection falling back to telephony detection
     * under certain circumstances.
     */
    public boolean isTelephonyFallbackSupported() {
        return mTelephonyFallbackSupported;
    }

    /**
     * Returns {@code true} if location time zone detection should run all the time on supported
     * devices, even when the user has not enabled it explicitly in settings. Enabled for internal
     * testing only. See {@link #isGeoDetectionExecutionEnabled()} and {@link #getDetectionMode()}
     * for details.
     */
    boolean getGeoDetectionRunInBackgroundEnabledSetting() {
        return mGeoDetectionRunInBackgroundEnabled;
    }

    /**
     * Returns {@code true} if the device can collect / report extra metrics information for QA
     * / testers. These metrics might involve logging more expensive or more revealing data that
     * would not be collected from the set of public users.
     */
    public boolean isEnhancedMetricsCollectionEnabled() {
        return mEnhancedMetricsCollectionEnabled;
    }

    /** Returns the value of the auto time zone detection enabled setting. */
    public boolean getAutoDetectionEnabledSetting() {
        return mAutoDetectionEnabledSetting;
    }

    /**
     * Returns true if auto time zone detection behavior is actually enabled, which can be distinct
     * from the raw setting value.
     */
    public boolean getAutoDetectionEnabledBehavior() {
        return isAutoDetectionSupported() && getAutoDetectionEnabledSetting();
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

    /**
     * Returns true if the user is allowed to modify time zone configuration, e.g. can be false due
     * to device policy (enterprise).
     *
     * <p>See also {@link #asCapabilities(boolean)} for situations where this value is ignored.
     */
    public boolean isUserConfigAllowed() {
        return mUserConfigAllowed;
    }

    /** Returns true if user's location can be used generally. */
    public boolean getLocationEnabledSetting() {
        return mLocationEnabledSetting;
    }

    /** Returns the value of the geolocation time zone detection enabled setting. */
    public boolean getGeoDetectionEnabledSetting() {
        return mGeoDetectionEnabledSetting;
    }

    /**
     * Returns the detection mode to use, i.e. which suggestions to use to determine the device's
     * time zone.
     */
    public @DetectionMode int getDetectionMode() {
        if (!isAutoDetectionSupported()) {
            // Handle the easy case first: No auto detection algorithms supported must mean manual.
            return DETECTION_MODE_MANUAL;
        } else if (!getAutoDetectionEnabledSetting()) {
            // Auto detection algorithms are supported, but disabled by the user.
            return DETECTION_MODE_MANUAL;
        } else if (getGeoDetectionEnabledBehavior()) {
            return DETECTION_MODE_GEO;
        } else if (isTelephonyDetectionSupported()) {
            return DETECTION_MODE_TELEPHONY;
        } else {
            // On devices with telephony detection support, telephony is used instead of geo when
            // geo cannot be used. This "unknown" case can occur on devices with only the location
            // detection algorithm supported when the user's master location setting prevents its
            // use.
            return DETECTION_MODE_UNKNOWN;
        }
    }

    private boolean getGeoDetectionEnabledBehavior() {
        // isAutoDetectionSupported() should already have been checked before calling this method.
        if (isGeoDetectionSupported() && getLocationEnabledSetting()) {
            if (isTelephonyDetectionSupported()) {
                // This is the "normal" case for smartphones that have both telephony and geo
                // detection: the user chooses which type of detection to use.
                return getGeoDetectionEnabledSetting();
            } else {
                // When only geo detection is supported then there is no choice for the user to
                // make between detection modes, so no user setting is consulted.
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if geolocation time zone detection behavior can execute. Typically, this will
     * agree with {@link #getDetectionMode()}, but under rare circumstances the geolocation detector
     * may be run in the background if the user's settings allow.
     */
    public boolean isGeoDetectionExecutionEnabled() {
        return getDetectionMode() == DETECTION_MODE_GEO
                || getGeoDetectionRunInBackgroundEnabledBehavior();
    }

    private boolean getGeoDetectionRunInBackgroundEnabledBehavior() {
        return isGeoDetectionSupported()
                && getLocationEnabledSetting()
                && getGeoDetectionRunInBackgroundEnabledSetting();
    }

    @NonNull
    public TimeZoneCapabilities asCapabilities(boolean bypassUserPolicyChecks) {
        UserHandle userHandle = UserHandle.of(mUserId);
        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(userHandle);

        boolean allowConfigDateTime = isUserConfigAllowed() || bypassUserPolicyChecks;

        // Automatic time zone detection is only supported on devices if there is a telephony
        // network available or geolocation time zone detection is possible.
        boolean deviceHasAutoTimeZoneDetection = isAutoDetectionSupported();

        final @CapabilityState int configureAutoDetectionEnabledCapability;
        if (!deviceHasAutoTimeZoneDetection) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!allowConfigDateTime) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_ALLOWED;
        } else {
            configureAutoDetectionEnabledCapability = CAPABILITY_POSSESSED;
        }
        builder.setConfigureAutoDetectionEnabledCapability(configureAutoDetectionEnabledCapability);

        builder.setUseLocationEnabled(mLocationEnabledSetting);

        boolean deviceHasLocationTimeZoneDetection = isGeoDetectionSupported();
        boolean deviceHasTelephonyDetection = isTelephonyDetectionSupported();

        // Note: allowConfigDateTime does not restrict the ability to change location time zone
        // detection enabled. This is intentional as it has user privacy implications and so it
        // makes sense to leave this under a user's control. The only time this is not true is
        // on devices that only support location-based detection and the main auto detection setting
        // is used to influence whether location can be used.
        final @CapabilityState int configureGeolocationDetectionEnabledCapability;
        if (!deviceHasLocationTimeZoneDetection || !deviceHasTelephonyDetection) {
            // If the device doesn't have geolocation detection support OR it ONLY has geolocation
            // detection support (no telephony) then the user doesn't need the ability to toggle the
            // location-based detection on and off (the auto detection toggle is considered
            // sufficient).
            configureGeolocationDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!mAutoDetectionEnabledSetting || !getLocationEnabledSetting()) {
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
        final @CapabilityState int suggestManualTimeZoneCapability;
        if (!allowConfigDateTime) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_ALLOWED;
        } else if (getAutoDetectionEnabledBehavior()) {
            suggestManualTimeZoneCapability = CAPABILITY_NOT_APPLICABLE;
        } else {
            suggestManualTimeZoneCapability = CAPABILITY_POSSESSED;
        }
        builder.setSetManualTimeZoneCapability(suggestManualTimeZoneCapability);

        return builder.build();
    }

    /** Returns a {@link TimeZoneConfiguration} from the configuration values. */
    public TimeZoneConfiguration asConfiguration() {
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
            builder.setAutoDetectionEnabledSetting(newConfiguration.isAutoDetectionEnabled());
        }
        if (newConfiguration.hasIsGeoDetectionEnabled()) {
            builder.setGeoDetectionEnabledSetting(newConfiguration.isGeoDetectionEnabled());
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
                && mTelephonyFallbackSupported == that.mTelephonyFallbackSupported
                && mGeoDetectionRunInBackgroundEnabled == that.mGeoDetectionRunInBackgroundEnabled
                && mEnhancedMetricsCollectionEnabled == that.mEnhancedMetricsCollectionEnabled
                && mAutoDetectionEnabledSetting == that.mAutoDetectionEnabledSetting
                && mLocationEnabledSetting == that.mLocationEnabledSetting
                && mGeoDetectionEnabledSetting == that.mGeoDetectionEnabledSetting;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId, mUserConfigAllowed, mTelephonyDetectionSupported,
                mGeoDetectionSupported, mTelephonyFallbackSupported,
                mGeoDetectionRunInBackgroundEnabled, mEnhancedMetricsCollectionEnabled,
                mAutoDetectionEnabledSetting, mLocationEnabledSetting, mGeoDetectionEnabledSetting);
    }

    @Override
    public String toString() {
        return "ConfigurationInternal{"
                + "mUserId=" + mUserId
                + ", mUserConfigAllowed=" + mUserConfigAllowed
                + ", mTelephonyDetectionSupported=" + mTelephonyDetectionSupported
                + ", mGeoDetectionSupported=" + mGeoDetectionSupported
                + ", mTelephonyFallbackSupported=" + mTelephonyFallbackSupported
                + ", mGeoDetectionRunInBackgroundEnabled=" + mGeoDetectionRunInBackgroundEnabled
                + ", mEnhancedMetricsCollectionEnabled=" + mEnhancedMetricsCollectionEnabled
                + ", mAutoDetectionEnabledSetting=" + mAutoDetectionEnabledSetting
                + ", mLocationEnabledSetting=" + mLocationEnabledSetting
                + ", mGeoDetectionEnabledSetting=" + mGeoDetectionEnabledSetting
                + '}';
    }

    /**
     * A Builder for {@link ConfigurationInternal}.
     */
    public static class Builder {

        private @UserIdInt Integer mUserId;
        private boolean mUserConfigAllowed;
        private boolean mTelephonyDetectionSupported;
        private boolean mGeoDetectionSupported;
        private boolean mTelephonyFallbackSupported;
        private boolean mGeoDetectionRunInBackgroundEnabled;
        private boolean mEnhancedMetricsCollectionEnabled;
        private boolean mAutoDetectionEnabledSetting;
        private boolean mLocationEnabledSetting;
        private boolean mGeoDetectionEnabledSetting;

        /**
         * Creates a new Builder.
         */
        public Builder() {}

        /**
         * Creates a new Builder by copying values from an existing instance.
         */
        public Builder(ConfigurationInternal toCopy) {
            this.mUserId = toCopy.mUserId;
            this.mUserConfigAllowed = toCopy.mUserConfigAllowed;
            this.mTelephonyDetectionSupported = toCopy.mTelephonyDetectionSupported;
            this.mTelephonyFallbackSupported = toCopy.mTelephonyFallbackSupported;
            this.mGeoDetectionSupported = toCopy.mGeoDetectionSupported;
            this.mGeoDetectionRunInBackgroundEnabled = toCopy.mGeoDetectionRunInBackgroundEnabled;
            this.mEnhancedMetricsCollectionEnabled = toCopy.mEnhancedMetricsCollectionEnabled;
            this.mAutoDetectionEnabledSetting = toCopy.mAutoDetectionEnabledSetting;
            this.mLocationEnabledSetting = toCopy.mLocationEnabledSetting;
            this.mGeoDetectionEnabledSetting = toCopy.mGeoDetectionEnabledSetting;
        }

        /**
         * Sets the user ID the configuration is for.
         */
        public Builder setUserId(@UserIdInt int userId) {
            mUserId = userId;
            return this;
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
         * Sets whether time zone detection supports falling back to telephony detection under
         * certain circumstances.
         */
        public Builder setTelephonyFallbackSupported(boolean supported) {
            mTelephonyFallbackSupported = supported;
            return this;
        }

        /**
         * Sets whether location time zone detection should run all the time on supported devices,
         * even when the user has not enabled it explicitly in settings. Enabled for internal
         * testing only.
         */
        public Builder setGeoDetectionRunInBackgroundEnabled(boolean enabled) {
            mGeoDetectionRunInBackgroundEnabled = enabled;
            return this;
        }

        /**
         * Sets the value for enhanced metrics collection.
         */
        public Builder setEnhancedMetricsCollectionEnabled(boolean enabled) {
            mEnhancedMetricsCollectionEnabled = enabled;
            return this;
        }

        /**
         * Sets the value of the automatic time zone detection enabled setting for this device.
         */
        public Builder setAutoDetectionEnabledSetting(boolean enabled) {
            mAutoDetectionEnabledSetting = enabled;
            return this;
        }

        /**
         * Sets the value of the location mode setting for this user.
         */
        public Builder setLocationEnabledSetting(boolean enabled) {
            mLocationEnabledSetting = enabled;
            return this;
        }

        /**
         * Sets the value of the geolocation time zone detection setting for this user.
         */
        public Builder setGeoDetectionEnabledSetting(boolean enabled) {
            mGeoDetectionEnabledSetting = enabled;
            return this;
        }

        /** Returns a new {@link ConfigurationInternal}. */
        @NonNull
        public ConfigurationInternal build() {
            return new ConfigurationInternal(this);
        }
    }
}
