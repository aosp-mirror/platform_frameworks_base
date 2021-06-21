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

import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.time.Capabilities.CapabilityState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneDetector;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Time zone-related capabilities for a user.
 *
 * <p>For configuration settings capabilities, the associated settings value can be found via
 * {@link TimeManager#getTimeZoneCapabilitiesAndConfig()} and may be changed using {@link
 * TimeManager#updateTimeZoneConfiguration(TimeZoneConfiguration)} (if the user's capabilities
 * allow).
 *
 * @hide
 */
@SystemApi
public final class TimeZoneCapabilities implements Parcelable {

    public static final @NonNull Creator<TimeZoneCapabilities> CREATOR =
            new Creator<TimeZoneCapabilities>() {
                public TimeZoneCapabilities createFromParcel(Parcel in) {
                    return TimeZoneCapabilities.createFromParcel(in);
                }

                public TimeZoneCapabilities[] newArray(int size) {
                    return new TimeZoneCapabilities[size];
                }
            };

    /**
     * The user the capabilities are for. This is used for object equality and debugging but there
     * is no accessor.
     */
    @NonNull private final UserHandle mUserHandle;
    private final @CapabilityState int mConfigureAutoDetectionEnabledCapability;
    private final @CapabilityState int mConfigureGeoDetectionEnabledCapability;
    private final @CapabilityState int mSuggestManualTimeZoneCapability;

    private TimeZoneCapabilities(@NonNull Builder builder) {
        this.mUserHandle = Objects.requireNonNull(builder.mUserHandle);
        this.mConfigureAutoDetectionEnabledCapability =
                builder.mConfigureAutoDetectionEnabledCapability;
        this.mConfigureGeoDetectionEnabledCapability =
                builder.mConfigureGeoDetectionEnabledCapability;
        this.mSuggestManualTimeZoneCapability = builder.mSuggestManualTimeZoneCapability;
    }

    @NonNull
    private static TimeZoneCapabilities createFromParcel(Parcel in) {
        UserHandle userHandle = UserHandle.readFromParcel(in);
        return new TimeZoneCapabilities.Builder(userHandle)
                .setConfigureAutoDetectionEnabledCapability(in.readInt())
                .setConfigureGeoDetectionEnabledCapability(in.readInt())
                .setSuggestManualTimeZoneCapability(in.readInt())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        UserHandle.writeToParcel(mUserHandle, dest);
        dest.writeInt(mConfigureAutoDetectionEnabledCapability);
        dest.writeInt(mConfigureGeoDetectionEnabledCapability);
        dest.writeInt(mSuggestManualTimeZoneCapability);
    }

    /**
     * Returns the capability state associated with the user's ability to modify the automatic time
     * zone detection setting. The setting can be updated via {@link
     * TimeManager#updateTimeZoneConfiguration(TimeZoneConfiguration)}.
     */
    @CapabilityState
    public int getConfigureAutoDetectionEnabledCapability() {
        return mConfigureAutoDetectionEnabledCapability;
    }

    /**
     * Returns the capability state associated with the user's ability to modify the geolocation
     * detection setting. The setting can be updated via {@link
     * TimeManager#updateTimeZoneConfiguration(TimeZoneConfiguration)}.
     */
    @CapabilityState
    public int getConfigureGeoDetectionEnabledCapability() {
        return mConfigureGeoDetectionEnabledCapability;
    }

    /**
     * Returns the capability state associated with the user's ability to manually set the time zone
     * on a device via {@link TimeZoneDetector#suggestManualTimeZone(ManualTimeZoneSuggestion)}.
     *
     * <p>The suggestion will be ignored in all cases unless the value is {@link
     * Capabilities#CAPABILITY_POSSESSED}. See also
     * {@link TimeZoneConfiguration#isAutoDetectionEnabled()}.
     *
     * @hide
     */
    @CapabilityState
    public int getSuggestManualTimeZoneCapability() {
        return mSuggestManualTimeZoneCapability;
    }

    /**
     * Tries to create a new {@link TimeZoneConfiguration} from the {@code config} and the set of
     * {@code requestedChanges}, if {@code this} capabilities allow. The new configuration is
     * returned. If the capabilities do not permit one or more of the requested changes then {@code
     * null} is returned.
     *
     * @hide
     */
    @Nullable
    public TimeZoneConfiguration tryApplyConfigChanges(
            @NonNull TimeZoneConfiguration config,
            @NonNull TimeZoneConfiguration requestedChanges) {
        TimeZoneConfiguration.Builder newConfigBuilder = new TimeZoneConfiguration.Builder(config);
        if (requestedChanges.hasIsAutoDetectionEnabled()) {
            if (this.getConfigureAutoDetectionEnabledCapability() < CAPABILITY_NOT_APPLICABLE) {
                return null;
            }
            newConfigBuilder.setAutoDetectionEnabled(requestedChanges.isAutoDetectionEnabled());
        }

        if (requestedChanges.hasIsGeoDetectionEnabled()) {
            if (this.getConfigureGeoDetectionEnabledCapability() < CAPABILITY_NOT_APPLICABLE) {
                return null;
            }
            newConfigBuilder.setGeoDetectionEnabled(requestedChanges.isGeoDetectionEnabled());
        }

        return newConfigBuilder.build();
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
        TimeZoneCapabilities that = (TimeZoneCapabilities) o;
        return mUserHandle.equals(that.mUserHandle)
                && mConfigureAutoDetectionEnabledCapability
                == that.mConfigureAutoDetectionEnabledCapability
                && mConfigureGeoDetectionEnabledCapability
                == that.mConfigureGeoDetectionEnabledCapability
                && mSuggestManualTimeZoneCapability == that.mSuggestManualTimeZoneCapability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserHandle, mConfigureAutoDetectionEnabledCapability,
                mConfigureGeoDetectionEnabledCapability, mSuggestManualTimeZoneCapability);
    }

    @Override
    public String toString() {
        return "TimeZoneDetectorCapabilities{"
                + "mUserHandle=" + mUserHandle
                + ", mConfigureAutoDetectionEnabledCapability="
                + mConfigureAutoDetectionEnabledCapability
                + ", mConfigureGeoDetectionEnabledCapability="
                + mConfigureGeoDetectionEnabledCapability
                + ", mSuggestManualTimeZoneCapability=" + mSuggestManualTimeZoneCapability
                + '}';
    }

    /** @hide */
    public static class Builder {

        @NonNull private UserHandle mUserHandle;
        private @CapabilityState int mConfigureAutoDetectionEnabledCapability;
        private @CapabilityState int mConfigureGeoDetectionEnabledCapability;
        private @CapabilityState int mSuggestManualTimeZoneCapability;

        public Builder(@NonNull UserHandle userHandle) {
            mUserHandle = Objects.requireNonNull(userHandle);
        }

        public Builder(@NonNull TimeZoneCapabilities capabilitiesToCopy) {
            Objects.requireNonNull(capabilitiesToCopy);
            mUserHandle = capabilitiesToCopy.mUserHandle;
            mConfigureAutoDetectionEnabledCapability =
                capabilitiesToCopy.mConfigureAutoDetectionEnabledCapability;
            mConfigureGeoDetectionEnabledCapability =
                capabilitiesToCopy.mConfigureGeoDetectionEnabledCapability;
            mSuggestManualTimeZoneCapability =
                capabilitiesToCopy.mSuggestManualTimeZoneCapability;
        }

        /** Sets the state for the automatic time zone detection enabled config. */
        public Builder setConfigureAutoDetectionEnabledCapability(@CapabilityState int value) {
            this.mConfigureAutoDetectionEnabledCapability = value;
            return this;
        }

        /** Sets the state for the geolocation time zone detection enabled config. */
        public Builder setConfigureGeoDetectionEnabledCapability(@CapabilityState int value) {
            this.mConfigureGeoDetectionEnabledCapability = value;
            return this;
        }

        /** Sets the state for the suggestManualTimeZone action. */
        public Builder setSuggestManualTimeZoneCapability(@CapabilityState int value) {
            this.mSuggestManualTimeZoneCapability = value;
            return this;
        }

        /** Returns the {@link TimeZoneCapabilities}. */
        @NonNull
        public TimeZoneCapabilities build() {
            verifyCapabilitySet(mConfigureAutoDetectionEnabledCapability,
                    "configureAutoDetectionEnabledCapability");
            verifyCapabilitySet(mConfigureGeoDetectionEnabledCapability,
                    "configureGeoDetectionEnabledCapability");
            verifyCapabilitySet(mSuggestManualTimeZoneCapability,
                    "suggestManualTimeZoneCapability");
            return new TimeZoneCapabilities(this);
        }

        private void verifyCapabilitySet(int value, String name) {
            if (value == 0) {
                throw new IllegalStateException(name + " not set");
            }
        }
    }
}
