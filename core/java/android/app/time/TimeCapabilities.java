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
public final class TimeCapabilities implements Parcelable {

    public static final @NonNull Creator<TimeCapabilities> CREATOR =
            new Creator<TimeCapabilities>() {
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
    private final @CapabilityState int mConfigureAutoTimeDetectionEnabledCapability;
    private final @CapabilityState int mSuggestTimeManuallyCapability;

    private TimeCapabilities(@NonNull Builder builder) {
        this.mUserHandle = Objects.requireNonNull(builder.mUserHandle);
        this.mConfigureAutoTimeDetectionEnabledCapability =
                builder.mConfigureAutoDetectionEnabledCapability;
        this.mSuggestTimeManuallyCapability =
                builder.mSuggestTimeManuallyCapability;
    }

    @NonNull
    private static TimeCapabilities createFromParcel(Parcel in) {
        UserHandle userHandle = UserHandle.readFromParcel(in);
        return new TimeCapabilities.Builder(userHandle)
                .setConfigureAutoTimeDetectionEnabledCapability(in.readInt())
                .setSuggestTimeManuallyCapability(in.readInt())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        UserHandle.writeToParcel(mUserHandle, dest);
        dest.writeInt(mConfigureAutoTimeDetectionEnabledCapability);
        dest.writeInt(mSuggestTimeManuallyCapability);
    }

    /**
     * Returns the capability state associated with the user's ability to modify the automatic time
     * detection setting.
     */
    @CapabilityState
    public int getConfigureAutoTimeDetectionEnabledCapability() {
        return mConfigureAutoTimeDetectionEnabledCapability;
    }

    /**
     * Returns the capability state associated with the user's ability to manually set time on a
     * device.
     */
    @CapabilityState
    public int getSuggestTimeManuallyCapability() {
        return mSuggestTimeManuallyCapability;
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
        return mConfigureAutoTimeDetectionEnabledCapability
                == that.mConfigureAutoTimeDetectionEnabledCapability
                && mSuggestTimeManuallyCapability == that.mSuggestTimeManuallyCapability
                && mUserHandle.equals(that.mUserHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserHandle, mConfigureAutoTimeDetectionEnabledCapability,
                mSuggestTimeManuallyCapability);
    }

    @Override
    public String toString() {
        return "TimeCapabilities{"
                + "mUserHandle=" + mUserHandle
                + ", mConfigureAutoTimeDetectionEnabledCapability="
                + mConfigureAutoTimeDetectionEnabledCapability
                + ", mSuggestTimeManuallyCapability=" + mSuggestTimeManuallyCapability
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
        private @CapabilityState int mSuggestTimeManuallyCapability;

        public Builder(@NonNull TimeCapabilities timeCapabilities) {
            Objects.requireNonNull(timeCapabilities);
            this.mUserHandle = timeCapabilities.mUserHandle;
            this.mConfigureAutoDetectionEnabledCapability =
                    timeCapabilities.mConfigureAutoTimeDetectionEnabledCapability;
            this.mSuggestTimeManuallyCapability =
                    timeCapabilities.mSuggestTimeManuallyCapability;
        }

        public Builder(@NonNull UserHandle userHandle) {
            this.mUserHandle = Objects.requireNonNull(userHandle);
        }

        /** Sets the state for automatic time detection config. */
        public Builder setConfigureAutoTimeDetectionEnabledCapability(
                @CapabilityState int setConfigureAutoTimeDetectionEnabledCapability) {
            this.mConfigureAutoDetectionEnabledCapability =
                    setConfigureAutoTimeDetectionEnabledCapability;
            return this;
        }

        /** Sets the state for manual time change. */
        public Builder setSuggestTimeManuallyCapability(
                @CapabilityState int suggestTimeManuallyCapability) {
            this.mSuggestTimeManuallyCapability = suggestTimeManuallyCapability;
            return this;
        }

        /** Returns the {@link TimeCapabilities}. */
        public TimeCapabilities build() {
            verifyCapabilitySet(mConfigureAutoDetectionEnabledCapability,
                    "configureAutoDetectionEnabledCapability");
            verifyCapabilitySet(mSuggestTimeManuallyCapability, "suggestTimeManuallyCapability");
            return new TimeCapabilities(this);
        }

        private void verifyCapabilitySet(int value, String name) {
            if (value == 0) {
                throw new IllegalStateException(name + " was not set");
            }
        }
    }
}
