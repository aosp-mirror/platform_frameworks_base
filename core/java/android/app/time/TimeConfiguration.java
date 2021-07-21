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
 * @hide
 */
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

    @StringDef(SETTING_AUTO_DETECTION_ENABLED)
    @Retention(RetentionPolicy.SOURCE)
    @interface Setting {}

    @Setting
    private static final String SETTING_AUTO_DETECTION_ENABLED = "autoDetectionEnabled";

    @NonNull
    private final Bundle mBundle;

    private TimeConfiguration(Builder builder) {
        this.mBundle = builder.mBundle;
    }

    /**
     * Returns the value of the {@link #SETTING_AUTO_DETECTION_ENABLED} setting. This
     * controls whether a device will attempt to determine the time automatically using
     * contextual information if the device supports auto detection.
     */
    public boolean isAutoDetectionEnabled() {
        return mBundle.getBoolean(SETTING_AUTO_DETECTION_ENABLED);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mBundle);
    }

    private static TimeConfiguration readFromParcel(Parcel in) {
        return new TimeConfiguration.Builder()
                .merge(in.readBundle())
                .build();
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

    /**
     * A builder for {@link TimeConfiguration} objects.
     *
     * @hide
     */
    public static final class Builder {
        private final Bundle mBundle = new Bundle();

        public Builder() {}

        public Builder(@NonNull TimeConfiguration configuration) {
            mBundle.putAll(configuration.mBundle);
        }

        /** Sets whether auto detection is enabled or not. */
        @NonNull
        public Builder setAutoDetectionEnabled(boolean enabled) {
            mBundle.putBoolean(SETTING_AUTO_DETECTION_ENABLED, enabled);
            return this;
        }

        Builder merge(@NonNull Bundle bundle) {
            mBundle.putAll(bundle);
            return this;
        }

        /** Returns {@link TimeConfiguration} object. */
        @NonNull
        public TimeConfiguration build() {
            return new TimeConfiguration(this);
        }
    }
}
