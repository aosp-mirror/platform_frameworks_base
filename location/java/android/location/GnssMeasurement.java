/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.location;

import android.annotation.TestApi;
import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class representing a GNSS satellite measurement, containing raw and computed information.
 */
public final class GnssMeasurement implements Parcelable {
    private int mFlags;
    private int mSvid;
    private int mConstellationType;
    private double mTimeOffsetNanos;
    private int mState;
    private long mReceivedSvTimeNanos;
    private long mReceivedSvTimeUncertaintyNanos;
    private double mCn0DbHz;
    private double mPseudorangeRateMetersPerSecond;
    private double mPseudorangeRateUncertaintyMetersPerSecond;
    private int mAccumulatedDeltaRangeState;
    private double mAccumulatedDeltaRangeMeters;
    private double mAccumulatedDeltaRangeUncertaintyMeters;
    private float mCarrierFrequencyHz;
    private long mCarrierCycles;
    private double mCarrierPhase;
    private double mCarrierPhaseUncertainty;
    private int mMultipathIndicator;
    private double mSnrInDb;
    private boolean mPseudorangeRateCorrected;

    // The following enumerations must be in sync with the values declared in gps.h

    private static final int HAS_NO_FLAGS = 0;
    private static final int HAS_SNR = (1<<0);
    private static final int HAS_CARRIER_FREQUENCY = (1<<9);
    private static final int HAS_CARRIER_CYCLES = (1<<10);
    private static final int HAS_CARRIER_PHASE = (1<<11);
    private static final int HAS_CARRIER_PHASE_UNCERTAINTY = (1<<12);

    /** The status of multipath. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MULTIPATH_INDICATOR_UNKNOWN, MULTIPATH_INDICATOR_DETECTED,
        MULTIPATH_INDICATOR_NOT_USED})
    public @interface MultipathIndicator {}

    /**
     * The indicator is not available or it is unknown.
     */
    public static final int MULTIPATH_INDICATOR_UNKNOWN = 0;

    /**
     * The measurement has been indicated to use multi-path.
     */
    public static final int MULTIPATH_INDICATOR_DETECTED = 1;

    /**
     * The measurement has been indicated not tu use multi-path.
     */
    public static final int MULTIPATH_INDICATOR_NOT_USED = 2;

    /**
     * The state of GNSS receiver the measurement is invalid or unknown.
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * The state of the GNSS receiver is ranging code lock.
     */
    public static final int STATE_CODE_LOCK = (1<<0);

    /**
     * The state of the GNSS receiver is in bit sync.
     */
    public static final int STATE_BIT_SYNC = (1<<1);

    /**
     *The state of the GNSS receiver is in sub-frame sync.
     */
    public static final int STATE_SUBFRAME_SYNC = (1<<2);

    /**
     * The state of the GNSS receiver has TOW decoded.
     */
    public static final int STATE_TOW_DECODED = (1<<3);

    /**
     * The state of the GNSS receiver contains millisecond ambiguity.
     */
    public static final int STATE_MSEC_AMBIGUOUS = (1<<4);

    /**
     * All the GNSS receiver state flags.
     */
    private static final int STATE_ALL = STATE_CODE_LOCK | STATE_BIT_SYNC | STATE_SUBFRAME_SYNC
            | STATE_TOW_DECODED | STATE_MSEC_AMBIGUOUS;

    /**
     * The state of the 'Accumulated Delta Range' is invalid or unknown.
     */
    public static final int ADR_STATE_UNKNOWN = 0;

    /**
     * The state of the 'Accumulated Delta Range' is valid.
     */
    public static final int ADR_STATE_VALID = (1<<0);

    /**
     * The state of the 'Accumulated Delta Range' has detected a reset.
     */
    public static final int ADR_STATE_RESET = (1<<1);

    /**
     * The state of the 'Accumulated Delta Range' has a cycle slip detected.
     */
    public static final int ADR_STATE_CYCLE_SLIP = (1<<2);

    /**
     * All the 'Accumulated Delta Range' flags.
     */
    private static final int ADR_ALL = ADR_STATE_VALID | ADR_STATE_RESET | ADR_STATE_CYCLE_SLIP;

    // End enumerations in sync with gps.h

    /**
     * @hide
     */
    @TestApi
    public GnssMeasurement() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     * @hide
     */
    @TestApi
    public void set(GnssMeasurement measurement) {
        mFlags = measurement.mFlags;
        mSvid = measurement.mSvid;
        mConstellationType = measurement.mConstellationType;
        mTimeOffsetNanos = measurement.mTimeOffsetNanos;
        mState = measurement.mState;
        mReceivedSvTimeNanos = measurement.mReceivedSvTimeNanos;
        mReceivedSvTimeUncertaintyNanos = measurement.mReceivedSvTimeUncertaintyNanos;
        mCn0DbHz = measurement.mCn0DbHz;
        mPseudorangeRateMetersPerSecond = measurement.mPseudorangeRateMetersPerSecond;
        mPseudorangeRateUncertaintyMetersPerSecond =
                measurement.mPseudorangeRateUncertaintyMetersPerSecond;
        mAccumulatedDeltaRangeState = measurement.mAccumulatedDeltaRangeState;
        mAccumulatedDeltaRangeMeters = measurement.mAccumulatedDeltaRangeMeters;
        mAccumulatedDeltaRangeUncertaintyMeters =
                measurement.mAccumulatedDeltaRangeUncertaintyMeters;
        mCarrierFrequencyHz = measurement.mCarrierFrequencyHz;
        mCarrierCycles = measurement.mCarrierCycles;
        mCarrierPhase = measurement.mCarrierPhase;
        mCarrierPhaseUncertainty = measurement.mCarrierPhaseUncertainty;
        mMultipathIndicator = measurement.mMultipathIndicator;
        mSnrInDb = measurement.mSnrInDb;
    }

    /**
     * Resets all the contents to its original state.
     * @hide
     */
    @TestApi
    public void reset() {
        initialize();
    }

    /**
     * Gets the Pseudo-random number (PRN).
     * Range: [1, 32]
     */
    public int getSvid() {
        return mSvid;
    }

    /**
     * Sets the Pseud-random number (PRN).
     * @hide
     */
    @TestApi
    public void setSvid(int value) {
        mSvid = value;
    }

    /**
     * Getst the constellation type.
     */
    @GnssStatus.ConstellationType
    public int getConstellationType() {
        return mConstellationType;
    }

    /**
     * Sets the constellation type.
     * @hide
     */
    @TestApi
    public void setConstellationType(@GnssStatus.ConstellationType int value) {
        mConstellationType = value;
    }

    /**
     * Gets the time offset at which the measurement was taken in nanoseconds.
     *
     * The reference receiver's time from which this is offset is specified by
     * {@link GnssClock#getTimeNanos()}.
     *
     * The sign of this value is given by the following equation:
     *      measurement time = time_ns + time_offset_ns
     *
     * The value provides an individual time-stamp for the measurement, and allows sub-nanosecond
     * accuracy.
     */
    public double getTimeOffsetNanos() {
        return mTimeOffsetNanos;
    }

    /**
     * Sets the time offset at which the measurement was taken in nanoseconds.
     * @hide
     */
    @TestApi
    public void setTimeOffsetNanos(double value) {
        mTimeOffsetNanos = value;
    }

    /**
     * Gets per-satellite sync state.
     * It represents the current sync state for the associated satellite.
     *
     * This value helps interpret {@link #getReceivedSvTimeNanos()}.
     */
    public int getState() {
        return mState;
    }

    /**
     * Sets the sync state.
     * @hide
     */
    @TestApi
    public void setState(int value) {
        mState = value;
    }

    /**
     * Gets a string representation of the 'sync state'.
     * For internal and logging use only.
     */
    private String getStateString() {
        if (mState == STATE_UNKNOWN) {
            return "Unknown";
        }
        StringBuilder builder = new StringBuilder();
        if ((mState & STATE_CODE_LOCK) == STATE_CODE_LOCK) {
            builder.append("CodeLock|");
        }
        if ((mState & STATE_BIT_SYNC) == STATE_BIT_SYNC) {
            builder.append("BitSync|");
        }
        if ((mState & STATE_SUBFRAME_SYNC) == STATE_SUBFRAME_SYNC) {
            builder.append("SubframeSync|");
        }
        if ((mState & STATE_TOW_DECODED) == STATE_TOW_DECODED) {
            builder.append("TowDecoded|");
        }
        if ((mState & STATE_MSEC_AMBIGUOUS) == STATE_MSEC_AMBIGUOUS) {
            builder.append("MsecAmbiguous");
        }
        int remainingStates = mState & ~STATE_ALL;
        if (remainingStates > 0) {
            builder.append("Other(");
            builder.append(Integer.toBinaryString(remainingStates));
            builder.append(")|");
        }
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }

    /**
     * Gets the received GNSS satellite time, at the measurement time, in nanoseconds.
     *
     * For GPS &amp; QZSS, this is:
     *   Received GPS Time-of-Week at the measurement time, in nanoseconds.
     *   The value is relative to the beginning of the current GPS week.
     *
     *   Given the highest sync state that can be achieved, per each satellite, valid range
     *   for this field can be:
     *     Searching       : [ 0       ]   : STATE_UNKNOWN
     *     C/A code lock   : [ 0   1ms ]   : STATE_CODE_LOCK is set
     *     Bit sync        : [ 0  20ms ]   : STATE_BIT_SYNC is set
     *     Subframe sync   : [ 0    6s ]   : STATE_SUBFRAME_SYNC is set
     *     TOW decoded     : [ 0 1week ]   : STATE_TOW_DECODED is set
     *
     *   Note well: if there is any ambiguity in integer millisecond,
     *   STATE_MSEC_AMBIGUOUS should be set accordingly, in the 'state' field.
     *
     *   This value must be populated if 'state' != STATE_UNKNOWN.
     *
     * For Glonass, this is:
     *   Received Glonass time of day, at the measurement time in nanoseconds.
     *
     *   Given the highest sync state that can be achieved, per each satellite, valid range for
     *   this field can be:
     *     Searching       : [ 0       ]   : STATE_UNKNOWN
     *     C/A code lock   : [ 0   1ms ]   : STATE_CODE_LOCK is set
     *    Symbol sync    : [ 0  10ms ]   : STATE_SYMBOL_SYNC is set
     *    Bit sync       : [ 0  20ms ]   : STATE_BIT_SYNC is set
     *     String sync     : [ 0    2s ]   :  STATE_GLO_STRING_SYNC is set
     *    Time of day      : [ 0  1day ]   : STATE_GLO_TOD_DECODED is set
     *
     * For Beidou, this is:
     *   Received Beidou time of week, at the measurement time in nanoseconds.
     *
     *   Given the highest sync state that can be achieved, per each satellite, valid range for
     *   this field can be:
     *     Searching       : [ 0       ]   : STATE_UNKNOWN
     *     C/A code lock   : [ 0   1ms ]   : STATE_CODE_LOCK is set
     *     Bit sync (D2)   : [ 0   2ms ]   : STATE_BDS_D2_BIT_SYNC is set
     *     Bit sync (D1)   : [ 0  20ms ]   : STATE_BIT_SYNC is set
     *     Subframe (D2)   : [ 0  0.6s ]   : STATE_BDS_D2_SUBFRAME_SYNC is set
     *     Subframe (D1)   : [ 0    6s ]   : STATE_SUBFRAME_SYNC is set
     *     Time of week    : [ 0 1week ]   : STATE_TOW_DECODED is set
     *
     * For Galileo, this is:
     *   Received Galileo time of week, at the measurement time in nanoseconds.
     *
     *     E1BC code lock  : [ 0   4ms ]   : STATE_GAL_E1BC_CODE_LOCK is set
     *     E1C 2nd code lock : [ 0   100ms ]   : STATE_GAL_E1C_2ND_CODE_LOCK is set
     *
     *     E1B page        : [ 0    2s ]   : STATE_GAL_E1B_PAGE_SYNC is set
     *     Time of week    : [ 0 1week ]   : STATE_GAL_TOW_DECODED is set
     *
     *   For SBAS, this is:
     *     Received SBAS time, at the measurement time in nanoseconds.
     *
     *   Given the highest sync state that can be achieved, per each satellite, valid range for
     *   this field can be:
     *     Searching       : [ 0       ]   : STATE_UNKNOWN
     *     C/A code lock   : [ 0   1ms ]   : STATE_CODE_LOCK is set
     *     Symbol sync     : [ 0   2ms ]   : STATE_SYMBOL_SYNC is set
     *     Message         : [ 0    1s ]   : STATE_SBAS_SYNC is set
     */
    public long getReceivedSvTimeNanos() {
        return mReceivedSvTimeNanos;
    }

    /**
     * Sets the received GNSS time in nanoseconds.
     * @hide
     */
    @TestApi
    public void setReceivedSvTimeNanos(long value) {
        mReceivedSvTimeNanos = value;
    }

    /**
     * Gets the received GNSS time uncertainty (1-Sigma) in nanoseconds.
     */
    public long getReceivedSvTimeUncertaintyNanos() {
        return mReceivedSvTimeUncertaintyNanos;
    }

    /**
     * Sets the received GNSS time uncertainty (1-Sigma) in nanoseconds.
     * @hide
     */
    @TestApi
    public void setReceivedSvTimeUncertaintyNanos(long value) {
        mReceivedSvTimeUncertaintyNanos = value;
    }

    /**
     * Gets the Carrier-to-noise density in dB-Hz.
     * Range: [0, 63].
     *
     * The value contains the measured C/N0 for the signal at the antenna input.
     */
    public double getCn0DbHz() {
        return mCn0DbHz;
    }

    /**
     * Sets the carrier-to-noise density in dB-Hz.
     * @hide
     */
    @TestApi
    public void setCn0DbHz(double value) {
        mCn0DbHz = value;
    }

    /**
     * Gets the Pseudorange rate at the timestamp in m/s.
     *
     * The reported value includes {@link #getPseudorangeRateUncertaintyMetersPerSecond()}.
     *
     * The value is uncorrected, hence corrections for receiver and satellite clock frequency errors
     * should not be included.
     *
     * A positive 'uncorrected' value indicates that the SV is moving away from the receiver. The
     * sign of the 'uncorrected' 'pseudorange rate' and its relation to the sign of 'doppler shift'
     * is given by the equation:
     *
     *      pseudorange rate = -k * doppler shift   (where k is a constant)
     */
    public double getPseudorangeRateMetersPerSecond() {
        return mPseudorangeRateMetersPerSecond;
    }

    /**
     * Sets the pseudorange rate at the timestamp in m/s.
     * @hide
     */
    @TestApi
    public void setPseudorangeRateMetersPerSecond(double value) {
        mPseudorangeRateMetersPerSecond = value;
    }

    /**
     * See {@link #getPseudorangeRateMetersPerSecond()} for more details.
     *
     * @return {@code true} if {@link #getPseudorangeRateMetersPerSecond()} contains a corrected
     *         value, {@code false} if it contains an uncorrected value.
     */
    public boolean isPseudorangeRateCorrected() {
        return mPseudorangeRateCorrected;
    }

    /**
     * Sets whether the pseudorange corrected.
     * @hide
     */
    @TestApi
    public void setPseudorangeRateCorrected(boolean value) {
        mPseudorangeRateCorrected = value;
    }

    /**
     * Gets the pseudorange's rate uncertainty (1-Sigma) in m/s.
     * The uncertainty is represented as an absolute (single sided) value.
     */
    public double getPseudorangeRateUncertaintyMetersPerSecond() {
        return mPseudorangeRateUncertaintyMetersPerSecond;
    }

    /**
     * Sets the pseudorange's rate uncertainty (1-Sigma) in m/s.
     * @hide
     */
    @TestApi
    public void setPseudorangeRateUncertaintyMetersPerSecond(double value) {
        mPseudorangeRateUncertaintyMetersPerSecond = value;
    }

    /**
     * Gets 'Accumulated Delta Range' state.
     * It indicates whether {@link #getAccumulatedDeltaRangeMeters()} is reset or there is a
     * cycle slip (indicating 'loss of lock').
     */
    public int getAccumulatedDeltaRangeState() {
        return mAccumulatedDeltaRangeState;
    }

    /**
     * Sets the 'Accumulated Delta Range' state.
     * @hide
     */
    @TestApi
    public void setAccumulatedDeltaRangeState(int value) {
        mAccumulatedDeltaRangeState = value;
    }

    /**
     * Gets a string representation of the 'Accumulated Delta Range state'.
     * For internal and logging use only.
     */
    private String getAccumulatedDeltaRangeStateString() {
        if (mAccumulatedDeltaRangeState == ADR_STATE_UNKNOWN) {
            return "Unknown";
        }
        StringBuilder builder = new StringBuilder();
        if ((mAccumulatedDeltaRangeState & ADR_STATE_VALID) == ADR_STATE_VALID) {
            builder.append("Valid|");
        }
        if ((mAccumulatedDeltaRangeState & ADR_STATE_RESET) == ADR_STATE_RESET) {
            builder.append("Reset|");
        }
        if ((mAccumulatedDeltaRangeState & ADR_STATE_CYCLE_SLIP) == ADR_STATE_CYCLE_SLIP) {
            builder.append("CycleSlip|");
        }
        int remainingStates = mAccumulatedDeltaRangeState & ~ADR_ALL;
        if (remainingStates > 0) {
            builder.append("Other(");
            builder.append(Integer.toBinaryString(remainingStates));
            builder.append(")|");
        }
        builder.deleteCharAt(builder.length() - 1);
        return builder.toString();
    }

    /**
     * Gets the accumulated delta range since the last channel reset, in meters.
     * The reported value includes {@link #getAccumulatedDeltaRangeUncertaintyMeters()}.
     *
     * The availability of the value is represented by {@link #getAccumulatedDeltaRangeState()}.
     *
     * A positive value indicates that the SV is moving away from the receiver.
     * The sign of {@link #getAccumulatedDeltaRangeMeters()} and its relation to the sign of
     * {@link #getCarrierPhase()} is given by the equation:
     *          accumulated delta range = -k * carrier phase    (where k is a constant)
     */
    public double getAccumulatedDeltaRangeMeters() {
        return mAccumulatedDeltaRangeMeters;
    }

    /**
     * Sets the accumulated delta range in meters.
     * @hide
     */
    @TestApi
    public void setAccumulatedDeltaRangeMeters(double value) {
        mAccumulatedDeltaRangeMeters = value;
    }

    /**
     * Gets the accumulated delta range's uncertainty (1-Sigma) in meters.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The status of the value is represented by {@link #getAccumulatedDeltaRangeState()}.
     */
    public double getAccumulatedDeltaRangeUncertaintyMeters() {
        return mAccumulatedDeltaRangeUncertaintyMeters;
    }

    /**
     * Sets the accumulated delta range's uncertainty (1-sigma) in meters.
     *
     * The status of the value is represented by {@link #getAccumulatedDeltaRangeState()}.
     *
     * @hide
     */
    @TestApi
    public void setAccumulatedDeltaRangeUncertaintyMeters(double value) {
        mAccumulatedDeltaRangeUncertaintyMeters = value;
    }

    /**
     * Returns true if {@link #getCarrierFrequencyHz()} is available, false otherwise.
     */
    public boolean hasCarrierFrequencyHz() {
        return isFlagSet(HAS_CARRIER_FREQUENCY);
    }

    /**
     * Gets the carrier frequency at which codes and messages are modulated, it can be L1 or L2.
     * If the field is not set, the carrier frequency corresponds to L1.
     *
     * The value is only available if {@link #hasCarrierFrequencyHz()} is true.
     */
    public float getCarrierFrequencyHz() {
        return mCarrierFrequencyHz;
    }

    /**
     * Sets the Carrier frequency (L1 or L2) in Hz.
     * @hide
     */
    @TestApi
    public void setCarrierFrequencyHz(float carrierFrequencyHz) {
        setFlag(HAS_CARRIER_FREQUENCY);
        mCarrierFrequencyHz = carrierFrequencyHz;
    }

    /**
     * Resets the Carrier frequency (L1 or L2) in Hz.
     * @hide
     */
    @TestApi
    public void resetCarrierFrequencyHz() {
        resetFlag(HAS_CARRIER_FREQUENCY);
        mCarrierFrequencyHz = Float.NaN;
    }

    /**
     * Returns true if {@link #getCarrierCycles()} is available, false otherwise.
     */
    public boolean hasCarrierCycles() {
        return isFlagSet(HAS_CARRIER_CYCLES);
    }

    /**
     * The number of full carrier cycles between the satellite and the receiver.
     * The reference frequency is given by the value of {@link #getCarrierFrequencyHz()}.
     *
     * The value is only available if {@link #hasCarrierCycles()} is true.
     */
    public long getCarrierCycles() {
        return mCarrierCycles;
    }

    /**
     * Sets the number of full carrier cycles between the satellite and the receiver.
     * @hide
     */
    @TestApi
    public void setCarrierCycles(long value) {
        setFlag(HAS_CARRIER_CYCLES);
        mCarrierCycles = value;
    }

    /**
     * Resets the number of full carrier cycles between the satellite and the receiver.
     * @hide
     */
    @TestApi
    public void resetCarrierCycles() {
        resetFlag(HAS_CARRIER_CYCLES);
        mCarrierCycles = Long.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getCarrierPhase()} is available, false otherwise.
     */
    public boolean hasCarrierPhase() {
        return isFlagSet(HAS_CARRIER_PHASE);
    }

    /**
     * Gets the RF phase detected by the receiver.
     * Range: [0.0, 1.0].
     * This is usually the fractional part of the complete carrier phase measurement.
     *
     * The reference frequency is given by the value of {@link #getCarrierFrequencyHz()}.
     * The reported carrier-phase includes {@link #getCarrierPhaseUncertainty()}.
     *
     * The value is only available if {@link #hasCarrierPhase()} is true.
     */
    public double getCarrierPhase() {
        return mCarrierPhase;
    }

    /**
     * Sets the RF phase detected by the receiver.
     * @hide
     */
    @TestApi
    public void setCarrierPhase(double value) {
        setFlag(HAS_CARRIER_PHASE);
        mCarrierPhase = value;
    }

    /**
     * Resets the RF phase detected by the receiver.
     * @hide
     */
    @TestApi
    public void resetCarrierPhase() {
        resetFlag(HAS_CARRIER_PHASE);
        mCarrierPhase = Double.NaN;
    }

    /**
     * Returns true if {@link #getCarrierPhaseUncertainty()} is available, false otherwise.
     */
    public boolean hasCarrierPhaseUncertainty() {
        return isFlagSet(HAS_CARRIER_PHASE_UNCERTAINTY);
    }

    /**
     * Gets the carrier-phase's uncertainty (1-Sigma).
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasCarrierPhaseUncertainty()} is true.
     */
    public double getCarrierPhaseUncertainty() {
        return mCarrierPhaseUncertainty;
    }

    /**
     * Sets the Carrier-phase's uncertainty (1-Sigma) in cycles.
     * @hide
     */
    @TestApi
    public void setCarrierPhaseUncertainty(double value) {
        setFlag(HAS_CARRIER_PHASE_UNCERTAINTY);
        mCarrierPhaseUncertainty = value;
    }

    /**
     * Resets the Carrier-phase's uncertainty (1-Sigma) in cycles.
     * @hide
     */
    @TestApi
    public void resetCarrierPhaseUncertainty() {
        resetFlag(HAS_CARRIER_PHASE_UNCERTAINTY);
        mCarrierPhaseUncertainty = Double.NaN;
    }

    /**
     * Gets a value indicating the 'multipath' state of the event.
     */
    @MultipathIndicator
    public int getMultipathIndicator() {
        return mMultipathIndicator;
    }

    /**
     * Sets the 'multi-path' indicator.
     * @hide
     */
    @TestApi
    public void setMultipathIndicator(@MultipathIndicator int value) {
        mMultipathIndicator = value;
    }

    /**
     * Gets a string representation of the 'multi-path indicator'.
     * For internal and logging use only.
     */
    private String getMultipathIndicatorString() {
        switch(mMultipathIndicator) {
            case MULTIPATH_INDICATOR_UNKNOWN:
                return "Unknown";
            case MULTIPATH_INDICATOR_DETECTED:
                return "Detected";
            case MULTIPATH_INDICATOR_NOT_USED:
                return "NotUsed";
            default:
                return "<Invalid:" + mMultipathIndicator + ">";
        }
    }

    /**
     * Returns true if {@link #getSnrInDb()} is available, false otherwise.
     */
    public boolean hasSnrInDb() {
        return isFlagSet(HAS_SNR);
    }

    /**
     * Gets the Signal-to-Noise ratio (SNR) in dB.
     *
     * The value is only available if {@link #hasSnrInDb()} is true.
     */
    public double getSnrInDb() {
        return mSnrInDb;
    }

    /**
     * Sets the Signal-to-noise ratio (SNR) in dB.
     * @hide
     */
    @TestApi
    public void setSnrInDb(double snrInDb) {
        setFlag(HAS_SNR);
        mSnrInDb = snrInDb;
    }

    /**
     * Resets the Signal-to-noise ratio (SNR) in dB.
     * @hide
     */
    @TestApi
    public void resetSnrInDb() {
        resetFlag(HAS_SNR);
        mSnrInDb = Double.NaN;
    }

    public static final Creator<GnssMeasurement> CREATOR = new Creator<GnssMeasurement>() {
        @Override
        public GnssMeasurement createFromParcel(Parcel parcel) {
            GnssMeasurement gnssMeasurement = new GnssMeasurement();

            gnssMeasurement.mFlags = parcel.readInt();
            gnssMeasurement.mSvid = parcel.readInt();
            gnssMeasurement.mConstellationType = parcel.readInt();
            gnssMeasurement.mTimeOffsetNanos = parcel.readDouble();
            gnssMeasurement.mState = parcel.readInt();
            gnssMeasurement.mReceivedSvTimeNanos = parcel.readLong();
            gnssMeasurement.mReceivedSvTimeUncertaintyNanos = parcel.readLong();
            gnssMeasurement.mCn0DbHz = parcel.readDouble();
            gnssMeasurement.mPseudorangeRateMetersPerSecond = parcel.readDouble();
            gnssMeasurement.mPseudorangeRateUncertaintyMetersPerSecond = parcel.readDouble();
            gnssMeasurement.mAccumulatedDeltaRangeState = parcel.readInt();
            gnssMeasurement.mAccumulatedDeltaRangeMeters = parcel.readDouble();
            gnssMeasurement.mAccumulatedDeltaRangeUncertaintyMeters = parcel.readDouble();
            gnssMeasurement.mCarrierFrequencyHz = parcel.readFloat();
            gnssMeasurement.mCarrierCycles = parcel.readLong();
            gnssMeasurement.mCarrierPhase = parcel.readDouble();
            gnssMeasurement.mCarrierPhaseUncertainty = parcel.readDouble();
            gnssMeasurement.mMultipathIndicator = parcel.readInt();
            gnssMeasurement.mSnrInDb = parcel.readDouble();
            gnssMeasurement.mPseudorangeRateCorrected = (parcel.readByte() != 0);

            return gnssMeasurement;
        }

        @Override
        public GnssMeasurement[] newArray(int i) {
            return new GnssMeasurement[i];
        }
    };

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mFlags);
        parcel.writeInt(mSvid);
        parcel.writeInt(mConstellationType);
        parcel.writeDouble(mTimeOffsetNanos);
        parcel.writeInt(mState);
        parcel.writeLong(mReceivedSvTimeNanos);
        parcel.writeLong(mReceivedSvTimeUncertaintyNanos);
        parcel.writeDouble(mCn0DbHz);
        parcel.writeDouble(mPseudorangeRateMetersPerSecond);
        parcel.writeDouble(mPseudorangeRateUncertaintyMetersPerSecond);
        parcel.writeInt(mAccumulatedDeltaRangeState);
        parcel.writeDouble(mAccumulatedDeltaRangeMeters);
        parcel.writeDouble(mAccumulatedDeltaRangeUncertaintyMeters);
        parcel.writeFloat(mCarrierFrequencyHz);
        parcel.writeLong(mCarrierCycles);
        parcel.writeDouble(mCarrierPhase);
        parcel.writeDouble(mCarrierPhaseUncertainty);
        parcel.writeInt(mMultipathIndicator);
        parcel.writeDouble(mSnrInDb);
        parcel.writeByte((byte) (mPseudorangeRateCorrected ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-29s = %s\n";
        final String formatWithUncertainty = "   %-29s = %-25s   %-40s = %s\n";
        StringBuilder builder = new StringBuilder("GnssMeasurement:\n");

        builder.append(String.format(format, "Svid", mSvid));
        builder.append(String.format(format, "ConstellationType", mConstellationType));
        builder.append(String.format(format, "TimeOffsetNanos", mTimeOffsetNanos));

        builder.append(String.format(format, "State", getStateString()));

        builder.append(String.format(
                formatWithUncertainty,
                "ReceivedSvTimeNanos",
                mReceivedSvTimeNanos,
                "ReceivedSvTimeUncertaintyNanos",
                mReceivedSvTimeUncertaintyNanos));

        builder.append(String.format(format, "Cn0DbHz", mCn0DbHz));

        builder.append(String.format(
                formatWithUncertainty,
                "PseudorangeRateMetersPerSecond",
                mPseudorangeRateMetersPerSecond,
                "PseudorangeRateUncertaintyMetersPerSecond",
                mPseudorangeRateUncertaintyMetersPerSecond));
        builder.append(String.format(
                format,
                "PseudorangeRateIsCorrected",
                isPseudorangeRateCorrected()));

        builder.append(String.format(
                format,
                "AccumulatedDeltaRangeState",
                getAccumulatedDeltaRangeStateString()));

        builder.append(String.format(
                formatWithUncertainty,
                "AccumulatedDeltaRangeMeters",
                mAccumulatedDeltaRangeMeters,
                "AccumulatedDeltaRangeUncertaintyMeters",
                mAccumulatedDeltaRangeUncertaintyMeters));

        builder.append(String.format(
                format,
                "CarrierFrequencyHz",
                hasCarrierFrequencyHz() ? mCarrierFrequencyHz : null));

        builder.append(String.format(
                format,
                "CarrierCycles",
                hasCarrierCycles() ? mCarrierCycles : null));

        builder.append(String.format(
                formatWithUncertainty,
                "CarrierPhase",
                hasCarrierPhase() ? mCarrierPhase : null,
                "CarrierPhaseUncertainty",
                hasCarrierPhaseUncertainty() ? mCarrierPhaseUncertainty : null));

        builder.append(String.format(format, "MultipathIndicator", getMultipathIndicatorString()));

        builder.append(String.format(
                format,
                "SnrInDb",
                hasSnrInDb() ? mSnrInDb : null));

        return builder.toString();
    }

    private void initialize() {
        mFlags = HAS_NO_FLAGS;
        setSvid(0);
        setTimeOffsetNanos(Long.MIN_VALUE);
        setState(STATE_UNKNOWN);
        setReceivedSvTimeNanos(Long.MIN_VALUE);
        setReceivedSvTimeUncertaintyNanos(Long.MAX_VALUE);
        setCn0DbHz(Double.MIN_VALUE);
        setPseudorangeRateMetersPerSecond(Double.MIN_VALUE);
        setPseudorangeRateUncertaintyMetersPerSecond(Double.MIN_VALUE);
        setAccumulatedDeltaRangeState(ADR_STATE_UNKNOWN);
        setAccumulatedDeltaRangeMeters(Double.MIN_VALUE);
        setAccumulatedDeltaRangeUncertaintyMeters(Double.MIN_VALUE);
        resetCarrierFrequencyHz();
        resetCarrierCycles();
        resetCarrierPhase();
        resetCarrierPhaseUncertainty();
        setMultipathIndicator(MULTIPATH_INDICATOR_UNKNOWN);
        resetSnrInDb();
        setPseudorangeRateCorrected(false);
    }

    private void setFlag(int flag) {
        mFlags |= flag;
    }

    private void resetFlag(int flag) {
        mFlags &= ~flag;
    }

    private boolean isFlagSet(int flag) {
        return (mFlags & flag) == flag;
    }
}
