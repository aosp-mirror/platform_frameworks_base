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

package android.app.time;

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_UNKNOWN;
import static android.app.time.DetectorStatusTypes.detectionAlgorithmStatusFromString;
import static android.app.time.DetectorStatusTypes.detectionAlgorithmStatusToString;
import static android.app.time.DetectorStatusTypes.requireValidDetectionAlgorithmStatus;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.DetectorStatusTypes.DetectionAlgorithmStatus;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.timezone.TimeZoneProviderStatus;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Information about the status of the location-based time zone detection algorithm.
 *
 * @hide
 */
public final class LocationTimeZoneAlgorithmStatus implements Parcelable {

    /**
     * An enum that describes a location time zone provider's status.
     *
     * @hide
     */
    @IntDef(prefix = "PROVIDER_STATUS_", value = {
            PROVIDER_STATUS_NOT_PRESENT,
            PROVIDER_STATUS_NOT_READY,
            PROVIDER_STATUS_IS_CERTAIN,
            PROVIDER_STATUS_IS_UNCERTAIN,
    })
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProviderStatus {}

    /**
     * Indicates a provider is not present because it has not been configured, the configuration
     * is bad, or the provider has reported a permanent failure.
     */
    public static final @ProviderStatus int PROVIDER_STATUS_NOT_PRESENT = 1;

    /**
     * Indicates a provider has not reported it is certain or uncertain. This may be because it has
     * just started running, or it has been stopped.
     */
    public static final @ProviderStatus int PROVIDER_STATUS_NOT_READY = 2;

    /**
     * Indicates a provider last reported it is certain.
     */
    public static final @ProviderStatus int PROVIDER_STATUS_IS_CERTAIN = 3;

    /**
     * Indicates a provider last reported it is uncertain.
     */
    public static final @ProviderStatus int PROVIDER_STATUS_IS_UNCERTAIN = 4;

    /**
     * An instance used when the location algorithm is not supported by the device.
     */
    public static final LocationTimeZoneAlgorithmStatus NOT_SUPPORTED =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED,
                    PROVIDER_STATUS_NOT_PRESENT, null, PROVIDER_STATUS_NOT_PRESENT, null);

    /**
     * An instance used when the location algorithm is running, but has not reported an event.
     */
    public static final LocationTimeZoneAlgorithmStatus RUNNING_NOT_REPORTED =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                    PROVIDER_STATUS_NOT_READY, null, PROVIDER_STATUS_NOT_READY, null);

    /**
     * An instance used when the location algorithm is supported but not running.
     */
    public static final LocationTimeZoneAlgorithmStatus NOT_RUNNING =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_NOT_RUNNING,
                    PROVIDER_STATUS_NOT_READY, null, PROVIDER_STATUS_NOT_READY, null);

    private final @DetectionAlgorithmStatus int mStatus;
    private final @ProviderStatus int mPrimaryProviderStatus;
    // May be populated when mPrimaryProviderReportedStatus == PROVIDER_STATUS_IS_CERTAIN
    // or PROVIDER_STATUS_IS_UNCERTAIN
    @Nullable private final TimeZoneProviderStatus mPrimaryProviderReportedStatus;

    private final @ProviderStatus int mSecondaryProviderStatus;
    // May be populated when mSecondaryProviderReportedStatus == PROVIDER_STATUS_IS_CERTAIN
    // or PROVIDER_STATUS_IS_UNCERTAIN
    @Nullable private final TimeZoneProviderStatus mSecondaryProviderReportedStatus;

    public LocationTimeZoneAlgorithmStatus(
            @DetectionAlgorithmStatus int status,
            @ProviderStatus int primaryProviderStatus,
            @Nullable TimeZoneProviderStatus primaryProviderReportedStatus,
            @ProviderStatus int secondaryProviderStatus,
            @Nullable TimeZoneProviderStatus secondaryProviderReportedStatus) {

        mStatus = requireValidDetectionAlgorithmStatus(status);
        mPrimaryProviderStatus = requireValidProviderStatus(primaryProviderStatus);
        mPrimaryProviderReportedStatus = primaryProviderReportedStatus;
        mSecondaryProviderStatus = requireValidProviderStatus(secondaryProviderStatus);
        mSecondaryProviderReportedStatus = secondaryProviderReportedStatus;

        boolean primaryProviderHasReported = hasProviderReported(primaryProviderStatus);
        boolean primaryProviderReportedStatusPresent = primaryProviderReportedStatus != null;
        if (!primaryProviderHasReported && primaryProviderReportedStatusPresent) {
            throw new IllegalArgumentException(
                    "primaryProviderReportedStatus=" + primaryProviderReportedStatus
                            + ", primaryProviderStatus="
                            + providerStatusToString(primaryProviderStatus));
        }

        boolean secondaryProviderHasReported = hasProviderReported(secondaryProviderStatus);
        boolean secondaryProviderReportedStatusPresent = secondaryProviderReportedStatus != null;
        if (!secondaryProviderHasReported && secondaryProviderReportedStatusPresent) {
            throw new IllegalArgumentException(
                    "secondaryProviderReportedStatus=" + secondaryProviderReportedStatus
                            + ", secondaryProviderStatus="
                            + providerStatusToString(secondaryProviderStatus));
        }

        // If the algorithm isn't running, providers can't report.
        if (status != DETECTION_ALGORITHM_STATUS_RUNNING
                && (primaryProviderHasReported || secondaryProviderHasReported)) {
            throw new IllegalArgumentException(
                    "algorithmStatus=" + detectionAlgorithmStatusToString(status)
                            + ", primaryProviderReportedStatus=" + primaryProviderReportedStatus
                            + ", secondaryProviderReportedStatus="
                            + secondaryProviderReportedStatus);
        }
    }

    /**
     * Returns the status value of the detection algorithm.
     */
    public @DetectionAlgorithmStatus int getStatus() {
        return mStatus;
    }

    /**
     * Returns the status of the primary location time zone provider as categorized by the detection
     * algorithm.
     */
    public @ProviderStatus int getPrimaryProviderStatus() {
        return mPrimaryProviderStatus;
    }

    /**
     * Returns the status of the primary location time zone provider as reported by the provider
     * itself. Can be {@code null} when the provider hasn't reported, or omitted when it has.
     */
    @Nullable
    public TimeZoneProviderStatus getPrimaryProviderReportedStatus() {
        return mPrimaryProviderReportedStatus;
    }

    /**
     * Returns the status of the secondary location time zone provider as categorized by the
     * detection algorithm.
     */
    public @ProviderStatus int getSecondaryProviderStatus() {
        return mSecondaryProviderStatus;
    }

    /**
     * Returns the status of the secondary location time zone provider as reported by the provider
     * itself. Can be {@code null} when the provider hasn't reported, or omitted when it has.
     */
    @Nullable
    public TimeZoneProviderStatus getSecondaryProviderReportedStatus() {
        return mSecondaryProviderReportedStatus;
    }

    @Override
    public String toString() {
        return "LocationTimeZoneAlgorithmStatus{"
                + "mAlgorithmStatus=" + detectionAlgorithmStatusToString(mStatus)
                + ", mPrimaryProviderStatus=" + providerStatusToString(mPrimaryProviderStatus)
                + ", mPrimaryProviderReportedStatus=" + mPrimaryProviderReportedStatus
                + ", mSecondaryProviderStatus=" + providerStatusToString(mSecondaryProviderStatus)
                + ", mSecondaryProviderReportedStatus=" + mSecondaryProviderReportedStatus
                + '}';
    }

    /**
     * Parses a {@link LocationTimeZoneAlgorithmStatus} from a toString() string for manual
     * command-line testing.
     */
    @NonNull
    public static LocationTimeZoneAlgorithmStatus parseCommandlineArg(@NonNull String arg) {
        // Note: "}" has to be escaped on Android with "\\}" because the regexp library is not based
        // on OpenJDK code.
        Pattern pattern = Pattern.compile("LocationTimeZoneAlgorithmStatus\\{"
                + "mAlgorithmStatus=(.+)"
                + ", mPrimaryProviderStatus=([^,]+)"
                + ", mPrimaryProviderReportedStatus=(null|TimeZoneProviderStatus\\{[^}]+\\})"
                + ", mSecondaryProviderStatus=([^,]+)"
                + ", mSecondaryProviderReportedStatus=(null|TimeZoneProviderStatus\\{[^}]+\\})"
                + "\\}"
        );
        Matcher matcher = pattern.matcher(arg);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unable to parse algorithm status arg: " + arg);
        }
        @DetectionAlgorithmStatus int algorithmStatus =
                detectionAlgorithmStatusFromString(matcher.group(1));
        @ProviderStatus int primaryProviderStatus = providerStatusFromString(matcher.group(2));
        TimeZoneProviderStatus primaryProviderReportedStatus =
                parseTimeZoneProviderStatusOrNull(matcher.group(3));
        @ProviderStatus int secondaryProviderStatus = providerStatusFromString(matcher.group(4));
        TimeZoneProviderStatus secondaryProviderReportedStatus =
                parseTimeZoneProviderStatusOrNull(matcher.group(5));
        return new LocationTimeZoneAlgorithmStatus(
                algorithmStatus, primaryProviderStatus, primaryProviderReportedStatus,
                secondaryProviderStatus, secondaryProviderReportedStatus);
    }

    @Nullable
    private static TimeZoneProviderStatus parseTimeZoneProviderStatusOrNull(
            String providerReportedStatusString) {
        TimeZoneProviderStatus providerReportedStatus;
        if ("null".equals(providerReportedStatusString)) {
            providerReportedStatus = null;
        } else {
            providerReportedStatus =
                    TimeZoneProviderStatus.parseProviderStatus(providerReportedStatusString);
        }
        return providerReportedStatus;
    }

    @NonNull
    public static final Creator<LocationTimeZoneAlgorithmStatus> CREATOR = new Creator<>() {
        @Override
        public LocationTimeZoneAlgorithmStatus createFromParcel(Parcel in) {
            @DetectionAlgorithmStatus int algorithmStatus = in.readInt();
            @ProviderStatus int primaryProviderStatus = in.readInt();
            TimeZoneProviderStatus primaryProviderReportedStatus =
                    in.readParcelable(getClass().getClassLoader(), TimeZoneProviderStatus.class);
            @ProviderStatus int secondaryProviderStatus = in.readInt();
            TimeZoneProviderStatus secondaryProviderReportedStatus =
                    in.readParcelable(getClass().getClassLoader(), TimeZoneProviderStatus.class);
            return new LocationTimeZoneAlgorithmStatus(
                    algorithmStatus, primaryProviderStatus, primaryProviderReportedStatus,
                    secondaryProviderStatus, secondaryProviderReportedStatus);
        }

        @Override
        public LocationTimeZoneAlgorithmStatus[] newArray(int size) {
            return new LocationTimeZoneAlgorithmStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mStatus);
        parcel.writeInt(mPrimaryProviderStatus);
        parcel.writeParcelable(mPrimaryProviderReportedStatus, flags);
        parcel.writeInt(mSecondaryProviderStatus);
        parcel.writeParcelable(mSecondaryProviderReportedStatus, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneAlgorithmStatus that = (LocationTimeZoneAlgorithmStatus) o;
        return mStatus == that.mStatus
                && mPrimaryProviderStatus == that.mPrimaryProviderStatus
                && Objects.equals(
                        mPrimaryProviderReportedStatus, that.mPrimaryProviderReportedStatus)
                && mSecondaryProviderStatus == that.mSecondaryProviderStatus
                && Objects.equals(
                        mSecondaryProviderReportedStatus, that.mSecondaryProviderReportedStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus,
                mPrimaryProviderStatus, mPrimaryProviderReportedStatus,
                mSecondaryProviderStatus, mSecondaryProviderReportedStatus);
    }

    /**
     * Returns {@code true} if the algorithm status could allow the time zone detector to enter
     * telephony fallback mode.
     */
    public boolean couldEnableTelephonyFallback() {
        if (mStatus == DETECTION_ALGORITHM_STATUS_UNKNOWN
                || mStatus == DETECTION_ALGORITHM_STATUS_NOT_RUNNING
                || mStatus == DETECTION_ALGORITHM_STATUS_NOT_SUPPORTED) {
            // This method is not expected to be called on objects with these statuses. Fallback
            // should not be enabled if it is.
            return false;
        }

        // mStatus == DETECTOR_STATUS_RUNNING.

        boolean primarySuggestsFallback = false;
        if (mPrimaryProviderStatus == PROVIDER_STATUS_NOT_PRESENT) {
            primarySuggestsFallback = true;
        } else if (mPrimaryProviderStatus == PROVIDER_STATUS_IS_UNCERTAIN
                && mPrimaryProviderReportedStatus != null) {
            primarySuggestsFallback = mPrimaryProviderReportedStatus.couldEnableTelephonyFallback();
        }

        boolean secondarySuggestsFallback = false;
        if (mSecondaryProviderStatus == PROVIDER_STATUS_NOT_PRESENT) {
            secondarySuggestsFallback = true;
        } else if (mSecondaryProviderStatus == PROVIDER_STATUS_IS_UNCERTAIN
                && mSecondaryProviderReportedStatus != null) {
            secondarySuggestsFallback =
                    mSecondaryProviderReportedStatus.couldEnableTelephonyFallback();
        }
        return primarySuggestsFallback && secondarySuggestsFallback;
    }

    /** @hide */
    @VisibleForTesting
    @NonNull
    public static String providerStatusToString(@ProviderStatus int providerStatus) {
        switch (providerStatus) {
            case PROVIDER_STATUS_NOT_PRESENT:
                return "NOT_PRESENT";
            case PROVIDER_STATUS_NOT_READY:
                return "NOT_READY";
            case PROVIDER_STATUS_IS_CERTAIN:
                return "IS_CERTAIN";
            case PROVIDER_STATUS_IS_UNCERTAIN:
                return "IS_UNCERTAIN";
            default:
                throw new IllegalArgumentException("Unknown status: " + providerStatus);
        }
    }

    /** @hide */
    @VisibleForTesting public static @ProviderStatus int providerStatusFromString(
            @Nullable String providerStatusString) {
        if (TextUtils.isEmpty(providerStatusString)) {
            throw new IllegalArgumentException("Empty status: " + providerStatusString);
        }

        switch (providerStatusString) {
            case "NOT_PRESENT":
                return PROVIDER_STATUS_NOT_PRESENT;
            case "NOT_READY":
                return PROVIDER_STATUS_NOT_READY;
            case "IS_CERTAIN":
                return PROVIDER_STATUS_IS_CERTAIN;
            case "IS_UNCERTAIN":
                return PROVIDER_STATUS_IS_UNCERTAIN;
            default:
                throw new IllegalArgumentException("Unknown status: " + providerStatusString);
        }
    }

    private static boolean hasProviderReported(@ProviderStatus int providerStatus) {
        return providerStatus == PROVIDER_STATUS_IS_CERTAIN
                || providerStatus == PROVIDER_STATUS_IS_UNCERTAIN;
    }

    /** @hide */
    @VisibleForTesting public static @ProviderStatus int requireValidProviderStatus(
            @ProviderStatus int providerStatus) {
        if (providerStatus < PROVIDER_STATUS_NOT_PRESENT
                || providerStatus > PROVIDER_STATUS_IS_UNCERTAIN) {
            throw new IllegalArgumentException(
                    "Invalid provider status: " + providerStatus);
        }
        return providerStatus;
    }
}
