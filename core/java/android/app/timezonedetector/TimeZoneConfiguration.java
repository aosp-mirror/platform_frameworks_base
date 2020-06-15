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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Configuration that controls the behavior of the time zone detector associated with a specific
 * user.
 *
 * <p>Configuration consists of a set of known properties. When reading configuration via
 * {@link TimeZoneDetector#getConfiguration()} values for all known properties will be provided. In
 * some cases, such as when the configuration relies on optional hardware, the values may be
 * meaningless / defaulted to safe values.
 *
 * <p>Configuration properties can be left absent when updating configuration via {@link
 * TimeZoneDetector#updateConfiguration(TimeZoneConfiguration)} and those values will not be
 * changed. Not all configuration properties can be modified by all users. See {@link
 * TimeZoneDetector#getCapabilities()} and {@link TimeZoneCapabilities}.
 *
 * <p>See {@link #isComplete()} to tell if all known properties are present, and {@link
 * #hasProperty(String)} with {@code PROPERTY_} constants for testing individual properties.
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
    @StringDef(PROPERTY_AUTO_DETECTION_ENABLED)
    @Retention(RetentionPolicy.SOURCE)
    @interface Property {}

    /** See {@link TimeZoneConfiguration#isAutoDetectionEnabled()} for details. */
    @Property
    public static final String PROPERTY_AUTO_DETECTION_ENABLED = "autoDetectionEnabled";

    private final Bundle mBundle;

    private TimeZoneConfiguration(Builder builder) {
        this.mBundle = builder.mBundle;
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

    /** Returns {@code true} if all known properties are set. */
    public boolean isComplete() {
        return hasProperty(PROPERTY_AUTO_DETECTION_ENABLED);
    }

    /** Returns true if the specified property is set. */
    public boolean hasProperty(@Property String property) {
        return mBundle.containsKey(property);
    }

    /**
     * Returns the value of the {@link #PROPERTY_AUTO_DETECTION_ENABLED} property. This
     * controls whether a device will attempt to determine the time zone automatically using
     * contextual information.
     *
     * @throws IllegalStateException if the field has not been set
     */
    public boolean isAutoDetectionEnabled() {
        if (!mBundle.containsKey(PROPERTY_AUTO_DETECTION_ENABLED)) {
            throw new IllegalStateException(PROPERTY_AUTO_DETECTION_ENABLED + " is not set");
        }
        return mBundle.getBoolean(PROPERTY_AUTO_DETECTION_ENABLED);
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
        return mBundle.kindofEquals(that.mBundle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBundle);
    }

    @Override
    public String toString() {
        return "TimeZoneDetectorConfiguration{"
                + "mBundle=" + mBundle
                + '}';
    }

    /** @hide */
    public static class Builder {

        private Bundle mBundle = new Bundle();

        /**
         * Creates a new Builder with no properties set.
         */
        public Builder() {}

        /**
         * Creates a new Builder by copying properties from an existing instance.
         */
        public Builder(TimeZoneConfiguration toCopy) {
            mergeProperties(toCopy);
        }

        /**
         * Merges {@code other} properties into this instances, replacing existing values in this
         * where the properties appear in both.
         */
        public Builder mergeProperties(TimeZoneConfiguration other) {
            this.mBundle.putAll(other.mBundle);
            return this;
        }

        Builder setPropertyBundleInternal(Bundle bundle) {
            this.mBundle.putAll(bundle);
            return this;
        }

        /** Sets the desired state of the automatic time zone detection property. */
        public Builder setAutoDetectionEnabled(boolean enabled) {
            this.mBundle.putBoolean(PROPERTY_AUTO_DETECTION_ENABLED, enabled);
            return this;
        }

        /** Returns the {@link TimeZoneConfiguration}. */
        @NonNull
        public TimeZoneConfiguration build() {
            return new TimeZoneConfiguration(this);
        }
    }
}

