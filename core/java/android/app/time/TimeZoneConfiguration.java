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

package android.app.time;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
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
 * TimeManager#updateTimeZoneConfiguration(TimeZoneConfiguration)} and those settings will not be
 * changed. Not all configuration settings can be modified by all users: see {@link
 * TimeManager#getTimeZoneCapabilitiesAndConfig()} and {@link TimeZoneCapabilities} for details.
 *
 * @hide
 */
@SystemApi
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

    /**
     * All configuration properties
     *
     * @hide
     */
    @StringDef({ SETTING_AUTO_DETECTION_ENABLED, SETTING_GEO_DETECTION_ENABLED })
    @Retention(RetentionPolicy.SOURCE)
    @interface Setting {}

    /** See {@link TimeZoneConfiguration#isAutoDetectionEnabled()} for details. */
    @Setting
    private static final String SETTING_AUTO_DETECTION_ENABLED = "autoDetectionEnabled";

    /** See {@link TimeZoneConfiguration#isGeoDetectionEnabled()} for details. */
    @Setting
    private static final String SETTING_GEO_DETECTION_ENABLED = "geoDetectionEnabled";

    @NonNull private final Bundle mBundle;

    private TimeZoneConfiguration(Builder builder) {
        this.mBundle = Objects.requireNonNull(builder.mBundle);
    }

    private static TimeZoneConfiguration createFromParcel(Parcel in) {
        return new TimeZoneConfiguration.Builder()
                .setPropertyBundleInternal(in.readBundle())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mBundle);
    }

    /**
     * Returns {@code true} if all known settings are present.
     *
     * @hide
     */
    public boolean isComplete() {
        return hasIsAutoDetectionEnabled()
                && hasIsGeoDetectionEnabled();
    }

    /**
     * Returns the value of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting. This
     * controls whether a device will attempt to determine the time zone automatically using
     * contextual information if the device supports auto detection.
     *
     * <p>See {@link TimeZoneCapabilities#getConfigureAutoDetectionEnabledCapability()} for how to
     * tell if the setting is meaningful for the current user at this time.
     *
     * @throws IllegalStateException if the setting is not present
     */
    public boolean isAutoDetectionEnabled() {
        enforceSettingPresent(SETTING_AUTO_DETECTION_ENABLED);
        return mBundle.getBoolean(SETTING_AUTO_DETECTION_ENABLED);
    }

    /**
     * Returns {@code true} if the {@link #isAutoDetectionEnabled()} setting is present.
     *
     * @hide
     */
    public boolean hasIsAutoDetectionEnabled() {
        return mBundle.containsKey(SETTING_AUTO_DETECTION_ENABLED);
    }

    /**
     * Returns the value of the {@link #SETTING_GEO_DETECTION_ENABLED} setting. This
     * controls whether the device can use geolocation to determine time zone. This value may only
     * be used by Android under some circumstances. For example, it is not used when
     * {@link #isGeoDetectionEnabled()} is {@code false}.
     *
     * <p>See {@link TimeZoneCapabilities#getConfigureGeoDetectionEnabledCapability()} for how to
     * tell if the setting is meaningful for the current user at this time.
     *
     * @throws IllegalStateException if the setting is not present
     */
    public boolean isGeoDetectionEnabled() {
        enforceSettingPresent(SETTING_GEO_DETECTION_ENABLED);
        return mBundle.getBoolean(SETTING_GEO_DETECTION_ENABLED);
    }

    /**
     * Returns {@code true} if the {@link #isGeoDetectionEnabled()} setting is present.
     *
     * @hide
     */
    public boolean hasIsGeoDetectionEnabled() {
        return mBundle.containsKey(SETTING_GEO_DETECTION_ENABLED);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneConfiguration that = (TimeZoneConfiguration) o;
        return mBundle.kindofEquals(that.mBundle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBundle);
    }

    @Override
    public String toString() {
        return "TimeZoneConfiguration{"
                + "mBundle=" + mBundle
                + '}';
    }

    private void enforceSettingPresent(@Setting String setting) {
        if (!mBundle.containsKey(setting)) {
            throw new IllegalStateException(setting + " is not set");
        }
    }

    /**
     * A builder for {@link TimeZoneConfiguration} objects.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private final Bundle mBundle = new Bundle();

        /**
         * Creates a new Builder with no settings held.
         */
        public Builder() {
        }

        /**
         * Creates a new Builder by copying the settings from an existing instance.
         */
        public Builder(@NonNull TimeZoneConfiguration toCopy) {
            mergeProperties(toCopy);
        }

        /**
         * Merges {@code other} settings into this instances, replacing existing values in this
         * where the settings appear in both.
         *
         * @hide
         */
        @NonNull
        public Builder mergeProperties(@NonNull TimeZoneConfiguration other) {
            this.mBundle.putAll(other.mBundle);
            return this;
        }

        @NonNull
        Builder setPropertyBundleInternal(@NonNull Bundle bundle) {
            this.mBundle.putAll(bundle);
            return this;
        }

        /**
         * Sets the state of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting.
         */
        @NonNull
        public Builder setAutoDetectionEnabled(boolean enabled) {
            this.mBundle.putBoolean(SETTING_AUTO_DETECTION_ENABLED, enabled);
            return this;
        }

        /**
         * Sets the state of the {@link #SETTING_GEO_DETECTION_ENABLED} setting.
         */
        @NonNull
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

