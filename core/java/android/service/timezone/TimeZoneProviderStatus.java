/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.timezone;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Information about the status of a {@link TimeZoneProviderService}.
 *
 * <p>Not all status properties or status values will apply to all provider implementations.
 * {@code _NOT_APPLICABLE} status can be used to indicate properties that have no meaning for a
 * given implementation.
 *
 * <p>Time zone providers are expected to work in one of two ways:
 * <ol>
 *     <li>Location: Providers will determine location and then map that location to one or more
 *     time zone IDs.</li>
 *     <li>External signals: Providers could use indirect signals like country code
 *     and/or local offset / DST information provided to the device to infer a time zone, e.g.
 *     signals like MCC and NITZ for telephony devices, IP geo location, or DHCP information
 *     (RFC4833). The time zone ID could also be fed directly to the device by an external service.
 *     </li>
 * </ol>
 *
 * <p>The status properties are:
 * <ul>
 *     <li>location detection - for location-based providers, the status of the location detection
 *     mechanism</li>
 *     <li>connectivity - connectivity can influence providers directly, for example if they use
 *     a networked service to map location to time zone ID, or use geo IP, or indirectly for
 *     location detection (e.g. for the network location provider.</li>
 *     <li>time zone resolution - the status related to determining a time zone ID or using a
 *     detected time zone ID. For example, a networked service may be reachable (i.e. connectivity
 *     is working) but the service could return errors, a time zone ID detected may not be usable
 *     for a device because of TZDB version skew, or external indirect signals may available but
 *     do not match the properties of a known time zone ID.</li>
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class TimeZoneProviderStatus implements Parcelable {

    /**
     * A status code related to a dependency a provider may have.
     *
     * @hide
     */
    @IntDef(prefix = "DEPENDENCY_STATUS_", value = {
            DEPENDENCY_STATUS_UNKNOWN,
            DEPENDENCY_STATUS_NOT_APPLICABLE,
            DEPENDENCY_STATUS_OK,
            DEPENDENCY_STATUS_TEMPORARILY_UNAVAILABLE,
            DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT,
            DEPENDENCY_STATUS_DEGRADED_BY_SETTINGS,
            DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS,
    })
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface DependencyStatus {}

    /**
     * The dependency's status is unknown.
     *
     * @hide
     */
    public static final @DependencyStatus int DEPENDENCY_STATUS_UNKNOWN = 0;

    /** The dependency is not used by the provider's implementation. */
    public static final @DependencyStatus int DEPENDENCY_STATUS_NOT_APPLICABLE = 1;

    /** The dependency is applicable and there are no known problems. */
    public static final @DependencyStatus int DEPENDENCY_STATUS_OK = 2;

    /**
     * The dependency is used but is temporarily unavailable, e.g. connectivity has been lost for an
     * unpredictable amount of time.
     *
     * <p>This status is considered normal is may be entered many times a day.
     */
    public static final @DependencyStatus int DEPENDENCY_STATUS_TEMPORARILY_UNAVAILABLE = 3;

    /**
     * The dependency is used by the provider but is blocked by the environment in a way that the
     * provider has detected and is considered likely to persist for some time, e.g. connectivity
     * has been lost due to boarding a plane.
     *
     * <p>This status is considered unusual and could be used by the system as a trigger to try
     * other time zone providers / time zone detection mechanisms. The bar for using this status
     * should therefore be set fairly high to avoid a device bringing up other providers or
     * switching to a different detection mechanism that may provide a different suggestion.
     */
    public static final @DependencyStatus int DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT = 4;

    /**
     * The dependency is used by the provider but is running in a degraded mode due to the user's
     * settings. A user can take action to improve this, e.g. by changing a setting.
     *
     * <p>This status could be used by the system as a trigger to try other time zone
     * providers / time zone detection mechanisms. The user may be informed.
     */
    public static final @DependencyStatus int DEPENDENCY_STATUS_DEGRADED_BY_SETTINGS = 5;

    /**
     * The dependency is used by the provider but is completely blocked by the user's settings.
     * A user can take action to correct this, e.g. by changing a setting.
     *
     * <p>This status could be used by the system as a trigger to try other time zone providers /
     * time zone detection mechanisms. The user may be informed.
     */
    public static final @DependencyStatus int DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS = 6;

    /**
     * A status code related to an operation in a provider's detection algorithm.
     *
     * @hide
     */
    @IntDef(prefix = "OPERATION_STATUS_", value = {
            OPERATION_STATUS_UNKNOWN,
            OPERATION_STATUS_NOT_APPLICABLE,
            OPERATION_STATUS_OK,
            OPERATION_STATUS_FAILED,
    })
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface OperationStatus {}

    /**
     * The operation's status is unknown.
     *
     * @hide
     */
    public static final @OperationStatus int OPERATION_STATUS_UNKNOWN = 0;

    /** The operation is not used by the provider's implementation. */
    public static final @OperationStatus int OPERATION_STATUS_NOT_APPLICABLE = 1;

    /** The operation is applicable and there are no known problems. */
    public static final @OperationStatus int OPERATION_STATUS_OK = 2;

    /** The operation is applicable and it recently failed. */
    public static final @OperationStatus int OPERATION_STATUS_FAILED = 3;

    private final @DependencyStatus int mLocationDetectionDependencyStatus;
    private final @DependencyStatus int mConnectivityDependencyStatus;
    private final @OperationStatus int mTimeZoneResolutionOperationStatus;

    private TimeZoneProviderStatus(
            @DependencyStatus int locationDetectionStatus,
            @DependencyStatus int connectivityStatus,
            @OperationStatus int timeZoneResolutionStatus) {
        mLocationDetectionDependencyStatus = locationDetectionStatus;
        mConnectivityDependencyStatus = connectivityStatus;
        mTimeZoneResolutionOperationStatus = timeZoneResolutionStatus;
    }

    /**
     * Returns the status of the location detection dependencies used by the provider (where
     * applicable).
     */
    public @DependencyStatus int getLocationDetectionDependencyStatus() {
        return mLocationDetectionDependencyStatus;
    }

    /**
     * Returns the status of the connectivity dependencies used by the provider (where applicable).
     */
    public @DependencyStatus int getConnectivityDependencyStatus() {
        return mConnectivityDependencyStatus;
    }

    /**
     * Returns the status of the time zone resolution operation used by the provider.
     */
    public @OperationStatus int getTimeZoneResolutionOperationStatus() {
        return mTimeZoneResolutionOperationStatus;
    }

    @Override
    public String toString() {
        return "TimeZoneProviderStatus{"
                + "mLocationDetectionDependencyStatus="
                + dependencyStatusToString(mLocationDetectionDependencyStatus)
                + ", mConnectivityDependencyStatus="
                + dependencyStatusToString(mConnectivityDependencyStatus)
                + ", mTimeZoneResolutionOperationStatus="
                + operationStatusToString(mTimeZoneResolutionOperationStatus)
                + '}';
    }

    /**
     * Parses a {@link TimeZoneProviderStatus} from a toString() string for manual command-line
     * testing.
     *
     * @hide
     */
    @NonNull
    public static TimeZoneProviderStatus parseProviderStatus(@NonNull String arg) {
        // Note: "}" has to be escaped on Android with "\\}" because the regexp library is not based
        // on OpenJDK code.
        Pattern pattern = Pattern.compile("TimeZoneProviderStatus\\{"
                + "mLocationDetectionDependencyStatus=([^,]+)"
                + ", mConnectivityDependencyStatus=([^,]+)"
                + ", mTimeZoneResolutionOperationStatus=([^\\}]+)"
                + "\\}");
        Matcher matcher = pattern.matcher(arg);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unable to parse provider status: " + arg);
        }
        @DependencyStatus int locationDependencyStatus =
                dependencyStatusFromString(matcher.group(1));
        @DependencyStatus int connectivityDependencyStatus =
                dependencyStatusFromString(matcher.group(2));
        @OperationStatus int timeZoneResolutionOperationStatus =
                operationStatusFromString(matcher.group(3));
        return new TimeZoneProviderStatus(locationDependencyStatus, connectivityDependencyStatus,
                timeZoneResolutionOperationStatus);
    }

    public static final @NonNull Creator<TimeZoneProviderStatus> CREATOR = new Creator<>() {
        @Override
        public TimeZoneProviderStatus createFromParcel(Parcel in) {
            @DependencyStatus int locationDetectionStatus = in.readInt();
            @DependencyStatus int connectivityStatus = in.readInt();
            @OperationStatus int timeZoneResolutionStatus = in.readInt();
            return new TimeZoneProviderStatus(
                    locationDetectionStatus, connectivityStatus, timeZoneResolutionStatus);
        }

        @Override
        public TimeZoneProviderStatus[] newArray(int size) {
            return new TimeZoneProviderStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mLocationDetectionDependencyStatus);
        parcel.writeInt(mConnectivityDependencyStatus);
        parcel.writeInt(mTimeZoneResolutionOperationStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneProviderStatus that = (TimeZoneProviderStatus) o;
        return mLocationDetectionDependencyStatus == that.mLocationDetectionDependencyStatus
                && mConnectivityDependencyStatus == that.mConnectivityDependencyStatus
                && mTimeZoneResolutionOperationStatus == that.mTimeZoneResolutionOperationStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mLocationDetectionDependencyStatus, mConnectivityDependencyStatus,
                mTimeZoneResolutionOperationStatus);
    }

    /** @hide */
    public boolean couldEnableTelephonyFallback() {
        return mLocationDetectionDependencyStatus == DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT
                || mLocationDetectionDependencyStatus == DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS
                || mConnectivityDependencyStatus == DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT
                || mConnectivityDependencyStatus == DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
    }

    /** A builder for {@link TimeZoneProviderStatus}. */
    public static final class Builder {

        private @DependencyStatus int mLocationDetectionDependencyStatus =
                DEPENDENCY_STATUS_UNKNOWN;
        private @DependencyStatus int mConnectivityDependencyStatus = DEPENDENCY_STATUS_UNKNOWN;
        private @OperationStatus int mTimeZoneResolutionOperationStatus = OPERATION_STATUS_UNKNOWN;

        /**
         * Creates a new builder instance. At creation time all status properties are set to
         * their "UNKNOWN" value.
         */
        public Builder() {
        }

        /**
         * @hide
         */
        public Builder(TimeZoneProviderStatus toCopy) {
            mLocationDetectionDependencyStatus = toCopy.mLocationDetectionDependencyStatus;
            mConnectivityDependencyStatus = toCopy.mConnectivityDependencyStatus;
            mTimeZoneResolutionOperationStatus = toCopy.mTimeZoneResolutionOperationStatus;
        }

        /**
         * Sets the status of the provider's location detection dependency (where applicable).
         * See the {@code DEPENDENCY_STATUS_} constants for more information.
         */
        @NonNull
        public Builder setLocationDetectionDependencyStatus(
                @DependencyStatus int locationDetectionStatus) {
            mLocationDetectionDependencyStatus = locationDetectionStatus;
            return this;
        }

        /**
         * Sets the status of the provider's connectivity dependency (where applicable).
         * See the {@code DEPENDENCY_STATUS_} constants for more information.
         */
        @NonNull
        public Builder setConnectivityDependencyStatus(@DependencyStatus int connectivityStatus) {
            mConnectivityDependencyStatus = connectivityStatus;
            return this;
        }

        /**
         * Sets the status of the provider's time zone resolution operation.
         * See the {@code OPERATION_STATUS_} constants for more information.
         */
        @NonNull
        public Builder setTimeZoneResolutionOperationStatus(
                @OperationStatus int timeZoneResolutionStatus) {
            mTimeZoneResolutionOperationStatus = timeZoneResolutionStatus;
            return this;
        }

        /**
         * Builds a {@link TimeZoneProviderStatus} instance.
         */
        @NonNull
        public TimeZoneProviderStatus build() {
            return new TimeZoneProviderStatus(
                    requireValidDependencyStatus(mLocationDetectionDependencyStatus),
                    requireValidDependencyStatus(mConnectivityDependencyStatus),
                    requireValidOperationStatus(mTimeZoneResolutionOperationStatus));
        }
    }

    private static @OperationStatus int requireValidOperationStatus(
            @OperationStatus int operationStatus) {
        if (operationStatus < OPERATION_STATUS_UNKNOWN
                || operationStatus > OPERATION_STATUS_FAILED) {
            throw new IllegalArgumentException(Integer.toString(operationStatus));
        }
        return operationStatus;
    }

    /** @hide */
    @NonNull
    public static String operationStatusToString(@OperationStatus int operationStatus) {
        switch (operationStatus) {
            case OPERATION_STATUS_UNKNOWN:
                return "UNKNOWN";
            case OPERATION_STATUS_NOT_APPLICABLE:
                return "NOT_APPLICABLE";
            case OPERATION_STATUS_OK:
                return "OK";
            case OPERATION_STATUS_FAILED:
                return "FAILED";
            default:
                throw new IllegalArgumentException("Unknown status: " + operationStatus);
        }
    }

    /** @hide */
    public static @OperationStatus int operationStatusFromString(
            @Nullable String operationStatusString) {

        if (TextUtils.isEmpty(operationStatusString)) {
            throw new IllegalArgumentException("Empty status: " + operationStatusString);
        }

        switch (operationStatusString) {
            case "UNKNOWN":
                return OPERATION_STATUS_UNKNOWN;
            case "NOT_APPLICABLE":
                return OPERATION_STATUS_NOT_APPLICABLE;
            case "OK":
                return OPERATION_STATUS_OK;
            case "FAILED":
                return OPERATION_STATUS_FAILED;
            default:
                throw new IllegalArgumentException("Unknown status: " + operationStatusString);
        }
    }

    private static @DependencyStatus int requireValidDependencyStatus(
            @DependencyStatus int dependencyStatus) {
        if (dependencyStatus < DEPENDENCY_STATUS_UNKNOWN
                || dependencyStatus > DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS) {
            throw new IllegalArgumentException(Integer.toString(dependencyStatus));
        }
        return dependencyStatus;
    }

    /** @hide */
    @NonNull
    public static String dependencyStatusToString(@DependencyStatus int dependencyStatus) {
        switch (dependencyStatus) {
            case DEPENDENCY_STATUS_UNKNOWN:
                return "UNKNOWN";
            case DEPENDENCY_STATUS_NOT_APPLICABLE:
                return "NOT_APPLICABLE";
            case DEPENDENCY_STATUS_OK:
                return "OK";
            case DEPENDENCY_STATUS_TEMPORARILY_UNAVAILABLE:
                return "TEMPORARILY_UNAVAILABLE";
            case DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT:
                return "BLOCKED_BY_ENVIRONMENT";
            case DEPENDENCY_STATUS_DEGRADED_BY_SETTINGS:
                return "DEGRADED_BY_SETTINGS";
            case DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS:
                return "BLOCKED_BY_SETTINGS";
            default:
                throw new IllegalArgumentException("Unknown status: " + dependencyStatus);
        }
    }

    /** @hide */
    public static @DependencyStatus int dependencyStatusFromString(
            @Nullable String dependencyStatusString) {

        if (TextUtils.isEmpty(dependencyStatusString)) {
            throw new IllegalArgumentException("Empty status: " + dependencyStatusString);
        }

        switch (dependencyStatusString) {
            case "UNKNOWN":
                return DEPENDENCY_STATUS_UNKNOWN;
            case "NOT_APPLICABLE":
                return DEPENDENCY_STATUS_NOT_APPLICABLE;
            case "OK":
                return DEPENDENCY_STATUS_OK;
            case "TEMPORARILY_UNAVAILABLE":
                return DEPENDENCY_STATUS_TEMPORARILY_UNAVAILABLE;
            case "BLOCKED_BY_ENVIRONMENT":
                return DEPENDENCY_STATUS_BLOCKED_BY_ENVIRONMENT;
            case "DEGRADED_BY_SETTINGS":
                return DEPENDENCY_STATUS_DEGRADED_BY_SETTINGS;
            case "BLOCKED_BY_SETTINGS":
                return DEPENDENCY_STATUS_BLOCKED_BY_SETTINGS;
            default:
                throw new IllegalArgumentException(
                        "Unknown status: " + dependencyStatusString);
        }
    }
}
