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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A pair containing a user's {@link TimeZoneCapabilities} and {@link TimeZoneConfiguration}.
 *
 * @hide
 */
@SystemApi
public final class TimeZoneCapabilitiesAndConfig implements Parcelable {

    public static final @NonNull Creator<TimeZoneCapabilitiesAndConfig> CREATOR =
            new Creator<TimeZoneCapabilitiesAndConfig>() {
                public TimeZoneCapabilitiesAndConfig createFromParcel(Parcel in) {
                    return TimeZoneCapabilitiesAndConfig.createFromParcel(in);
                }

                public TimeZoneCapabilitiesAndConfig[] newArray(int size) {
                    return new TimeZoneCapabilitiesAndConfig[size];
                }
            };


    @NonNull private final TimeZoneCapabilities mCapabilities;
    @NonNull private final TimeZoneConfiguration mConfiguration;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public TimeZoneCapabilitiesAndConfig(
            @NonNull TimeZoneCapabilities capabilities,
            @NonNull TimeZoneConfiguration configuration) {
        this.mCapabilities = Objects.requireNonNull(capabilities);
        this.mConfiguration = Objects.requireNonNull(configuration);
    }

    @NonNull
    private static TimeZoneCapabilitiesAndConfig createFromParcel(Parcel in) {
        TimeZoneCapabilities capabilities = in.readParcelable(null, android.app.time.TimeZoneCapabilities.class);
        TimeZoneConfiguration configuration = in.readParcelable(null, android.app.time.TimeZoneConfiguration.class);
        return new TimeZoneCapabilitiesAndConfig(capabilities, configuration);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mCapabilities, flags);
        dest.writeParcelable(mConfiguration, flags);
    }

    /**
     * Returns the user's time zone behavior capabilities.
     */
    @NonNull
    public TimeZoneCapabilities getCapabilities() {
        return mCapabilities;
    }

    /**
     * Returns the user's time zone behavior configuration.
     */
    @NonNull
    public TimeZoneConfiguration getConfiguration() {
        return mConfiguration;
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
        TimeZoneCapabilitiesAndConfig that = (TimeZoneCapabilitiesAndConfig) o;
        return mCapabilities.equals(that.mCapabilities)
                && mConfiguration.equals(that.mConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCapabilities, mConfiguration);
    }

    @Override
    public String toString() {
        return "TimeZoneCapabilitiesAndConfig{"
                + "mCapabilities=" + mCapabilities
                + ", mConfiguration=" + mConfiguration
                + '}';
    }
}
