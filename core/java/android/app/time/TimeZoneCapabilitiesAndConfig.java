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
import java.util.concurrent.Executor;

/**
 * An object containing a user's {@link TimeZoneCapabilities} and {@link TimeZoneConfiguration}.
 *
 * @hide
 */
@SystemApi
public final class TimeZoneCapabilitiesAndConfig implements Parcelable {

    public static final @NonNull Creator<TimeZoneCapabilitiesAndConfig> CREATOR = new Creator<>() {
        public TimeZoneCapabilitiesAndConfig createFromParcel(Parcel in) {
            return TimeZoneCapabilitiesAndConfig.createFromParcel(in);
        }

        public TimeZoneCapabilitiesAndConfig[] newArray(int size) {
            return new TimeZoneCapabilitiesAndConfig[size];
        }
    };

    /**
     * The time zone detector status.
     *
     * Implementation note for future platform engineers: This field is only needed by SettingsUI
     * initially and so it has not been added to the SDK API. {@link TimeZoneDetectorStatus}
     * contains details about the internals of the time zone detector so thought should be given to
     * abstraction / exposing a lightweight version if something unbundled needs access to detector
     * details. Also, that could be good time to add separate APIs for bundled components, or add
     * new APIs that return something more extensible and generic like a Bundle or a less
     * constraining name. See also {@link
     * TimeManager#addTimeZoneDetectorListener(Executor, TimeManager.TimeZoneDetectorListener)},
     * which notified of changes to any fields in this class, including the detector status.
     */
    @NonNull private final TimeZoneDetectorStatus mDetectorStatus;
    @NonNull private final TimeZoneCapabilities mCapabilities;
    @NonNull private final TimeZoneConfiguration mConfiguration;

    /**
     * Creates a new instance.
     *
     * @hide
     */
    public TimeZoneCapabilitiesAndConfig(
            @NonNull TimeZoneDetectorStatus detectorStatus,
            @NonNull TimeZoneCapabilities capabilities,
            @NonNull TimeZoneConfiguration configuration) {
        mDetectorStatus = Objects.requireNonNull(detectorStatus);
        mCapabilities = Objects.requireNonNull(capabilities);
        mConfiguration = Objects.requireNonNull(configuration);
    }

    @NonNull
    private static TimeZoneCapabilitiesAndConfig createFromParcel(Parcel in) {
        TimeZoneDetectorStatus detectorStatus =
                in.readParcelable(null, TimeZoneDetectorStatus.class);
        TimeZoneCapabilities capabilities = in.readParcelable(null, TimeZoneCapabilities.class);
        TimeZoneConfiguration configuration = in.readParcelable(null, TimeZoneConfiguration.class);
        return new TimeZoneCapabilitiesAndConfig(detectorStatus, capabilities, configuration);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mDetectorStatus, flags);
        dest.writeParcelable(mCapabilities, flags);
        dest.writeParcelable(mConfiguration, flags);
    }

    /**
     * Returns the time zone detector's status.
     *
     * @hide
     */
    @NonNull
    public TimeZoneDetectorStatus getDetectorStatus() {
        return mDetectorStatus;
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
        return mDetectorStatus.equals(that.mDetectorStatus)
                && mCapabilities.equals(that.mCapabilities)
                && mConfiguration.equals(that.mConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCapabilities, mConfiguration);
    }

    @Override
    public String toString() {
        return "TimeZoneCapabilitiesAndConfig{"
                + "mDetectorStatus=" + mDetectorStatus
                + ", mCapabilities=" + mCapabilities
                + ", mConfiguration=" + mConfiguration
                + '}';
    }
}
