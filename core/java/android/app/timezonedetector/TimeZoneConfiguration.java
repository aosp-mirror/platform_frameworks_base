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

package android.app.timezonedetector;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.UserIdInt;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * User visible settings that control the behavior of the time zone detector / manual time zone
 * entry.
 *
 * <p>When reading the configuration, values for all settings will be provided. In some cases, such
 * as when the device behavior relies on optional hardware / OEM configuration, or the value of
 * several settings, the device behavior may not be directly affected by the setting value.
 *
 * <p>Settings can be left absent when updating configuration via {@link
 * TimeZoneDetector#updateConfiguration(TimeZoneConfiguration)} and those settings will not be
 * changed. Not all configuration settings can be modified by all users: see {@link
 * TimeZoneDetector#getCapabilities()} and {@link TimeZoneCapabilities} for details.
 *
 * <p>See {@link #hasSetting(String)} with {@code PROPERTY_} constants for testing for the presence
 * of individual settings.
 *
 * @hide
 */
public final class TimeZoneConfiguration implements Parcelable {

    public static final @NonNull Creator<TimeZoneConfiguration> CREATOR =
            new Creator<TimeZoneConfiguration>() {
                public TimeZoneConfiguration createFromParcel(Parcel in) {
                    return TimeZoneConfiguration.createFromParcel(in);
                }

                public TimeZoneConfiguration[] newArray(int size) {
                    return new TimeZoneConfiguration[size];
                }
            };

    /** All configuration properties */
    @StringDef({ SETTING_AUTO_DETECTION_ENABLED, SETTING_GEO_DETECTION_ENABLED })
    @Retention(RetentionPolicy.SOURCE)
    @interface Setting {}

    /** See {@link TimeZoneConfiguration#isAutoDetectionEnabled()} for details. */
    @Setting
    public static final String SETTING_AUTO_DETECTION_ENABLED = "autoDetectionEnabled";

    /** See {@link TimeZoneConfiguration#isGeoDetectionEnabled()} for details. */
    @Setting
    public static final String SETTING_GEO_DETECTION_ENABLED = "geoDetectionEnabled";

    private final @UserIdInt int mUserId;
    @NonNull private final Bundle mBundle;

    private TimeZoneConfiguration(Builder builder) {
        this.mUserId = builder.mUserId;
        this.mBundle = Objects.requireNonNull(builder.mBundle);
    }

    private static TimeZoneConfiguration createFromParcel(Parcel in) {
        return new TimeZoneConfiguration.Builder(in.readInt())
                .setPropertyBundleInternal(in.readBundle())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeBundle(mBundle);
    }

    /** Returns the ID of the user this configuration is associated with. */
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /** Returns {@code true} if all known settings are present. */
    public boolean isComplete() {
        return hasSetting(SETTING_AUTO_DETECTION_ENABLED)
                && hasSetting(SETTING_GEO_DETECTION_ENABLED);
    }

    /** Returns true if the specified setting is set. */
    public boolean hasSetting(@Setting String setting) {
        return mBundle.containsKey(setting);
    }

    /**
     * Returns the value of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting. This
     * controls whether a device will attempt to determine the time zone automatically using
     * contextual information if the device supports auto detection.
     *
     * <p>This setting is global and can be updated by some users.
     *
     * @throws IllegalStateException if the setting has not been set
     */
    public boolean isAutoDetectionEnabled() {
        enforceSettingPresent(SETTING_AUTO_DETECTION_ENABLED);
        return mBundle.getBoolean(SETTING_AUTO_DETECTION_ENABLED);
    }

    /**
     * Returns the value of the {@link #SETTING_GEO_DETECTION_ENABLED} setting. This
     * controls whether a device can use geolocation to determine time zone. Only used when
     * {@link #isAutoDetectionEnabled()} is {@code true} and when the user has allowed their
     * location to be used.
     *
     * <p>This setting is user-scoped and can be updated by some users.
     * See {@link TimeZoneCapabilities#getConfigureGeoDetectionEnabled()}.
     *
     * @throws IllegalStateException if the setting has not been set
     */
    public boolean isGeoDetectionEnabled() {
        enforceSettingPresent(SETTING_GEO_DETECTION_ENABLED);
        return mBundle.getBoolean(SETTING_GEO_DETECTION_ENABLED);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneConfiguration that = (TimeZoneConfiguration) o;
        return mUserId == that.mUserId
                && mBundle.kindofEquals(that.mBundle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId, mBundle);
    }

    @Override
    public String toString() {
        return "TimeZoneConfiguration{"
                + "mUserId=" + mUserId
                + ", mBundle=" + mBundle
                + '}';
    }

    private void enforceSettingPresent(@Setting String setting) {
        if (!mBundle.containsKey(setting)) {
            throw new IllegalStateException(setting + " is not set");
        }
    }

    /** @hide */
    public static class Builder {

        private final @UserIdInt int mUserId;
        private final Bundle mBundle = new Bundle();

        /**
         * Creates a new Builder for a userId with no settings held.
         */
        public Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Creates a new Builder by copying the user ID and settings from an existing instance.
         */
        public Builder(TimeZoneConfiguration toCopy) {
            this.mUserId = toCopy.mUserId;
            mergeProperties(toCopy);
        }

        /**
         * Merges {@code other} settings into this instances, replacing existing values in this
         * where the settings appear in both.
         */
        public Builder mergeProperties(TimeZoneConfiguration other) {
            if (mUserId != other.mUserId) {
                throw new IllegalArgumentException(
                        "Cannot merge configurations for different user IDs."
                                + " this.mUserId=" + this.mUserId
                                + ", other.mUserId=" + other.mUserId);
            }
            this.mBundle.putAll(other.mBundle);
            return this;
        }

        Builder setPropertyBundleInternal(Bundle bundle) {
            this.mBundle.putAll(bundle);
            return this;
        }

        /**
         * Sets the state of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting.
         */
        public Builder setAutoDetectionEnabled(boolean enabled) {
            this.mBundle.putBoolean(SETTING_AUTO_DETECTION_ENABLED, enabled);
            return this;
        }

        /**
         * Sets the state of the {@link #SETTING_GEO_DETECTION_ENABLED} setting.
         */
        public Builder setGeoDetectionEnabled(boolean enabled) {
            this.mBundle.putBoolean(SETTING_GEO_DETECTION_ENABLED, enabled);
            return this;
        }

        /** Returns the {@link TimeZoneConfiguration}. */
        @NonNull
        public TimeZoneConfiguration build() {
            return new TimeZoneConfiguration(this);
        }
    }
}

