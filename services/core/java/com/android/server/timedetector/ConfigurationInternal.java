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

package com.android.server.timedetector;

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import static java.util.stream.Collectors.joining;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.Capabilities.CapabilityState;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.os.UserHandle;

import com.android.server.timedetector.TimeDetectorStrategy.Origin;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Holds configuration values that affect user-facing time behavior and some associated logic.
 * Some configuration is global, some is user scoped, but this class deliberately doesn't make a
 * distinction for simplicity.
 */
public final class ConfigurationInternal {

    private final boolean mAutoDetectionSupported;
    private final int mSystemClockUpdateThresholdMillis;
    private final int mSystemClockConfidenceThresholdMillis;
    private final Instant mAutoSuggestionLowerBound;
    private final Instant mManualSuggestionLowerBound;
    private final Instant mSuggestionUpperBound;
    private final @Origin int[] mOriginPriorities;
    private final boolean mAutoDetectionEnabledSetting;
    private final @UserIdInt int mUserId;
    private final boolean mUserConfigAllowed;

    private ConfigurationInternal(Builder builder) {
        mAutoDetectionSupported = builder.mAutoDetectionSupported;
        mSystemClockUpdateThresholdMillis = builder.mSystemClockUpdateThresholdMillis;
        mSystemClockConfidenceThresholdMillis =
                builder.mSystemClockConfidenceThresholdMillis;
        mAutoSuggestionLowerBound = Objects.requireNonNull(builder.mAutoSuggestionLowerBound);
        mManualSuggestionLowerBound = Objects.requireNonNull(builder.mManualSuggestionLowerBound);
        mSuggestionUpperBound = Objects.requireNonNull(builder.mSuggestionUpperBound);
        mOriginPriorities = Objects.requireNonNull(builder.mOriginPriorities);
        mAutoDetectionEnabledSetting = builder.mAutoDetectionEnabledSetting;

        mUserId = builder.mUserId;
        mUserConfigAllowed = builder.mUserConfigAllowed;
    }

    /** Returns true if the device supports any form of auto time detection. */
    public boolean isAutoDetectionSupported() {
        return mAutoDetectionSupported;
    }

    /**
     * Returns the absolute threshold below which the system clock need not be updated. i.e. if
     * setting the system clock would adjust it by less than this (either backwards or forwards)
     * then it need not be set.
     */
    public int getSystemClockUpdateThresholdMillis() {
        return mSystemClockUpdateThresholdMillis;
    }

    /**
     * Return the absolute threshold for Unix epoch time comparison at/below which the system clock
     * confidence can be said to be "close enough", e.g. if the detector receives a high-confidence
     * time and the current system clock is +/- this value from that time and the current confidence
     * in the time is low, then the device's confidence in the current system clock time can be
     * upgraded.
     */
    public int getSystemClockConfidenceThresholdMillis() {
        return mSystemClockConfidenceThresholdMillis;
    }

    /**
     * Returns the lower bound for valid automatic time suggestions. It is guaranteed to be in the
     * past, i.e. it is unrelated to the current system clock time.
     * It holds no other meaning; it could be related to when the device system image was built,
     * or could be updated by a mainline module.
     */
    @NonNull
    public Instant getAutoSuggestionLowerBound() {
        return mAutoSuggestionLowerBound;
    }

    /**
     * Returns the lower bound for valid manual time suggestions. It is guaranteed to be in the
     * past, i.e. it is unrelated to the current system clock time.
     */
    @NonNull
    public Instant getManualSuggestionLowerBound() {
        return mManualSuggestionLowerBound;
    }

    /**
     * Returns the upper bound for valid time suggestions (manual and automatic).
     */
    @NonNull
    public Instant getSuggestionUpperBound() {
        return mSuggestionUpperBound;
    }

    /**
     * Returns the order to look at time suggestions when automatically detecting time.
     * See {@code #ORIGIN_} constants
     */
    public @Origin int[] getAutoOriginPriorities() {
        return mOriginPriorities;
    }

    /** Returns the value of the auto time detection enabled setting. */
    public boolean getAutoDetectionEnabledSetting() {
        return mAutoDetectionEnabledSetting;
    }

    /**
     * Returns true if auto time detection behavior is actually enabled, which can be distinct
     * from the raw setting value.
     */
    public boolean getAutoDetectionEnabledBehavior() {
        return isAutoDetectionSupported() && mAutoDetectionEnabledSetting;
    }

    /** Returns the ID of the user this configuration is associated with. */
    public @UserIdInt int getUserId() {
        return mUserId;
    }

    /** Returns the handle of the user this configuration is associated with. */
    @NonNull
    public UserHandle getUserHandle() {
        return UserHandle.of(mUserId);
    }

    /**
     * Returns true if the user is allowed to modify time configuration, e.g. can be false due
     * to device policy (enterprise).
     *
     * <p>See also {@link #createCapabilitiesAndConfig(boolean)} for situations where this
     * value are ignored.
     */
    public boolean isUserConfigAllowed() {
        return mUserConfigAllowed;
    }

    /**
     * Returns a {@link TimeCapabilitiesAndConfig} objects based on configuration values.
     *
     * @param bypassUserPolicyChecks {@code true} for device policy manager use cases where device
     *   policy restrictions that should apply to actual users can be ignored
     */
    public TimeCapabilitiesAndConfig createCapabilitiesAndConfig(boolean bypassUserPolicyChecks) {
        return new TimeCapabilitiesAndConfig(
                timeCapabilities(bypassUserPolicyChecks), timeConfiguration());
    }

    private TimeCapabilities timeCapabilities(boolean bypassUserPolicyChecks) {
        UserHandle userHandle = UserHandle.of(mUserId);
        TimeCapabilities.Builder builder = new TimeCapabilities.Builder(userHandle);

        boolean allowConfigDateTime = isUserConfigAllowed() || bypassUserPolicyChecks;

        boolean deviceHasAutoTimeDetection = isAutoDetectionSupported();
        final @CapabilityState int configureAutoDetectionEnabledCapability;
        if (!deviceHasAutoTimeDetection) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_SUPPORTED;
        } else if (!allowConfigDateTime) {
            configureAutoDetectionEnabledCapability = CAPABILITY_NOT_ALLOWED;
        } else {
            configureAutoDetectionEnabledCapability = CAPABILITY_POSSESSED;
        }
        builder.setConfigureAutoDetectionEnabledCapability(configureAutoDetectionEnabledCapability);

        // The ability to make manual time suggestions can also be restricted by policy. With the
        // current logic above, this could lead to a situation where a device hardware does not
        // support auto detection, the device has been forced into "auto" mode by an admin and the
        // user is unable to disable auto detection.
        final @CapabilityState int suggestManualTimeCapability;
        if (!allowConfigDateTime) {
            suggestManualTimeCapability = CAPABILITY_NOT_ALLOWED;
        } else if (getAutoDetectionEnabledBehavior()) {
            suggestManualTimeCapability = CAPABILITY_NOT_APPLICABLE;
        } else {
            suggestManualTimeCapability = CAPABILITY_POSSESSED;
        }
        builder.setSetManualTimeCapability(suggestManualTimeCapability);

        return builder.build();
    }

    /** Returns a {@link TimeConfiguration} from the configuration values. */
    private TimeConfiguration timeConfiguration() {
        return new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(getAutoDetectionEnabledSetting())
                .build();
    }

    /**
     * Merges the configuration values from this with any properties set in {@code
     * newConfiguration}. The new configuration has precedence. Used to apply user updates to
     * internal configuration.
     */
    public ConfigurationInternal merge(TimeConfiguration newConfiguration) {
        Builder builder = new Builder(this);
        if (newConfiguration.hasIsAutoDetectionEnabled()) {
            builder.setAutoDetectionEnabledSetting(newConfiguration.isAutoDetectionEnabled());
        }
        return builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConfigurationInternal)) {
            return false;
        }
        ConfigurationInternal that = (ConfigurationInternal) o;
        return mAutoDetectionSupported == that.mAutoDetectionSupported
                && mAutoDetectionEnabledSetting == that.mAutoDetectionEnabledSetting
                && mUserId == that.mUserId && mUserConfigAllowed == that.mUserConfigAllowed
                && mSystemClockUpdateThresholdMillis == that.mSystemClockUpdateThresholdMillis
                && mSystemClockConfidenceThresholdMillis
                == that.mSystemClockConfidenceThresholdMillis
                && mAutoSuggestionLowerBound.equals(that.mAutoSuggestionLowerBound)
                && mManualSuggestionLowerBound.equals(that.mManualSuggestionLowerBound)
                && mSuggestionUpperBound.equals(that.mSuggestionUpperBound)
                && Arrays.equals(mOriginPriorities, that.mOriginPriorities);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mAutoDetectionSupported, mAutoDetectionEnabledSetting, mUserId,
                mUserConfigAllowed, mSystemClockUpdateThresholdMillis,
                mSystemClockConfidenceThresholdMillis, mAutoSuggestionLowerBound,
                mManualSuggestionLowerBound, mSuggestionUpperBound);
        result = 31 * result + Arrays.hashCode(mOriginPriorities);
        return result;
    }

    @Override
    public String toString() {
        String originPrioritiesString =
                Arrays.stream(mOriginPriorities)
                        .mapToObj(TimeDetectorStrategy::originToString)
                        .collect(joining(",", "[", "]"));
        return "ConfigurationInternal{"
                + "mAutoDetectionSupported=" + mAutoDetectionSupported
                + ", mSystemClockUpdateThresholdMillis=" + mSystemClockUpdateThresholdMillis
                + ", mSystemClockConfidenceThresholdMillis="
                + mSystemClockConfidenceThresholdMillis
                + ", mAutoSuggestionLowerBound=" + mAutoSuggestionLowerBound
                + "(" + mAutoSuggestionLowerBound.toEpochMilli() + ")"
                + ", mManualSuggestionLowerBound=" + mManualSuggestionLowerBound
                + "(" + mManualSuggestionLowerBound.toEpochMilli() + ")"
                + ", mSuggestionUpperBound=" + mSuggestionUpperBound
                + "(" + mSuggestionUpperBound.toEpochMilli() + ")"
                + ", mOriginPriorities=" + originPrioritiesString
                + ", mAutoDetectionEnabled=" + mAutoDetectionEnabledSetting
                + ", mUserId=" + mUserId
                + ", mUserConfigAllowed=" + mUserConfigAllowed
                + '}';
    }

    static final class Builder {
        private boolean mAutoDetectionSupported;
        private int mSystemClockUpdateThresholdMillis;
        private int mSystemClockConfidenceThresholdMillis;
        @NonNull private Instant mAutoSuggestionLowerBound;
        @NonNull private Instant mManualSuggestionLowerBound;
        @NonNull private Instant mSuggestionUpperBound;
        @NonNull private @Origin int[] mOriginPriorities;
        private boolean mAutoDetectionEnabledSetting;

        private final @UserIdInt int mUserId;
        private boolean mUserConfigAllowed;

        Builder(@UserIdInt int userId) {
            mUserId = userId;
        }

        /**
         * Creates a new Builder by copying values from an existing instance.
         */
        Builder(ConfigurationInternal toCopy) {
            this.mUserId = toCopy.mUserId;
            this.mUserConfigAllowed = toCopy.mUserConfigAllowed;
            this.mAutoDetectionSupported = toCopy.mAutoDetectionSupported;
            this.mSystemClockUpdateThresholdMillis = toCopy.mSystemClockUpdateThresholdMillis;
            this.mAutoSuggestionLowerBound = toCopy.mAutoSuggestionLowerBound;
            this.mManualSuggestionLowerBound = toCopy.mManualSuggestionLowerBound;
            this.mSuggestionUpperBound = toCopy.mSuggestionUpperBound;
            this.mOriginPriorities = toCopy.mOriginPriorities;
            this.mAutoDetectionEnabledSetting = toCopy.mAutoDetectionEnabledSetting;
        }

        /** See {@link ConfigurationInternal#isUserConfigAllowed()}. */
        Builder setUserConfigAllowed(boolean userConfigAllowed) {
            mUserConfigAllowed = userConfigAllowed;
            return this;
        }

        /** See {@link ConfigurationInternal#isAutoDetectionSupported()}. */
        public Builder setAutoDetectionSupported(boolean supported) {
            mAutoDetectionSupported = supported;
            return this;
        }

        /** See {@link ConfigurationInternal#getSystemClockUpdateThresholdMillis()}. */
        public Builder setSystemClockUpdateThresholdMillis(int systemClockUpdateThresholdMillis) {
            mSystemClockUpdateThresholdMillis = systemClockUpdateThresholdMillis;
            return this;
        }

        /** See {@link ConfigurationInternal#getSystemClockConfidenceThresholdMillis()}. */
        public Builder setSystemClockConfidenceThresholdMillis(int thresholdMillis) {
            mSystemClockConfidenceThresholdMillis = thresholdMillis;
            return this;
        }

        /** See {@link ConfigurationInternal#getAutoSuggestionLowerBound()}. */
        public Builder setAutoSuggestionLowerBound(@NonNull Instant autoSuggestionLowerBound) {
            mAutoSuggestionLowerBound = Objects.requireNonNull(autoSuggestionLowerBound);
            return this;
        }

        /** See {@link ConfigurationInternal#getManualSuggestionLowerBound()}. */
        public Builder setManualSuggestionLowerBound(@NonNull Instant manualSuggestionLowerBound) {
            mManualSuggestionLowerBound = Objects.requireNonNull(manualSuggestionLowerBound);
            return this;
        }

        /** See {@link ConfigurationInternal#getSuggestionUpperBound()}. */
        public Builder setSuggestionUpperBound(@NonNull Instant suggestionUpperBound) {
            mSuggestionUpperBound = Objects.requireNonNull(suggestionUpperBound);
            return this;
        }

        /** See {@link ConfigurationInternal#getAutoOriginPriorities()}. */
        public Builder setOriginPriorities(@NonNull @Origin int... originPriorities) {
            mOriginPriorities = Objects.requireNonNull(originPriorities);
            return this;
        }

        /** See {@link ConfigurationInternal#getAutoDetectionEnabledSetting()}. */
        Builder setAutoDetectionEnabledSetting(boolean autoDetectionEnabledSetting) {
            mAutoDetectionEnabledSetting = autoDetectionEnabledSetting;
            return this;
        }

        /** Returns a new {@link ConfigurationInternal}. */
        @NonNull
        ConfigurationInternal build() {
            return new ConfigurationInternal(this);
        }
    }

}
