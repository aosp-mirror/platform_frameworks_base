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

    public static final @NonNull Creator<TimeZoneCapabilities> CREATOR = new Creator<>() {
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

    /**
     * The values of the user's "Use location" value, AKA the Master Location Switch.
     *
     * <p>This is only exposed for SettingsUI and so is not part of the SDK API.
     *
     * <p>This is not treated as a CapabilityState as it's a boolean value that all user's have.
     */
    private final boolean mUseLocationEnabled;

    private final @CapabilityState int mConfigureGeoDetectionEnabledCapability;
    private final @CapabilityState int mSetManualTimeZoneCapability;

    private TimeZoneCapabilities(@NonNull Builder builder) {
        this.mUserHandle = Objects.requireNonNull(builder.mUserHandle);
        this.mConfigureAutoDetectionEnabledCapability =
                builder.mConfigureAutoDetectionEnabledCapability;
        this.mUseLocationEnabled = builder.mUseLocationEnabled;
        this.mConfigureGeoDetectionEnabledCapability =
                builder.mConfigureGeoDetectionEnabledCapability;
        this.mSetManualTimeZoneCapability = builder.mSetManualTimeZoneCapability;
    }

    @NonNull
    private static TimeZoneCapabilities createFromParcel(@NonNull Parcel in) {
        UserHandle userHandle = UserHandle.readFromParcel(in);
        return new TimeZoneCapabilities.Builder(userHandle)
                .setConfigureAutoDetectionEnabledCapability(in.readInt())
                .setUseLocationEnabled(in.readBoolean())
                .setConfigureGeoDetectionEnabledCapability(in.readInt())
                .setSetManualTimeZoneCapability(in.readInt())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        UserHandle.writeToParcel(mUserHandle, dest);
        dest.writeInt(mConfigureAutoDetectionEnabledCapability);
        dest.writeBoolean(mUseLocationEnabled);
        dest.writeInt(mConfigureGeoDetectionEnabledCapability);
        dest.writeInt(mSetManualTimeZoneCapability);
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
     * Returns {@code true} if the device's location can be used by the Android system, and
     * therefore the platform components running on behalf of the user. At the time of writing, the
     * user can change this via the "Use location" setting on the Location settings screen.
     *
     * Not part of the SDK API because it is intended for use by SettingsUI, which can display
     * text about needing it to be on for location-based time zone detection.
     * @hide
     *
     */
    public boolean isUseLocationEnabled() {
        return mUseLocationEnabled;
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
     * on a device.
     *
     * <p>The time zone will be ignored in all cases unless the value is {@link
     * Capabilities#CAPABILITY_POSSESSED}. See also
     * {@link TimeZoneConfiguration#isAutoDetectionEnabled()}.
     */
    @CapabilityState
    public int getSetManualTimeZoneCapability() {
        return mSetManualTimeZoneCapability;
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
                && mUseLocationEnabled == that.mUseLocationEnabled
                && mConfigureGeoDetectionEnabledCapability
                == that.mConfigureGeoDetectionEnabledCapability
                && mSetManualTimeZoneCapability == that.mSetManualTimeZoneCapability;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserHandle, mConfigureAutoDetectionEnabledCapability,
                mConfigureGeoDetectionEnabledCapability, mSetManualTimeZoneCapability);
    }

    @Override
    public String toString() {
        return "TimeZoneDetectorCapabilities{"
                + "mUserHandle=" + mUserHandle
                + ", mConfigureAutoDetectionEnabledCapability="
                + mConfigureAutoDetectionEnabledCapability
                + ", mUseLocationEnabled=" + mUseLocationEnabled
                + ", mConfigureGeoDetectionEnabledCapability="
                + mConfigureGeoDetectionEnabledCapability
                + ", mSetManualTimeZoneCapability=" + mSetManualTimeZoneCapability
                + '}';
    }

    /**
     * A builder of {@link TimeZoneCapabilities} objects.
     *
     * @hide
     */
    public static class Builder {

        @NonNull private UserHandle mUserHandle;
        private @CapabilityState int mConfigureAutoDetectionEnabledCapability;
        private Boolean mUseLocationEnabled;
        private @CapabilityState int mConfigureGeoDetectionEnabledCapability;
        private @CapabilityState int mSetManualTimeZoneCapability;

        public Builder(@NonNull UserHandle userHandle) {
            mUserHandle = Objects.requireNonNull(userHandle);
        }

        public Builder(@NonNull TimeZoneCapabilities capabilitiesToCopy) {
            Objects.requireNonNull(capabilitiesToCopy);
            mUserHandle = capabilitiesToCopy.mUserHandle;
            mConfigureAutoDetectionEnabledCapability =
                capabilitiesToCopy.mConfigureAutoDetectionEnabledCapability;
            mUseLocationEnabled = capabilitiesToCopy.mUseLocationEnabled;
            mConfigureGeoDetectionEnabledCapability =
                capabilitiesToCopy.mConfigureGeoDetectionEnabledCapability;
            mSetManualTimeZoneCapability =
                capabilitiesToCopy.mSetManualTimeZoneCapability;
        }

        /** Sets the value for the "configure automatic time zone detection enabled" capability. */
        public Builder setConfigureAutoDetectionEnabledCapability(@CapabilityState int value) {
            this.mConfigureAutoDetectionEnabledCapability = value;
            return this;
        }

        /** Sets the values for "use location". See {@link #isUseLocationEnabled()}. */
        public Builder setUseLocationEnabled(boolean useLocation) {
            mUseLocationEnabled = useLocation;
            return this;
        }

        /**
         * Sets the value for the "configure geolocation time zone detection enabled" capability.
         */
        public Builder setConfigureGeoDetectionEnabledCapability(@CapabilityState int value) {
            this.mConfigureGeoDetectionEnabledCapability = value;
            return this;
        }

        /** Sets the value for the "set manual time zone" capability. */
        public Builder setSetManualTimeZoneCapability(@CapabilityState int value) {
            this.mSetManualTimeZoneCapability = value;
            return this;
        }

        /** Returns the {@link TimeZoneCapabilities}. */
        @NonNull
        public TimeZoneCapabilities build() {
            verifyCapabilitySet(mConfigureAutoDetectionEnabledCapability,
                    "configureAutoDetectionEnabledCapability");
            Objects.requireNonNull(mUseLocationEnabled, "useLocationEnabled");
            verifyCapabilitySet(mConfigureGeoDetectionEnabledCapability,
                    "configureGeoDetectionEnabledCapability");
            verifyCapabilitySet(mSetManualTimeZoneCapability,
                    "mSetManualTimeZoneCapability");
            return new TimeZoneCapabilities(this);
        }

        private void verifyCapabilitySet(int value, String name) {
            if (value == 0) {
                throw new IllegalStateException(name + " not set");
            }
        }
    }
}
