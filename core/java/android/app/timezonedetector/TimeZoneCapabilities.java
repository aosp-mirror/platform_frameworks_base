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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Time zone-related capabilities for a user. A capability is the ability for the user to configure
 * something or perform an action. This information is exposed so that system apps like SettingsUI
 * can be dynamic, rather than hard-coding knowledge of when configuration or actions are applicable
 * / available to the user.
 *
 * <p>Capabilities have states that users cannot change directly. They may influence some
 * capabilities indirectly by agreeing to certain device-wide behaviors such as location sharing, or
 * by changing the configuration. See the {@code CAPABILITY_} constants for details.
 *
 * <p>Actions have associated methods, see the documentation for each action for details.
 *
 * <p>For configuration capabilities, the associated current configuration value can be retrieved
 * using {@link TimeZoneDetector#getConfiguration()} and may be changed using
 * {@link TimeZoneDetector#updateConfiguration(TimeZoneConfiguration)}.
 *
 * <p>Note: Capabilities are independent of app permissions required to call the associated APIs.
 *
 * @hide
 */
public final class TimeZoneCapabilities implements Parcelable {

    @IntDef({ CAPABILITY_NOT_SUPPORTED, CAPABILITY_NOT_ALLOWED, CAPABILITY_NOT_APPLICABLE,
            CAPABILITY_POSSESSED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CapabilityState {}

    /**
     * Indicates that a capability is not supported on this device, e.g. because of form factor or
     * hardware. The associated UI should usually not be shown to the user.
     */
    public static final int CAPABILITY_NOT_SUPPORTED = 10;

    /**
     * Indicates that a capability is supported on this device, but not allowed for the user.
     * This could be because of the user's type (e.g. maybe it applies to the primary user only) or
     * device policy. Depending on the capability, this could mean the associated UI
     * should be hidden, or displayed but disabled.
     */
    public static final int CAPABILITY_NOT_ALLOWED = 20;

    /**
     * Indicates that a capability is possessed but not applicable, e.g. if it is configuration,
     * the current configuration or device state renders it irrelevant. The associated UI may be
     * hidden, disabled, or left visible (but ineffective) depending on requirements.
     */
    public static final int CAPABILITY_NOT_APPLICABLE = 30;

    /** Indicates that a capability is possessed by the user. */
    public static final int CAPABILITY_POSSESSED = 40;

    public static final @NonNull Creator<TimeZoneCapabilities> CREATOR =
            new Creator<TimeZoneCapabilities>() {
                public TimeZoneCapabilities createFromParcel(Parcel in) {
                    return TimeZoneCapabilities.createFromParcel(in);
                }

                public TimeZoneCapabilities[] newArray(int size) {
                    return new TimeZoneCapabilities[size];
                }
            };


    private final @UserIdInt int mUserId;
    private final @CapabilityState int mConfigureAutoDetectionEnabled;
    private final @CapabilityState int mSuggestManualTimeZone;

    private TimeZoneCapabilities(@NonNull Builder builder) {
        this.mUserId = builder.mUserId;
        this.mConfigureAutoDetectionEnabled = builder.mConfigureAutoDetectionEnabled;
        this.mSuggestManualTimeZone = builder.mSuggestManualTimeZone;
    }

    @NonNull
    private static TimeZoneCapabilities createFromParcel(Parcel in) {
        return new TimeZoneCapabilities.Builder(in.readInt())
                .setConfigureAutoDetectionEnabled(in.readInt())
                .setSuggestManualTimeZone(in.readInt())
                .build();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeInt(mConfigureAutoDetectionEnabled);
        dest.writeInt(mSuggestManualTimeZone);
    }

    /** Returns the user ID the capabilities are for. */
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /**
     * Returns the user's capability state for controlling automatic time zone detection via
     * {@link TimeZoneDetector#updateConfiguration(TimeZoneConfiguration)} and {@link
     * TimeZoneConfiguration#isAutoDetectionEnabled()}.
     */
    @CapabilityState
    public int getConfigureAutoDetectionEnabled() {
        return mConfigureAutoDetectionEnabled;
    }

    /**
     * Returns the user's capability state for manually setting the time zone on a device via
     * {@link TimeZoneDetector#suggestManualTimeZone(ManualTimeZoneSuggestion)}.
     *
     * <p>The suggestion will be ignored in all cases unless the value is {@link
     * #CAPABILITY_POSSESSED}. See also {@link TimeZoneConfiguration#isAutoDetectionEnabled()}.
     */
    @CapabilityState
    public int getSuggestManualTimeZone() {
        return mSuggestManualTimeZone;
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
        TimeZoneCapabilities that = (TimeZoneCapabilities) o;
        return mUserId == that.mUserId
                && mConfigureAutoDetectionEnabled == that.mConfigureAutoDetectionEnabled
                && mSuggestManualTimeZone == that.mSuggestManualTimeZone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId, mConfigureAutoDetectionEnabled, mSuggestManualTimeZone);
    }

    @Override
    public String toString() {
        return "TimeZoneDetectorCapabilities{"
                + "mUserId=" + mUserId
                + ", mConfigureAutomaticDetectionEnabled=" + mConfigureAutoDetectionEnabled
                + ", mSuggestManualTimeZone=" + mSuggestManualTimeZone
                + '}';
    }

    /** @hide */
    public static class Builder {

        private final @UserIdInt int mUserId;
        private @CapabilityState int mConfigureAutoDetectionEnabled;
        private @CapabilityState int mSuggestManualTimeZone;

        /**
         * Creates a new Builder with no properties set.
         */
        public Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /** Sets the state for the automatic time zone detection enabled config. */
        public Builder setConfigureAutoDetectionEnabled(@CapabilityState int value) {
            this.mConfigureAutoDetectionEnabled = value;
            return this;
        }

        /** Sets the state for the suggestManualTimeZone action. */
        public Builder setSuggestManualTimeZone(@CapabilityState int value) {
            this.mSuggestManualTimeZone = value;
            return this;
        }

        /** Returns the {@link TimeZoneCapabilities}. */
        @NonNull
        public TimeZoneCapabilities build() {
            verifyCapabilitySet(mConfigureAutoDetectionEnabled, "configureAutoDetectionEnabled");
            verifyCapabilitySet(mSuggestManualTimeZone, "suggestManualTimeZone");
            return new TimeZoneCapabilities(this);
        }

        private void verifyCapabilitySet(int value, String name) {
            if (value == 0) {
                throw new IllegalStateException(name + " not set");
            }
        }
    }
}
