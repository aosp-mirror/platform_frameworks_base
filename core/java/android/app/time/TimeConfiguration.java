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

package android.app.time;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * User visible settings that control the behavior of the time detector / manual time entry.
 *
 * <p>When reading the configuration, values for all settings will be provided. In some cases, such
 * as when the device behavior relies on optional hardware / OEM configuration, or the value of
 * several settings, the device behavior may not be directly affected by the setting value.
 *
 * <p>Settings can be left absent when updating configuration via {@link
 * TimeManager#updateTimeConfiguration(TimeConfiguration)} and those settings will not be
 * changed. Not all configuration settings can be modified by all users: see {@link
 * TimeManager#getTimeCapabilitiesAndConfig()} and {@link TimeCapabilities} for details.
 *
 * @hide
 */
@SystemApi
public final class TimeConfiguration implements Parcelable {

    public static final @NonNull Creator<TimeConfiguration> CREATOR =
            new Creator<TimeConfiguration>() {
                @Override
                public TimeConfiguration createFromParcel(Parcel source) {
                    return TimeConfiguration.readFromParcel(source);
                }

                @Override
                public TimeConfiguration[] newArray(int size) {
                    return new TimeConfiguration[size];
                }
            };

    /**
     * All configuration properties
     *
     * @hide
     */
    @StringDef(SETTING_AUTO_DETECTION_ENABLED)
    @Retention(RetentionPolicy.SOURCE)
    @interface Setting {}

    /** See {@link TimeConfiguration#isAutoDetectionEnabled()} for details. */
    @Setting
    private static final String SETTING_AUTO_DETECTION_ENABLED = "autoDetectionEnabled";

    @NonNull
    private final Bundle mBundle;

    private TimeConfiguration(Builder builder) {
        this.mBundle = builder.mBundle;
    }

    private static TimeConfiguration readFromParcel(Parcel in) {
        return new TimeConfiguration.Builder()
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
        return hasIsAutoDetectionEnabled();
    }

    /**
     * Returns the value of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting. This
     * controls whether a device will attempt to determine the time automatically using
     * contextual information if the device supports auto detection.
     *
     * <p>See {@link TimeCapabilities#getConfigureAutoDetectionEnabledCapability()} for how to
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeConfiguration that = (TimeConfiguration) o;
        return mBundle.kindofEquals(that.mBundle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBundle);
    }

    @Override
    public String toString() {
        return "TimeConfiguration{"
                + "mBundle=" + mBundle
                + '}';
    }

    private void enforceSettingPresent(@TimeZoneConfiguration.Setting String setting) {
        if (!mBundle.containsKey(setting)) {
            throw new IllegalStateException(setting + " is not set");
        }
    }

    /**
     * A builder for {@link TimeConfiguration} objects.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private final Bundle mBundle = new Bundle();

        /**
         * Creates a new Builder with no settings held.
         */
        public Builder() {}

        /**
         * Creates a new Builder by copying the settings from an existing instance.
         */
        public Builder(@NonNull TimeConfiguration toCopy) {
            mergeProperties(toCopy);
        }

        /**
         * Merges {@code other} settings into this instances, replacing existing values in this
         * where the settings appear in both.
         *
         * @hide
         */
        @NonNull
        public Builder mergeProperties(@NonNull TimeConfiguration toCopy) {
            mBundle.putAll(toCopy.mBundle);
            return this;
        }

        @NonNull
        Builder setPropertyBundleInternal(@NonNull Bundle bundle) {
            this.mBundle.putAll(bundle);
            return this;
        }

        /** Sets whether auto detection is enabled or not. */
        @NonNull
        public Builder setAutoDetectionEnabled(boolean enabled) {
            mBundle.putBoolean(SETTING_AUTO_DETECTION_ENABLED, enabled);
            return this;
        }

        /** Returns the {@link TimeConfiguration}. */
        @NonNull
        public TimeConfiguration build() {
            return new TimeConfiguration(this);
        }
    }
}
