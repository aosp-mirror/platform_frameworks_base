/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Defines the threshold value of the signal strength.
 */
public final class SignalThresholdInfo implements Parcelable {

    /**
     * Unknown signal measurement type.
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_UNKNOWN = 0;

    /**
     * Received Signal Strength Indication.
     * Range: -113 dBm and -51 dBm
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#GERAN},
     *           {@link AccessNetworkConstants.AccessNetworkType#CDMA2000}
     * Reference: 3GPP TS 27.007 section 8.5.
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_RSSI = 1;

    /**
     * Received Signal Code Power.
     * Range: -120 dBm to -25 dBm;
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#UTRAN}
     * Reference: 3GPP TS 25.123, section 9.1.1.1
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_RSCP = 2;

    /**
     * Reference Signal Received Power.
     * Range: -140 dBm to -44 dBm;
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
     * Reference: 3GPP TS 36.133 9.1.4
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_RSRP = 3;

    /**
     * Reference Signal Received Quality
     * Range: -34 dB to 3 dB;
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
     * Reference: 3GPP TS 36.133 9.1.7
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_RSRQ = 4;

    /**
     * Reference Signal Signal to Noise Ratio
     * Range: -20 dB to 30 dB;
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#EUTRAN}
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_RSSNR = 5;

    /**
     * 5G SS reference signal received power.
     * Range: -140 dBm to -44 dBm.
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
     * Reference: 3GPP TS 38.215.
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_SSRSRP = 6;

    /**
     * 5G SS reference signal received quality.
     * Range: -43 dB to 20 dB.
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
     * Reference: 3GPP TS 38.133 section 10.1.11.1.
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_SSRSRQ = 7;

    /**
     * 5G SS signal-to-noise and interference ratio.
     * Range: -23 dB to 40 dB
     * Used RAN: {@link AccessNetworkConstants.AccessNetworkType#NGRAN}
     * Reference: 3GPP TS 38.215 section 5.1.*, 3GPP TS 38.133 section 10.1.16.1.
     */
    public static final int SIGNAL_MEASUREMENT_TYPE_SSSINR = 8;

    /** @hide */
    @IntDef(prefix = {"SIGNAL_MEASUREMENT_TYPE_"}, value = {
            SIGNAL_MEASUREMENT_TYPE_UNKNOWN,
            SIGNAL_MEASUREMENT_TYPE_RSSI,
            SIGNAL_MEASUREMENT_TYPE_RSCP,
            SIGNAL_MEASUREMENT_TYPE_RSRP,
            SIGNAL_MEASUREMENT_TYPE_RSRQ,
            SIGNAL_MEASUREMENT_TYPE_RSSNR,
            SIGNAL_MEASUREMENT_TYPE_SSRSRP,
            SIGNAL_MEASUREMENT_TYPE_SSRSRQ,
            SIGNAL_MEASUREMENT_TYPE_SSSINR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignalMeasurementType {
    }

    @SignalMeasurementType
    private final int mSignalMeasurementType;

    /**
     * A hysteresis time in milliseconds to prevent flapping.
     * A value of 0 disables hysteresis
     */
    private final int mHysteresisMs;

    /**
     * An interval in dB defining the required magnitude change between reports.
     * hysteresisDb must be smaller than the smallest threshold delta.
     * An interval value of 0 disables hysteresis.
     */
    private final int mHysteresisDb;

    /**
     * List of threshold values.
     * Range and unit must reference specific SignalMeasurementType
     * The threshold values for which to apply criteria.
     * A vector size of 0 disables the use of thresholds for reporting.
     */
    private final int[] mThresholds;

    /**
     * {@code true} means modem must trigger the report based on the criteria;
     * {@code false} means modem must not trigger the report based on the criteria.
     */
    private final boolean mIsEnabled;

    /**
     * The radio access network type associated with the signal thresholds.
     */
    @AccessNetworkConstants.RadioAccessNetworkType
    private final int mRan;

    /**
     * Indicates the hysteresisMs is disabled.
     *
     * @hide
     */
    public static final int HYSTERESIS_MS_DISABLED = 0;

    /**
     * Indicates the hysteresisDb is disabled.
     *
     * @hide
     */
    public static final int HYSTERESIS_DB_DISABLED = 0;


    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSSI}.
     *
     * @hide
     */
    public static final int SIGNAL_RSSI_MIN_VALUE = -113;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSSI}.
     *
     * @hide
     */
    public static final int SIGNAL_RSSI_MAX_VALUE = -51;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSCP}.
     *
     * @hide
     */
    public static final int SIGNAL_RSCP_MIN_VALUE = -120;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSCP}.
     *
     * @hide
     */
    public static final int SIGNAL_RSCP_MAX_VALUE = -25;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSRP}.
     *
     * @hide
     */
    public static final int SIGNAL_RSRP_MIN_VALUE = -140;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSRP}.
     *
     * @hide
     */
    public static final int SIGNAL_RSRP_MAX_VALUE = -44;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSRQ}.
     *
     * @hide
     */
    public static final int SIGNAL_RSRQ_MIN_VALUE = -34;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSRQ}.
     *
     * @hide
     */
    public static final int SIGNAL_RSRQ_MAX_VALUE = 3;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSSNR}.
     *
     * @hide
     */
    public static final int SIGNAL_RSSNR_MIN_VALUE = -20;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_RSSNR}.
     *
     * @hide
     */
    public static final int SIGNAL_RSSNR_MAX_VALUE = 30;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSRSRP}.
     *
     * @hide
     */
    public static final int SIGNAL_SSRSRP_MIN_VALUE = -140;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSRSRP}.
     *
     * @hide
     */
    public static final int SIGNAL_SSRSRP_MAX_VALUE = -44;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSRSRQ}.
     *
     * @hide
     */
    public static final int SIGNAL_SSRSRQ_MIN_VALUE = -43;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSRSRQ}.
     *
     * @hide
     */
    public static final int SIGNAL_SSRSRQ_MAX_VALUE = 20;

    /**
     * Minimum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSSINR}.
     *
     * @hide
     */
    public static final int SIGNAL_SSSINR_MIN_VALUE = -23;

    /**
     * Maximum valid value for {@link #SIGNAL_MEASUREMENT_TYPE_SSSINR}.
     *
     * @hide
     */
    public static final int SIGNAL_SSSINR_MAX_VALUE = 40;

    /**
     * The minimum number of thresholds allowed in each SignalThresholdInfo.
     *
     * @hide
     */
    public static final int MINIMUM_NUMBER_OF_THRESHOLDS_ALLOWED = 1;

    /**
     * The maximum number of thresholds allowed in each SignalThresholdInfo.
     *
     * @hide
     */
    public static final int MAXIMUM_NUMBER_OF_THRESHOLDS_ALLOWED = 4;

    /**
     * Constructor
     *
     * @param ran               Radio Access Network type
     * @param signalMeasurementType Signal Measurement Type
     * @param hysteresisMs      hysteresisMs
     * @param hysteresisDb      hysteresisDb
     * @param thresholds        threshold value
     * @param isEnabled         isEnabled
     */
    private SignalThresholdInfo(@AccessNetworkConstants.RadioAccessNetworkType int ran,
            @SignalMeasurementType int signalMeasurementType, int hysteresisMs, int hysteresisDb,
            @NonNull int[] thresholds, boolean isEnabled) {
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        validateRanWithMeasurementType(ran, signalMeasurementType);
        validateThresholdRange(signalMeasurementType, thresholds);

        mRan = ran;
        mSignalMeasurementType = signalMeasurementType;
        mHysteresisMs = hysteresisMs < 0 ? HYSTERESIS_MS_DISABLED : hysteresisMs;
        mHysteresisDb = hysteresisDb < 0 ? HYSTERESIS_DB_DISABLED : hysteresisDb;
        mThresholds = thresholds;
        mIsEnabled = isEnabled;
    }

    /**
     * Builder class to create {@link SignalThresholdInfo} objects.
     */
    public static final class Builder {
        private int mRan = AccessNetworkConstants.AccessNetworkType.UNKNOWN;
        private int mSignalMeasurementType = SIGNAL_MEASUREMENT_TYPE_UNKNOWN;
        private int mHysteresisMs = HYSTERESIS_MS_DISABLED;
        private int mHysteresisDb = HYSTERESIS_DB_DISABLED;
        private int[] mThresholds = null;
        private boolean mIsEnabled = false;

        /**
         * Set the radio access network type for the builder instance.
         *
         * @param ran The radio access network type
         * @return the builder to facilitate the chaining
         */
        public @NonNull Builder setRadioAccessNetworkType(
                @AccessNetworkConstants.RadioAccessNetworkType int ran) {
            mRan = ran;
            return this;
        }

        /**
         * Set the signal measurement type for the builder instance.
         *
         * @param signalMeasurementType The signal measurement type
         * @return the builder to facilitate the chaining
         */
        public @NonNull Builder setSignalMeasurementType(
                @SignalMeasurementType int signalMeasurementType) {
            mSignalMeasurementType = signalMeasurementType;
            return this;
        }

        /**
         * Set the hysteresis time in milliseconds to prevent flapping. A value of 0 disables
         * hysteresis.
         *
         * @param hysteresisMs the hysteresis time in milliseconds
         * @return the builder to facilitate the chaining
         * @hide
         */
        public @NonNull Builder setHysteresisMs(int hysteresisMs) {
            mHysteresisMs = hysteresisMs;
            return this;
        }

        /**
         * Set the interval in dB defining the required magnitude change between reports. A value of
         * zero disabled dB-based hysteresis restrictions.
         *
         * @param hysteresisDb the interval in dB
         * @return the builder to facilitate the chaining
         * @hide
         */
        public @NonNull Builder setHysteresisDb(int hysteresisDb) {
            mHysteresisDb = hysteresisDb;
            return this;
        }

        /**
         * Set the signal strength thresholds of the corresponding signal measurement type.
         *
         * The range and unit must reference specific SignalMeasurementType. The length of the
         * thresholds should between the numbers return from
         * {@link #getMinimumNumberOfThresholdsAllowed()} and
         * {@link #getMaximumNumberOfThresholdsAllowed()}. An IllegalArgumentException will throw
         * otherwise.
         *
         * @param thresholds array of integer as the signal threshold values
         * @return the builder to facilitate the chaining
         *
         * @see #SIGNAL_MEASUREMENT_TYPE_RSSI
         * @see #SIGNAL_MEASUREMENT_TYPE_RSCP
         * @see #SIGNAL_MEASUREMENT_TYPE_RSRP
         * @see #SIGNAL_MEASUREMENT_TYPE_RSRQ
         * @see #SIGNAL_MEASUREMENT_TYPE_RSSNR
         * @see #SIGNAL_MEASUREMENT_TYPE_SSRSRP
         * @see #SIGNAL_MEASUREMENT_TYPE_SSRSRQ
         * @see #SIGNAL_MEASUREMENT_TYPE_SSSINR
         * @see #getThresholds() for more details on signal strength thresholds
         */
        public @NonNull Builder setThresholds(@NonNull int[] thresholds) {
            return setThresholds(thresholds, false /*isSystem*/);
        }

        /**
         * Set the signal strength thresholds for the corresponding signal measurement type.
         *
         * @param thresholds array of integer as the signal threshold values
         * @param isSystem true is the caller is system which does not have restrictions on
         *        the length of thresholds array.
         * @return the builder to facilitate the chaining
         *
         * @hide
         */
        public @NonNull Builder setThresholds(@NonNull int[] thresholds, boolean isSystem) {
            Objects.requireNonNull(thresholds, "thresholds must not be null");
            if (!isSystem && (thresholds.length < MINIMUM_NUMBER_OF_THRESHOLDS_ALLOWED
                    || thresholds.length > MAXIMUM_NUMBER_OF_THRESHOLDS_ALLOWED)) {
                throw new IllegalArgumentException(
                        "thresholds length must between " + MINIMUM_NUMBER_OF_THRESHOLDS_ALLOWED
                                + " and " + MAXIMUM_NUMBER_OF_THRESHOLDS_ALLOWED);
            }
            mThresholds = thresholds.clone();
            Arrays.sort(mThresholds);
            return this;
        }


        /**
         * Set if the modem should trigger the report based on the criteria.
         *
         * @param isEnabled true if the modem should trigger the report based on the criteria
         * @return the builder to facilitate the chaining
         * @hide
         */
        public @NonNull Builder setIsEnabled(boolean isEnabled) {
            mIsEnabled = isEnabled;
            return this;
        }

        /**
         * Build {@link SignalThresholdInfo} object.
         *
         * @return the SignalThresholdInfo object build out
         *
         * @throws IllegalArgumentException if the signal measurement type is invalid, any value in
         * the thresholds is out of range, or the RAN is not allowed to set with the signal
         * measurement type
         */
        public @NonNull SignalThresholdInfo build() {
            return new SignalThresholdInfo(mRan, mSignalMeasurementType, mHysteresisMs,
                    mHysteresisDb, mThresholds, mIsEnabled);
        }
    }

    /**
     * Get the radio access network type.
     *
     * @return radio access network type
     */
    public @AccessNetworkConstants.RadioAccessNetworkType int getRadioAccessNetworkType() {
        return mRan;
    }

    /**
     * Get the signal measurement type.
     *
     * @return the SignalMeasurementType value
     */
    public @SignalMeasurementType int getSignalMeasurementType() {
        return mSignalMeasurementType;
    }

    /** @hide */
    public int getHysteresisMs() {
        return mHysteresisMs;
    }

    /** @hide */
    public int getHysteresisDb() {
        return mHysteresisDb;
    }

    /** @hide */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Get the signal strength thresholds.
     *
     * Signal strength thresholds are a list of integer used for suggesting signal level and signal
     * reporting criteria. The range and unit must reference specific SignalMeasurementType.
     *
     * Please refer to https://source.android.com/devices/tech/connect/signal-strength on how signal
     * strength thresholds are used for signal strength reporting.
     *
     * @return array of integer of the signal thresholds
     *
     * @see #SIGNAL_MEASUREMENT_TYPE_RSSI
     * @see #SIGNAL_MEASUREMENT_TYPE_RSCP
     * @see #SIGNAL_MEASUREMENT_TYPE_RSRP
     * @see #SIGNAL_MEASUREMENT_TYPE_RSRQ
     * @see #SIGNAL_MEASUREMENT_TYPE_RSSNR
     * @see #SIGNAL_MEASUREMENT_TYPE_SSRSRP
     * @see #SIGNAL_MEASUREMENT_TYPE_SSRSRQ
     * @see #SIGNAL_MEASUREMENT_TYPE_SSSINR
     */
    public @NonNull int[] getThresholds() {
        return mThresholds.clone();
    }

    /**
     * Get the minimum number of thresholds allowed in each SignalThresholdInfo.
     *
     * @return the minimum number of thresholds allowed
     */
    public static int getMinimumNumberOfThresholdsAllowed() {
        return MINIMUM_NUMBER_OF_THRESHOLDS_ALLOWED;
    }

    /**
     * Get the maximum number of threshold allowed in each SignalThresholdInfo.
     *
     * @return the maximum number of thresholds allowed
     */
    public static int getMaximumNumberOfThresholdsAllowed() {
        return MAXIMUM_NUMBER_OF_THRESHOLDS_ALLOWED;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mRan);
        out.writeInt(mSignalMeasurementType);
        out.writeInt(mHysteresisMs);
        out.writeInt(mHysteresisDb);
        out.writeIntArray(mThresholds);
        out.writeBoolean(mIsEnabled);
    }

    private SignalThresholdInfo(Parcel in) {
        mRan = in.readInt();
        mSignalMeasurementType = in.readInt();
        mHysteresisMs = in.readInt();
        mHysteresisDb = in.readInt();
        mThresholds = in.createIntArray();
        mIsEnabled = in.readBoolean();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof SignalThresholdInfo)) {
            return false;
        }

        SignalThresholdInfo other = (SignalThresholdInfo) o;
        return mRan == other.mRan
                && mSignalMeasurementType == other.mSignalMeasurementType
                && mHysteresisMs == other.mHysteresisMs
                && mHysteresisDb == other.mHysteresisDb
                && Arrays.equals(mThresholds, other.mThresholds)
                && mIsEnabled == other.mIsEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRan, mSignalMeasurementType, mHysteresisMs, mHysteresisDb, mThresholds,
                mIsEnabled);
    }

    public static final @NonNull Parcelable.Creator<SignalThresholdInfo> CREATOR =
            new Parcelable.Creator<SignalThresholdInfo>() {
                @Override
                public SignalThresholdInfo createFromParcel(Parcel in) {
                    return new SignalThresholdInfo(in);
                }

                @Override
                public SignalThresholdInfo[] newArray(int size) {
                    return new SignalThresholdInfo[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("SignalThresholdInfo{")
                .append("mRan=").append(mRan)
                .append(" mSignalMeasurementType=").append(mSignalMeasurementType)
                .append(" mHysteresisMs=").append(mHysteresisMs)
                .append(" mHysteresisDb=").append(mHysteresisDb)
                .append(" mThresholds=").append(Arrays.toString(mThresholds))
                .append(" mIsEnabled=").append(mIsEnabled)
                .append("}").toString();
    }

    /**
     * Return true if signal measurement type is valid and the threshold value is in range.
     */
    private static boolean isValidThreshold(@SignalMeasurementType int type, int threshold) {
        switch (type) {
            case SIGNAL_MEASUREMENT_TYPE_RSSI:
                return threshold >= SIGNAL_RSSI_MIN_VALUE && threshold <= SIGNAL_RSSI_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_RSCP:
                return threshold >= SIGNAL_RSCP_MIN_VALUE && threshold <= SIGNAL_RSCP_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_RSRP:
                return threshold >= SIGNAL_RSRP_MIN_VALUE && threshold <= SIGNAL_RSRP_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_RSRQ:
                return threshold >= SIGNAL_RSRQ_MIN_VALUE && threshold <= SIGNAL_RSRQ_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_RSSNR:
                return threshold >= SIGNAL_RSSNR_MIN_VALUE && threshold <= SIGNAL_RSSNR_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_SSRSRP:
                return threshold >= SIGNAL_SSRSRP_MIN_VALUE && threshold <= SIGNAL_SSRSRP_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_SSRSRQ:
                return threshold >= SIGNAL_SSRSRQ_MIN_VALUE && threshold <= SIGNAL_SSRSRQ_MAX_VALUE;
            case SIGNAL_MEASUREMENT_TYPE_SSSINR:
                return threshold >= SIGNAL_SSSINR_MIN_VALUE && threshold <= SIGNAL_SSSINR_MAX_VALUE;
            default:
                return false;
        }
    }

    /**
     * Return true if the radio access type is allowed to set with the measurement type.
     */
    private static boolean isValidRanWithMeasurementType(
            @AccessNetworkConstants.RadioAccessNetworkType int ran,
            @SignalMeasurementType int type) {
        switch (type) {
            case SIGNAL_MEASUREMENT_TYPE_RSSI:
                return ran == AccessNetworkConstants.AccessNetworkType.GERAN
                        || ran == AccessNetworkConstants.AccessNetworkType.CDMA2000;
            case SIGNAL_MEASUREMENT_TYPE_RSCP:
                return ran == AccessNetworkConstants.AccessNetworkType.UTRAN;
            case SIGNAL_MEASUREMENT_TYPE_RSRP:
            case SIGNAL_MEASUREMENT_TYPE_RSRQ:
            case SIGNAL_MEASUREMENT_TYPE_RSSNR:
                return ran == AccessNetworkConstants.AccessNetworkType.EUTRAN;
            case SIGNAL_MEASUREMENT_TYPE_SSRSRP:
            case SIGNAL_MEASUREMENT_TYPE_SSRSRQ:
            case SIGNAL_MEASUREMENT_TYPE_SSSINR:
                return ran == AccessNetworkConstants.AccessNetworkType.NGRAN;
            default:
                return false;
        }
    }

    private void validateRanWithMeasurementType(
            @AccessNetworkConstants.RadioAccessNetworkType int ran,
            @SignalMeasurementType int signalMeasurement) {
        if (!isValidRanWithMeasurementType(ran, signalMeasurement)) {
            throw new IllegalArgumentException(
                    "invalid RAN: " + ran + " with signal measurement type: " + signalMeasurement);
        }
    }

    private void validateThresholdRange(@SignalMeasurementType int signalMeasurement,
            int[] thresholds) {
        for (int threshold : thresholds) {
            if (!isValidThreshold(signalMeasurement, threshold)) {
                throw new IllegalArgumentException(
                        "invalid signal measurement type: " + signalMeasurement
                                + " with threshold: " + threshold);
            }
        }
    }
}
