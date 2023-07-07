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
 * Time-relate capabilities for a user.
 *
 * <p>For configuration settings capabilities, the associated settings value can be found via
 * {@link TimeManager#getTimeCapabilitiesAndConfig()} and may be changed using {@link
 * TimeManager#updateTimeConfiguration(TimeConfiguration)} (if the user's capabilities
 * allow).
 *
 * @hide
 */
@SystemApi
public final class TimeCapabilities implements Parcelable {

    public static final @NonNull Creator<TimeCapabilities> CREATOR = new Creator<>() {
        public TimeCapabilities createFromParcel(Parcel in) {
            return TimeCapabilities.createFromParcel(in);
        }

        public TimeCapabilities[] newArray(int size) {
            return new TimeCapabilities[size];
        }
    };


    /**
     * The user the capabilities are for. This is used for object equality and debugging but there
     * is no accessor.
     */
    @NonNull
    private final UserHandle mUserHandle;
    private final @CapabilityState int mConfigureAutoDetectionEnabledCapability;
    private final @CapabilityState int mSetManualTimeCapability;

    private TimeCapabilities(@NonNull Builder builder) {
        this.mUserHandle = Objects.requireNonNull(builder.mUserHandle);
        this.mConfigureAutoDetectionEnabledCapability =
                builder.mConfigureAutoDetectionEnabledCapability;
        this.mSetManualTimeCapability = builder.mSetManualTimeCapability;
    }

    @NonNull
    private static TimeCapabilities createFromParcel(@NonNull Parcel in) {
        UserHandle userHandle = UserHandle.readFromParcel(in);
        return new TimeCapabilities.Builder(userHandle)
                .setConfigureAutoDetectionEnabledCapability(in.readInt())
                .setSetManualTimeCapability(in.readInt())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        UserHandle.writeToParcel(mUserHandle, dest);
        dest.writeInt(mConfigureAutoDetectionEnabledCapability);
        dest.writeInt(mSetManualTimeCapability);
    }

    /**
     * Returns the capability state associated with the user's ability to modify the automatic time
     * detection setting. The setting can be updated via {@link
     * TimeManager#updateTimeConfiguration(TimeConfiguration)}.
     */
    @CapabilityState
    public int getConfigureAutoDetectionEnabledCapability() {
        return mConfigureAutoDetectionEnabledCapability;
    }

    /**
     * Returns the capability state associated with the user's ability to manually set time on a
     * device. The setting can be updated via {@link
     * TimeManager#updateTimeConfiguration(TimeConfiguration)}.
     */
    @CapabilityState
    public int getSetManualTimeCapability() {
        return mSetManualTimeCapability;
    }

    /**
     * Tries to create a new {@link TimeConfiguration} from the {@code config} and the set of
     * {@code requestedChanges}, if {@code this} capabilities allow. The new configuration is
     * returned. If the capabilities do not permit one or more of the requested changes then {@code
     * null} is returned.
     *
     * @hide
     */
    @Nullable
    public TimeConfiguration tryApplyConfigChanges(
            @NonNull TimeConfiguration config,
            @NonNull TimeConfiguration requestedChanges) {
        TimeConfiguration.Builder newConfigBuilder = new TimeConfiguration.Builder(config);
        if (requestedChanges.hasIsAutoDetectionEnabled()) {
            if (this.getConfigureAutoDetectionEnabledCapability() < CAPABILITY_NOT_APPLICABLE) {
                return null;
            }
            newConfigBuilder.setAutoDetectionEnabled(requestedChanges.isAutoDetectionEnabled());
        }

        return newConfigBuilder.build();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeCapabilities that = (TimeCapabilities) o;
        return mConfigureAutoDetectionEnabledCapability
                == that.mConfigureAutoDetectionEnabledCapability
                && mSetManualTimeCapability == that.mSetManualTimeCapability
                && mUserHandle.equals(that.mUserHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserHandle, mConfigureAutoDetectionEnabledCapability,
                mSetManualTimeCapability);
    }

    @Override
    public String toString() {
        return "TimeCapabilities{"
                + "mUserHandle=" + mUserHandle
                + ", mConfigureAutoDetectionEnabledCapability="
                + mConfigureAutoDetectionEnabledCapability
                + ", mSetManualTimeCapability=" + mSetManualTimeCapability
                + '}';
    }

    /**
     * A builder of {@link TimeCapabilities} objects.
     *
     * @hide
     */
    public static class Builder {

        @NonNull private final UserHandle mUserHandle;
        private @CapabilityState int mConfigureAutoDetectionEnabledCapability;
        private @CapabilityState int mSetManualTimeCapability;

        public Builder(@NonNull UserHandle userHandle) {
            this.mUserHandle = Objects.requireNonNull(userHandle);
        }

        public Builder(@NonNull TimeCapabilities timeCapabilities) {
            Objects.requireNonNull(timeCapabilities);
            this.mUserHandle = timeCapabilities.mUserHandle;
            this.mConfigureAutoDetectionEnabledCapability =
                    timeCapabilities.mConfigureAutoDetectionEnabledCapability;
            this.mSetManualTimeCapability = timeCapabilities.mSetManualTimeCapability;
        }

        /** Sets the value for the "configure automatic time detection" capability. */
        public Builder setConfigureAutoDetectionEnabledCapability(@CapabilityState int value) {
            this.mConfigureAutoDetectionEnabledCapability = value;
            return this;
        }

        /** Sets the value for the "set manual time" capability. */
        public Builder setSetManualTimeCapability(@CapabilityState int value) {
            this.mSetManualTimeCapability = value;
            return this;
        }

        /** Returns the {@link TimeCapabilities}. */
        public TimeCapabilities build() {
            verifyCapabilitySet(mConfigureAutoDetectionEnabledCapability,
                    "configureAutoDetectionEnabledCapability");
            verifyCapabilitySet(mSetManualTimeCapability, "mSetManualTimeCapability");
            return new TimeCapabilities(this);
        }

        private void verifyCapabilitySet(int value, String name) {
            if (value == 0) {
                throw new IllegalStateException(name + " was not set");
            }
        }
    }
}
