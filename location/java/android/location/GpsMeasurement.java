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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A class representing a GPS satellite measurement, containing raw and computed information.
 *
 * @hide
 */
public class GpsMeasurement implements Parcelable {
    private static final String TAG = "GpsMeasurement";

    // mandatory parameters
    private byte mPrn;
    private long mLocalTimeInNs;
    private long mReceivedGpsTowInNs;
    private double mCn0InDbHz;
    private double mPseudorangeRateInMetersPerSec;
    private double mPseudorangeRateUncertaintyInMetersPerSec;
    private double mAccumulatedDeltaRangeInMeters;
    private double mAccumulatedDeltaRangeUncertaintyInMeters;

    // optional parameters
    private boolean mHasPseudorangeInMeters;
    private double mPseudorangeInMeters;
    private boolean mHasPseudorangeUncertaintyInMeters;
    private double mPseudorangeUncertaintyInMeters;
    private boolean mHasCodePhaseInChips;
    private double mCodePhaseInChips;
    private boolean mHasCodePhaseUncertaintyInChips;
    private double mCodePhaseUncertaintyInChips;
    private boolean mHasCarrierFrequencyInHz;
    private float mCarrierFrequencyInHz;
    private boolean mHasCarrierCycles;
    private long mCarrierCycles;
    private boolean mHasCarrierPhase;
    private double mCarrierPhase;
    private boolean mHasCarrierPhaseUncertainty;
    private double mCarrierPhaseUncertainty;
    private short mLossOfLock;
    private boolean mHasBitNumber;
    private short mBitNumber;
    private boolean mHasTimeFromLastBitInNs;
    private long mTimeFromLastBitInNs;
    private boolean mHasDopplerShiftInHz;
    private double mDopplerShiftInHz;
    private boolean mHasDopplerShiftUncertaintyInHz;
    private double mDopplerShiftUncertaintyInHz;
    private short mMultipathIndicator;
    private boolean mHasSnrInDb;
    private double mSnrInDb;
    private boolean mHasElevationInDeg;
    private double mElevationInDeg;
    private boolean mHasElevationUncertaintyInDeg;
    private double mElevationUncertaintyInDeg;
    private boolean mHasAzimuthInDeg;
    private double mAzimuthInDeg;
    private boolean mHasAzimuthUncertaintyInDeg;
    private double mAzimuthUncertaintyInDeg;
    private boolean mUsedInFix;

    // The following enumerations must be in sync with the values declared in gps.h

    /**
     * The indicator is not available or it is unknown.
     */
    public static final short LOSS_OF_LOCK_UNKNOWN = 0;

    /**
     * The measurement does not present any indication of 'loss of lock'.
     */
    public static final short LOSS_OF_LOCK_OK = 1;

    /**
     * 'Loss of lock' detected between the previous and current observation: cycle slip possible.
     */
    public static final short LOSS_OF_LOCK_CYCLE_SLIP = 2;

    /**
     * The indicator is not available or it is unknown.
     */
    public static final short MULTIPATH_INDICATOR_UNKNOWN = 0;

    /**
     * The measurement has been indicated to use multi-path.
     */
    public static final short MULTIPATH_INDICATOR_DETECTED = 1;

    /**
     * The measurement has been indicated not tu use multi-path.
     */
    public static final short MULTIPATH_INDICATOR_NOT_USED = 2;

    // End enumerations in sync with gps.h

    GpsMeasurement() {
        reset();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     */
    public void set(GpsMeasurement measurement) {
        mPrn = measurement.mPrn;
        mLocalTimeInNs = measurement.mLocalTimeInNs;
        mReceivedGpsTowInNs = measurement.mReceivedGpsTowInNs;
        mCn0InDbHz = measurement.mCn0InDbHz;
        mPseudorangeRateInMetersPerSec = measurement.mPseudorangeRateInMetersPerSec;
        mPseudorangeRateUncertaintyInMetersPerSec =
                measurement.mPseudorangeRateUncertaintyInMetersPerSec;
        mAccumulatedDeltaRangeInMeters = measurement.mAccumulatedDeltaRangeInMeters;
        mAccumulatedDeltaRangeUncertaintyInMeters =
                measurement.mAccumulatedDeltaRangeUncertaintyInMeters;

        mHasPseudorangeInMeters = measurement.mHasPseudorangeInMeters;
        mPseudorangeInMeters = measurement.mPseudorangeInMeters;
        mHasPseudorangeUncertaintyInMeters = measurement.mHasPseudorangeUncertaintyInMeters;
        mPseudorangeUncertaintyInMeters = measurement.mPseudorangeUncertaintyInMeters;
        mHasCodePhaseInChips = measurement.mHasCodePhaseInChips;
        mCodePhaseInChips = measurement.mCodePhaseInChips;
        mHasCodePhaseUncertaintyInChips = measurement.mHasCodePhaseUncertaintyInChips;
        mCodePhaseUncertaintyInChips = measurement.mCodePhaseUncertaintyInChips;
        mHasCarrierFrequencyInHz = measurement.mHasCarrierFrequencyInHz;
        mCarrierFrequencyInHz = measurement.mCarrierFrequencyInHz;
        mHasCarrierCycles = measurement.mHasCarrierCycles;
        mCarrierCycles = measurement.mCarrierCycles;
        mHasCarrierPhase = measurement.mHasCarrierPhase;
        mCarrierPhase = measurement.mCarrierPhase;
        mHasCarrierPhaseUncertainty = measurement.mHasCarrierPhaseUncertainty;
        mCarrierPhaseUncertainty = measurement.mCarrierPhaseUncertainty;
        mLossOfLock = measurement.mLossOfLock;
        mHasBitNumber = measurement.mHasBitNumber;
        mBitNumber = measurement.mBitNumber;
        mHasTimeFromLastBitInNs = measurement.mHasTimeFromLastBitInNs;
        mTimeFromLastBitInNs = measurement.mTimeFromLastBitInNs;
        mHasDopplerShiftInHz = measurement.mHasDopplerShiftInHz;
        mDopplerShiftInHz = measurement.mDopplerShiftInHz;
        mHasDopplerShiftUncertaintyInHz = measurement.mHasDopplerShiftUncertaintyInHz;
        mDopplerShiftUncertaintyInHz = measurement.mDopplerShiftUncertaintyInHz;
        mMultipathIndicator = measurement.mMultipathIndicator;
        mHasSnrInDb = measurement.mHasSnrInDb;
        mSnrInDb = measurement.mSnrInDb;
        mHasElevationInDeg = measurement.mHasElevationInDeg;
        mElevationInDeg = measurement.mElevationInDeg;
        mHasElevationUncertaintyInDeg = measurement.mHasElevationUncertaintyInDeg;
        mElevationUncertaintyInDeg = measurement.mElevationUncertaintyInDeg;
        mHasAzimuthInDeg = measurement.mHasAzimuthInDeg;
        mAzimuthInDeg = measurement.mAzimuthInDeg;
        mHasAzimuthUncertaintyInDeg = measurement.mHasAzimuthUncertaintyInDeg;
        mAzimuthUncertaintyInDeg = measurement.mAzimuthUncertaintyInDeg;
        mUsedInFix = measurement.mUsedInFix;
    }

    /**
     * Resets all the contents to its original state.
     */
    public void reset() {
        mPrn = Byte.MIN_VALUE;
        mLocalTimeInNs = Long.MIN_VALUE;
        mReceivedGpsTowInNs = Long.MIN_VALUE;
        mCn0InDbHz = Double.MIN_VALUE;
        mPseudorangeRateInMetersPerSec = Double.MIN_VALUE;
        mPseudorangeRateUncertaintyInMetersPerSec = Double.MIN_VALUE;
        mAccumulatedDeltaRangeInMeters = Double.MIN_VALUE;
        mAccumulatedDeltaRangeUncertaintyInMeters = Double.MIN_VALUE;

        resetPseudorangeInMeters();
        resetPseudorangeUncertaintyInMeters();
        resetCodePhaseInChips();
        resetCodePhaseUncertaintyInChips();
        resetCarrierFrequencyInHz();
        resetCarrierCycles();
        resetCarrierPhase();
        resetCarrierPhaseUncertainty();
        setLossOfLock(LOSS_OF_LOCK_UNKNOWN);
        resetBitNumber();
        resetTimeFromLastBitInNs();
        resetDopplerShiftInHz();
        resetDopplerShiftUncertaintyInHz();
        setMultipathIndicator(MULTIPATH_INDICATOR_UNKNOWN);
        resetSnrInDb();
        resetElevationInDeg();
        resetElevationUncertaintyInDeg();
        resetAzimuthInDeg();
        resetAzimuthUncertaintyInDeg();
        setUsedInFix(false);
    }

    /**
     * Gets the Pseudo-random number (PRN).
     * Range: [1, 32]
     */
    public byte getPrn() {
        return mPrn;
    }

    /**
     * Sets the Pseud-random number (PRN).
     */
    public void setPrn(byte value) {
        mPrn = value;
    }

    /**
     * Gets the local (hardware) time at which the measurement was taken in nanoseconds.
     */
    public long getLocalTimeInNs() {
        return mLocalTimeInNs;
    }

    /**
     * Sets the measurement's local (hardware) time in nanoseconds.
     */
    public void setLocalTimeInNs(long value) {
        mLocalTimeInNs = value;
    }

    /**
     * Gets the received GPS Time-of-Week in nanoseconds.
     * The value is relative to the beginning of the current GPS week.
     */
    public long getReceivedGpsTowInNs() {
        return mReceivedGpsTowInNs;
    }

    /**
     * Sets the received GPS time-of-week in nanoseconds.
     */
    public void setReceivedGpsTowInNs(long value) {
        mReceivedGpsTowInNs = value;
    }

    /**
     * Gets the Carrier-to-noise density in dB-Hz.
     * Range: [0, 63].
     *
     * The value contains the measured C/N0 for the signal at the antenna input.
     */
    public double getCn0InDbHz() {
        return mCn0InDbHz;
    }

    /**
     * Sets the carrier-to-noise density in dB-Hz.
     */
    public void setCn0InDbHz(double value) {
        mCn0InDbHz = value;
    }

    /**
     * Gets the Pseudorange rate at the timestamp in m/s.
     * The reported value includes {@link #getPseudorangeRateUncertaintyInMetersPerSec()}.
     */
    public double getPseudorangeRateInMetersPerSec() {
        return mPseudorangeRateInMetersPerSec;
    }

    /**
     * Sets the pseudorange rate at the timestamp in m/s.
     */
    public void setPseudorangeRateInMetersPerSec(double value) {
        mPseudorangeRateInMetersPerSec = value;
    }

    /**
     * Gets the pseudorange's rate uncertainty (1-Sigma) in m/s.
     * The uncertainty is represented as an absolute (single sided) value.
     */
    public double getPseudorangeRateUncertaintyInMetersPerSec() {
        return mPseudorangeRateUncertaintyInMetersPerSec;
    }

    /**
     * Sets the pseudorange's rate uncertainty (1-Sigma) in m/s.
     */
    public void setPseudorangeRateUncertaintyInMetersPerSec(double value) {
        mPseudorangeRateUncertaintyInMetersPerSec = value;
    }

    /**
     * Gets the accumulated delta range since the last channel reset, in meters.
     * The reported value includes {@link #getAccumulatedDeltaRangeUncertaintyInMeters()}.
     */
    public double getAccumulatedDeltaRangeInMeters() {
        return mAccumulatedDeltaRangeInMeters;
    }

    /**
     * Sets the accumulated delta range in meters.
     */
    public void setAccumulatedDeltaRangeInMeters(double value) {
        mAccumulatedDeltaRangeInMeters = value;
    }

    /**
     * Gets the accumulated delta range's uncertainty (1-Sigma) in meters.
     * The uncertainty is represented as an absolute (single sided) value.
     */
    public double getAccumulatedDeltaRangeUncertaintyInMeters() {
        return mAccumulatedDeltaRangeUncertaintyInMeters;
    }

    /**
     * Sets the accumulated delta range's uncertainty (1-sigma) in meters.
     */
    public void setAccumulatedDeltaRangeUncertaintyInMeters(double value) {
        mAccumulatedDeltaRangeUncertaintyInMeters = value;
    }

    /**
     * Returns true if {@link #getPseudorangeInMeters()} is available, false otherwise.
     */
    public boolean hasPseudorangeInMeters() {
        return mHasPseudorangeInMeters;
    }

    /**
     * Gets the best derived pseudorange by the chipset, in meters.
     * The reported pseudorange includes {@link #getPseudorangeUncertaintyInMeters()}.
     *
     * The value is only available if {@link #hasPseudorangeInMeters()} is true.
     */
    public double getPseudorangeInMeters() {
        return mPseudorangeInMeters;
    }

    /**
     * Sets the Pseudo-range in meters.
     */
    public void setPseudorangeInMeters(double value) {
        mHasPseudorangeInMeters = true;
        mPseudorangeInMeters = value;
    }

    /**
     * Resets the Pseudo-range in meters.
     */
    public void resetPseudorangeInMeters() {
        mHasPseudorangeInMeters = false;
        mPseudorangeInMeters = Double.NaN;
    }

    /**
     * Returns true if {@link #getPseudorangeUncertaintyInMeters()} is available, false otherwise.
     */
    public boolean hasPseudorangeUncertaintyInMeters() {
        return mHasPseudorangeUncertaintyInMeters;
    }

    /**
     * Gets the pseudorange's uncertainty (1-Sigma) in meters.
     * The value contains the 'pseudorange' and 'clock' uncertainty in it.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasPseudorangeUncertaintyInMeters()} is true.
     */
    public double getPseudorangeUncertaintyInMeters() {
        return mPseudorangeUncertaintyInMeters;
    }

    /**
     * Sets the pseudo-range's uncertainty (1-Sigma) in meters.
     */
    public void setPseudorangeUncertaintyInMeters(double value) {
        mHasPseudorangeUncertaintyInMeters = true;
        mPseudorangeUncertaintyInMeters = value;
    }

    /**
     * Resets the pseudo-range's uncertainty (1-Sigma) in meters.
     */
    public void resetPseudorangeUncertaintyInMeters() {
        mHasPseudorangeUncertaintyInMeters = false;
        mPseudorangeUncertaintyInMeters = Double.NaN;
    }

    /**
     * Returns true if {@link #getCodePhaseInChips()} is available, false otherwise.
     */
    public boolean hasCodePhaseInChips() {
        return mHasCodePhaseInChips;
    }

    /**
     * Gets the fraction of the current C/A code cycle.
     * Range: [0, 1023]
     * The reference frequency is given by the value of {@link #getCarrierFrequencyInHz()}.
     * The reported code-phase includes {@link #getCodePhaseUncertaintyInChips()}.
     *
     * The value is only available if {@link #hasCodePhaseInChips()} is true.
     */
    public double getCodePhaseInChips() {
        return mCodePhaseInChips;
    }

    /**
     * Sets the Code-phase in chips.
     */
    public void setCodePhaseInChips(double value) {
        mHasCodePhaseInChips = true;
        mCodePhaseInChips = value;
    }

    /**
     * Resets the Code-phase in chips.
     */
    public void resetCodePhaseInChips() {
        mHasCodePhaseInChips = false;
        mCodePhaseInChips = Double.NaN;
    }

    /**
     * Returns true if {@link #getCodePhaseUncertaintyInChips()} is available, false otherwise.
     */
    public boolean hasCodePhaseUncertaintyInChips() {
        return mHasCodePhaseUncertaintyInChips;
    }

    /**
     * Gets the code-phase's uncertainty (1-Sigma) as a fraction of chips.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasCodePhaseUncertaintyInChips()} is true.
     */
    public double getCodePhaseUncertaintyInChips() {
        return mCodePhaseUncertaintyInChips;
    }

    /**
     * Sets the Code-phase's uncertainty (1-Sigma) in fractions of chips.
     */
    public void setCodePhaseUncertaintyInChips(double value) {
        mHasCodePhaseUncertaintyInChips = true;
        mCodePhaseUncertaintyInChips = value;
    }

    /**
     * Resets the Code-phase's uncertainty (1-Sigma) in fractions of chips.
     */
    public void resetCodePhaseUncertaintyInChips() {
        mHasCodePhaseUncertaintyInChips = false;
        mCodePhaseUncertaintyInChips = Double.NaN;
    }

    /**
     * Returns true if {@link #getCarrierFrequencyInHz()} is available, false otherwise.
     */
    public boolean hasCarrierFrequencyInHz() {
        return mHasCarrierFrequencyInHz;
    }

    /**
     * Gets the carrier frequency at which codes and messages are modulated, it can be L1 or L2.
     * If the field is not set, the carrier frequency corresponds to L1.
     *
     * The value is only available if {@link #hasCarrierFrequencyInHz()} is true.
     */
    public float getCarrierFrequencyInHz() {
        return mCarrierFrequencyInHz;
    }

    /**
     * Sets the Carrier frequency (L1 or L2) in Hz.
     */
    public void setCarrierFrequencyInHz(float carrierFrequencyInHz) {
        mHasCarrierFrequencyInHz = true;
        mCarrierFrequencyInHz = carrierFrequencyInHz;
    }

    /**
     * Resets the Carrier frequency (L1 or L2) in Hz.
     */
    public void resetCarrierFrequencyInHz() {
        mHasCarrierFrequencyInHz = false;
        mCarrierFrequencyInHz = Float.NaN;
    }

    /**
     * Returns true if {@link #getCarrierCycles()} is available, false otherwise.
     */
    public boolean hasCarrierCycles() {
        return mHasCarrierCycles;
    }

    /**
     * The number of full carrier cycles between the satellite and the receiver.
     * The reference frequency is given by the value of {@link #getCarrierFrequencyInHz()}.
     *
     * The value is only available if {@link #hasCarrierCycles()} is true.
     */
    public long getCarrierCycles() {
        return mCarrierCycles;
    }

    /**
     * Sets the number of full carrier cycles between the satellite and the receiver.
     */
    public void setCarrierCycles(long value) {
        mHasCarrierCycles = true;
        mCarrierCycles = value;
    }

    /**
     * Resets the number of full carrier cycles between the satellite and the receiver.
     */
    public void resetCarrierCycles() {
        mHasCarrierCycles = false;
        mCarrierCycles = Long.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getCarrierPhase()} is available, false otherwise.
     */
    public boolean hasCarrierPhase() {
        return mHasCarrierPhase;
    }

    /**
     * Gets the RF phase detected by the receiver.
     * Range: [0.0, 1.0].
     * This is usually the fractional part of the complete carrier phase measurement.
     *
     * The reference frequency is given by the value of {@link #getCarrierFrequencyInHz()}.
     * The reported carrier-phase includes {@link #getCarrierPhaseUncertainty()}.
     *
     * The value is only available if {@link #hasCarrierPhase()} is true.
     */
    public double getCarrierPhase() {
        return mCarrierPhase;
    }

    /**
     * Sets the RF phase detected by the receiver.
     */
    public void setCarrierPhase(double value) {
        mHasCarrierPhase = true;
        mCarrierPhase = value;
    }

    /**
     * Resets the RF phase detected by the receiver.
     */
    public void resetCarrierPhase() {
        mHasCarrierPhase = false;
        mCarrierPhase = Double.NaN;
    }

    /**
     * Returns true if {@link #getCarrierPhaseUncertainty()} is available, false otherwise.
     */
    public boolean hasCarrierPhaseUncertainty() {
        return mHasCarrierPhaseUncertainty;
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
     */
    public void setCarrierPhaseUncertainty(double value) {
        mHasCarrierPhaseUncertainty = true;
        mCarrierPhaseUncertainty = value;
    }

    /**
     * Resets the Carrier-phase's uncertainty (1-Sigma) in cycles.
     */
    public void resetCarrierPhaseUncertainty() {
        mHasCarrierPhaseUncertainty = false;
        mCarrierPhaseUncertainty = Double.NaN;
    }

    /**
     * Gets a value indicating the 'loss of lock' state of the event.
     */
    public short getLossOfLock() {
        return mLossOfLock;
    }

    /**
     * Sets the 'loss of lock' status.
     */
    public void setLossOfLock(short value) {
        switch (value) {
            case LOSS_OF_LOCK_UNKNOWN:
            case LOSS_OF_LOCK_OK:
            case LOSS_OF_LOCK_CYCLE_SLIP:
                mLossOfLock = value;
                break;
            default:
                Log.d(TAG, "Sanitizing invalid 'loss of lock': " + value);
                mLossOfLock = LOSS_OF_LOCK_UNKNOWN;
                break;
        }
    }

    /**
     * Gets a string representation of the 'loss of lock'.
     * For internal and logging use only.
     */
    private String getLossOfLockString() {
        switch (mLossOfLock) {
            case LOSS_OF_LOCK_UNKNOWN:
                return "Unknown";
            case LOSS_OF_LOCK_OK:
                return "Ok";
            case LOSS_OF_LOCK_CYCLE_SLIP:
                return "CycleSlip";
            default:
                return "Invalid";
        }
    }

    /**
     * Returns true if {@link #getBitNumber()} is available, false otherwise.
     */
    public boolean hasBitNumber() {
        return mHasBitNumber;
    }

    /**
     * Gets the number of GPS bits transmitted since Sat-Sun midnight (GPS week).
     *
     * The value is only available if {@link #hasBitNumber()} is true.
     */
    public short getBitNumber() {
        return mBitNumber;
    }

    /**
     * Sets the bit number within the broadcast frame.
     */
    public void setBitNumber(short bitNumber) {
        mHasBitNumber = true;
        mBitNumber = bitNumber;
    }

    /**
     * Resets the bit number within the broadcast frame.
     */
    public void resetBitNumber() {
        mHasBitNumber = false;
        mBitNumber = Short.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getTimeFromLastBitInNs()} is available, false otherwise.
     */
    public boolean hasTimeFromLastBitInNs() {
        return mHasTimeFromLastBitInNs;
    }

    /**
     * Gets the elapsed time since the last received bit in nanoseconds.
     * Range: [0, 20000000].
     *
     * The value is only available if {@link #hasTimeFromLastBitInNs()} is true.
     */
    public long getTimeFromLastBitInNs() {
        return mTimeFromLastBitInNs;
    }

    /**
     * Sets the elapsed time since the last received bit in nanoseconds.
     */
    public void setTimeFromLastBitInNs(long value) {
        mHasTimeFromLastBitInNs = true;
        mTimeFromLastBitInNs = value;
    }

    /**
     * Resets the elapsed time since the last received bit in nanoseconds.
     */
    public void resetTimeFromLastBitInNs() {
        mHasTimeFromLastBitInNs = false;
        mTimeFromLastBitInNs = Long.MIN_VALUE;
    }

    /**
     * Returns true if {@link #getDopplerShiftInHz()} is available, false otherwise.
     */
    public boolean hasDopplerShiftInHz() {
        return mHasDopplerShiftInHz;
    }

    /**
     * Gets the Doppler Shift in Hz.
     * A positive value indicates that the SV is moving toward the receiver.
     *
     * The reference frequency is given by the value of {@link #getCarrierFrequencyInHz()}.
     * The reported doppler shift includes {@link #getDopplerShiftUncertaintyInHz()}.
     *
     * The value is only available if {@link #hasDopplerShiftInHz()} is true.
     */
    public double getDopplerShiftInHz() {
        return mDopplerShiftInHz;
    }

    /**
     * Sets the Doppler shift in Hz.
     */
    public void setDopplerShiftInHz(double value) {
        mHasDopplerShiftInHz = true;
        mDopplerShiftInHz = value;
    }

    /**
     * Resets the Doppler shift in Hz.
     */
    public void resetDopplerShiftInHz() {
        mHasDopplerShiftInHz = false;
        mDopplerShiftInHz = Double.NaN;
    }

    /**
     * Returns true if {@link #getDopplerShiftUncertaintyInHz()} is available, false otherwise.
     */
    public boolean hasDopplerShiftUncertaintyInHz() {
        return mHasDopplerShiftUncertaintyInHz;
    }

    /**
     * Gets the Doppler's Shift uncertainty (1-Sigma) in Hz.
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasDopplerShiftUncertaintyInHz()} is true.
     */
    public double getDopplerShiftUncertaintyInHz() {
        return mDopplerShiftUncertaintyInHz;
    }

    /**
     * Sets the Doppler's shift uncertainty (1-Sigma) in Hz.
     */
    public void setDopplerShiftUncertaintyInHz(double value) {
        mHasDopplerShiftUncertaintyInHz = true;
        mDopplerShiftUncertaintyInHz = value;
    }

    /**
     * Resets the Doppler's shift uncertainty (1-Sigma) in Hz.
     */
    public void resetDopplerShiftUncertaintyInHz() {
        mHasDopplerShiftUncertaintyInHz = false;
        mDopplerShiftUncertaintyInHz = Double.NaN;
    }

    /**
     * Gets a value indicating the 'multipath' state of the event.
     */
    public short getMultipathIndicator() {
        return mMultipathIndicator;
    }

    /**
     * Sets the 'multi-path' indicator.
     */
    public void setMultipathIndicator(short value) {
        switch (value) {
            case MULTIPATH_INDICATOR_UNKNOWN:
            case MULTIPATH_INDICATOR_DETECTED:
            case MULTIPATH_INDICATOR_NOT_USED:
                mMultipathIndicator = value;
                break;
            default:
                Log.d(TAG, "Sanitizing invalid 'muti-path indicator': " + value);
                mMultipathIndicator = MULTIPATH_INDICATOR_UNKNOWN;
                break;
        }
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
                return "NotDetected";
            default:
                return "Invalid";
        }
    }

    /**
     * Returns true if {@link #getSnrInDb()} is available, false otherwise.
     */
    public boolean hasSnrInDb() {
        return mHasSnrInDb;
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
     */
    public void setSnrInDb(double snrInDb) {
        mHasSnrInDb = true;
        mSnrInDb = snrInDb;
    }

    /**
     * Resets the Signal-to-noise ratio (SNR) in dB.
     */
    public void resetSnrInDb() {
        mHasSnrInDb = false;
        mSnrInDb = Double.NaN;
    }

    /**
     * Returns true if {@link #getElevationInDeg()} is available, false otherwise.
     */
    public boolean hasElevationInDeg() {
        return mHasElevationInDeg;
    }

    /**
     * Gets the Elevation in degrees.
     * Range: [-90, 90]
     * The reported elevation includes {@link #getElevationUncertaintyInDeg()}.
     *
     * The value is only available if {@link #hasElevationInDeg()} is true.
     */
    public double getElevationInDeg() {
        return mElevationInDeg;
    }

    /**
     * Sets the Elevation in degrees.
     */
    public void setElevationInDeg(double elevationInDeg) {
        mHasElevationInDeg = true;
        mElevationInDeg = elevationInDeg;
    }

    /**
     * Resets the Elevation in degrees.
     */
    public void resetElevationInDeg() {
        mHasElevationInDeg = false;
        mElevationInDeg = Double.NaN;
    }

    /**
     * Returns true if {@link #getElevationUncertaintyInDeg()} is available, false otherwise.
     */
    public boolean hasElevationUncertaintyInDeg() {
        return mHasElevationUncertaintyInDeg;
    }

    /**
     * Gets the elevation's uncertainty (1-Sigma) in degrees.
     * Range: [0, 90]
     *
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasElevationUncertaintyInDeg()} is true.
     */
    public double getElevationUncertaintyInDeg() {
        return mElevationUncertaintyInDeg;
    }

    /**
     * Sets the elevation's uncertainty (1-Sigma) in degrees.
     */
    public void setElevationUncertaintyInDeg(double value) {
        mHasElevationUncertaintyInDeg = true;
        mElevationUncertaintyInDeg = value;
    }

    /**
     * Resets the elevation's uncertainty (1-Sigma) in degrees.
     */
    public void resetElevationUncertaintyInDeg() {
        mHasElevationUncertaintyInDeg = false;
        mElevationUncertaintyInDeg = Double.NaN;
    }

    /**
     * Returns true if {@link #getAzimuthInDeg()} is available, false otherwise.
     */
    public boolean hasAzimuthInDeg() {
        return mHasAzimuthInDeg;
    }

    /**
     * Gets the azimuth in degrees.
     * Range: [0, 360).
     *
     * The reported azimuth includes {@link #getAzimuthUncertaintyInDeg()}.
     *
     * The value is only available if {@link #hasAzimuthInDeg()} is true.
     */
    public double getAzimuthInDeg() {
        return mAzimuthInDeg;
    }

    /**
     * Sets the Azimuth in degrees.
     */
    public void setAzimuthInDeg(double value) {
        mHasAzimuthInDeg = true;
        mAzimuthInDeg = value;
    }

    /**
     * Resets the Azimuth in degrees.
     */
    public void resetAzimuthInDeg() {
        mHasAzimuthInDeg = false;
        mAzimuthInDeg = Double.NaN;
    }

    /**
     * Returns true if {@link #getAzimuthUncertaintyInDeg()} is available, false otherwise.
     */
    public boolean hasAzimuthUncertaintyInDeg() {
        return mHasAzimuthUncertaintyInDeg;
    }

    /**
     * Gets the azimuth's uncertainty (1-Sigma) in degrees.
     * Range: [0, 180].
     *
     * The uncertainty is represented as an absolute (single sided) value.
     *
     * The value is only available if {@link #hasAzimuthUncertaintyInDeg()} is true.
     */
    public double getAzimuthUncertaintyInDeg() {
        return mAzimuthUncertaintyInDeg;
    }

    /**
     * Sets the Azimuth's uncertainty (1-Sigma) in degrees.
     */
    public void setAzimuthUncertaintyInDeg(double value) {
        mHasAzimuthUncertaintyInDeg = true;
        mAzimuthUncertaintyInDeg = value;
    }

    /**
     * Resets the Azimuth's uncertainty (1-Sigma) in degrees.
     */
    public void resetAzimuthUncertaintyInDeg() {
        mHasAzimuthUncertaintyInDeg = false;
        mAzimuthUncertaintyInDeg = Double.NaN;
    }

    /**
     * Gets a flag indicating whether the GPS represented by the measurement was used for computing
     * the most recent fix.
     *
     * @return A non-null value if the data is available, null otherwise.
     */
    public Boolean isUsedInFix() {
        return mUsedInFix;
    }

    /**
     * Sets the Used-in-Fix flag.
     */
    public void setUsedInFix(boolean value) {
        mUsedInFix = value;
    }

    public static final Creator<GpsMeasurement> CREATOR = new Creator<GpsMeasurement>() {
        @Override
        public GpsMeasurement createFromParcel(Parcel parcel) {
            GpsMeasurement gpsMeasurement = new GpsMeasurement();

            gpsMeasurement.mPrn = parcel.readByte();
            gpsMeasurement.mLocalTimeInNs = parcel.readLong();
            gpsMeasurement.mReceivedGpsTowInNs = parcel.readLong();
            gpsMeasurement.mCn0InDbHz = parcel.readDouble();
            gpsMeasurement.mPseudorangeRateInMetersPerSec = parcel.readDouble();
            gpsMeasurement.mPseudorangeRateUncertaintyInMetersPerSec = parcel.readDouble();
            gpsMeasurement.mAccumulatedDeltaRangeInMeters = parcel.readDouble();
            gpsMeasurement.mAccumulatedDeltaRangeUncertaintyInMeters = parcel.readDouble();

            gpsMeasurement.mHasPseudorangeInMeters = parcel.readInt() != 0;
            gpsMeasurement.mPseudorangeInMeters = parcel.readDouble();
            gpsMeasurement.mHasPseudorangeUncertaintyInMeters = parcel.readInt() != 0;
            gpsMeasurement.mPseudorangeUncertaintyInMeters = parcel.readDouble();
            gpsMeasurement.mHasCodePhaseInChips = parcel.readInt() != 0;
            gpsMeasurement.mCodePhaseInChips = parcel.readDouble();
            gpsMeasurement.mHasCodePhaseUncertaintyInChips = parcel.readInt() != 0;
            gpsMeasurement.mCodePhaseUncertaintyInChips = parcel.readDouble();
            gpsMeasurement.mHasCarrierFrequencyInHz = parcel.readInt() != 0;
            gpsMeasurement.mCarrierFrequencyInHz = parcel.readFloat();
            gpsMeasurement.mHasCarrierCycles = parcel.readInt() != 0;
            gpsMeasurement.mCarrierCycles = parcel.readLong();
            gpsMeasurement.mHasCarrierPhase = parcel.readInt() != 0;
            gpsMeasurement.mCarrierPhase = parcel.readDouble();
            gpsMeasurement.mHasCarrierPhaseUncertainty = parcel.readInt() != 0;
            gpsMeasurement.mCarrierPhaseUncertainty = parcel.readDouble();
            gpsMeasurement.mLossOfLock = (short) parcel.readInt();
            gpsMeasurement.mHasBitNumber = parcel.readInt() != 0;
            gpsMeasurement.mBitNumber = (short) parcel.readInt();
            gpsMeasurement.mHasTimeFromLastBitInNs = parcel.readInt() != 0;
            gpsMeasurement.mTimeFromLastBitInNs = parcel.readLong();
            gpsMeasurement.mHasDopplerShiftInHz = parcel.readInt() != 0;
            gpsMeasurement.mDopplerShiftInHz = parcel.readDouble();
            gpsMeasurement.mHasDopplerShiftUncertaintyInHz = parcel.readInt() != 0;
            gpsMeasurement.mDopplerShiftUncertaintyInHz = parcel.readDouble();
            gpsMeasurement.mMultipathIndicator = (short) parcel.readInt();
            gpsMeasurement.mHasSnrInDb = parcel.readInt() != 0;
            gpsMeasurement.mSnrInDb = parcel.readDouble();
            gpsMeasurement.mHasElevationInDeg = parcel.readInt() != 0;
            gpsMeasurement.mElevationInDeg = parcel.readDouble();
            gpsMeasurement.mHasElevationUncertaintyInDeg = parcel.readInt() != 0;
            gpsMeasurement.mElevationUncertaintyInDeg = parcel.readDouble();
            gpsMeasurement.mHasAzimuthInDeg = parcel.readInt() != 0;
            gpsMeasurement.mAzimuthInDeg = parcel.readDouble();
            gpsMeasurement.mHasAzimuthUncertaintyInDeg = parcel.readInt() != 0;
            gpsMeasurement.mAzimuthUncertaintyInDeg = parcel.readDouble();
            gpsMeasurement.mUsedInFix = parcel.readInt() != 0;

            return gpsMeasurement;
        }

        @Override
        public GpsMeasurement[] newArray(int i) {
            return new GpsMeasurement[i];
        }
    };

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeByte(mPrn);
        parcel.writeLong(mLocalTimeInNs);
        parcel.writeLong(mReceivedGpsTowInNs);
        parcel.writeDouble(mCn0InDbHz);
        parcel.writeDouble(mPseudorangeRateInMetersPerSec);
        parcel.writeDouble(mPseudorangeRateUncertaintyInMetersPerSec);
        parcel.writeDouble(mAccumulatedDeltaRangeInMeters);
        parcel.writeDouble(mAccumulatedDeltaRangeUncertaintyInMeters);

        parcel.writeInt(mHasPseudorangeInMeters ? 1 : 0);
        parcel.writeDouble(mPseudorangeInMeters);
        parcel.writeInt(mHasPseudorangeUncertaintyInMeters ? 1 : 0);
        parcel.writeDouble(mPseudorangeUncertaintyInMeters);
        parcel.writeInt(mHasCodePhaseInChips ? 1 : 0);
        parcel.writeDouble(mCodePhaseInChips);
        parcel.writeInt(mHasCodePhaseUncertaintyInChips ? 1 : 0);
        parcel.writeDouble(mCodePhaseUncertaintyInChips);
        parcel.writeInt(mHasCarrierFrequencyInHz ? 1 : 0);
        parcel.writeFloat(mCarrierFrequencyInHz);
        parcel.writeInt(mHasCarrierCycles ? 1 : 0);
        parcel.writeLong(mCarrierCycles);
        parcel.writeInt(mHasCarrierPhase ? 1 : 0);
        parcel.writeDouble(mCarrierPhase);
        parcel.writeInt(mHasCarrierPhaseUncertainty ? 1 : 0);
        parcel.writeDouble(mCarrierPhaseUncertainty);
        parcel.writeInt(mLossOfLock);
        parcel.writeInt(mHasBitNumber ? 1 : 0);
        parcel.writeInt(mBitNumber);
        parcel.writeInt(mHasTimeFromLastBitInNs ? 1 : 0);
        parcel.writeLong(mTimeFromLastBitInNs);
        parcel.writeInt(mHasDopplerShiftInHz ? 1 : 0);
        parcel.writeDouble(mDopplerShiftInHz);
        parcel.writeInt(mHasDopplerShiftUncertaintyInHz ? 1 : 0);
        parcel.writeDouble(mDopplerShiftUncertaintyInHz);
        parcel.writeInt(mMultipathIndicator);
        parcel.writeInt(mHasSnrInDb ? 1 : 0);
        parcel.writeDouble(mSnrInDb);
        parcel.writeInt(mHasElevationInDeg ? 1 : 0);
        parcel.writeDouble(mElevationInDeg);
        parcel.writeInt(mHasElevationUncertaintyInDeg ? 1 : 0);
        parcel.writeDouble(mElevationUncertaintyInDeg);
        parcel.writeInt(mHasAzimuthInDeg ? 1 : 0);
        parcel.writeDouble(mAzimuthInDeg);
        parcel.writeInt(mHasAzimuthUncertaintyInDeg ? 1 : 0);
        parcel.writeDouble(mAzimuthUncertaintyInDeg);
        parcel.writeInt(mUsedInFix ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-29s = %s\n";
        final String formatWithUncertainty = "   %-29s = %-25s   %-40s = %s\n";
        StringBuilder builder = new StringBuilder("GpsMeasurement:\n");

        builder.append(String.format(format, "Prn", mPrn));

        builder.append(String.format(format, "LocalTimeInNs", mLocalTimeInNs));

        builder.append(String.format(format, "ReceivedGpsTowInNs", mReceivedGpsTowInNs));

        builder.append(String.format(format, "Cn0InDbHz", mCn0InDbHz));

        builder.append(String.format(
                formatWithUncertainty,
                "PseudorangeRateInMetersPerSec",
                mPseudorangeRateInMetersPerSec,
                "PseudorangeRateUncertaintyInMetersPerSec",
                mPseudorangeRateUncertaintyInMetersPerSec));

        builder.append(String.format(
                formatWithUncertainty,
                "AccumulatedDeltaRangeInMeters",
                mAccumulatedDeltaRangeInMeters,
                "AccumulatedDeltaRangeUncertaintyInMeters",
                mAccumulatedDeltaRangeUncertaintyInMeters));


        builder.append(String.format(
                formatWithUncertainty,
                "PseudorangeInMeters",
                mHasPseudorangeInMeters ? mPseudorangeInMeters : null,
                "PseudorangeUncertaintyInMeters",
                mHasPseudorangeUncertaintyInMeters ? mPseudorangeUncertaintyInMeters : null));

        builder.append(String.format(
                formatWithUncertainty,
                "CodePhaseInChips",
                mHasCodePhaseInChips ? mCodePhaseInChips : null,
                "CodePhaseUncertaintyInChips",
                mHasCodePhaseUncertaintyInChips ? mCodePhaseUncertaintyInChips : null));

        builder.append(String.format(
                format,
                "CarrierFrequencyInHz",
                mHasCarrierFrequencyInHz ? mCarrierFrequencyInHz : null));

        builder.append(String.format(
                format,
                "CarrierCycles",
                mHasCarrierCycles ? mCarrierCycles : null));

        builder.append(String.format(
                formatWithUncertainty,
                "CarrierPhase",
                mHasCarrierPhase ? mCarrierPhase : null,
                "CarrierPhaseUncertainty",
                mHasCarrierPhaseUncertainty ? mCarrierPhaseUncertainty : null));

        builder.append(String.format(format, "LossOfLock", getLossOfLockString()));

        builder.append(String.format(
                format,
                "BitNumber",
                mHasBitNumber ? mBitNumber : null));

        builder.append(String.format(
                format,
                "TimeFromLastBitInNs",
                mHasTimeFromLastBitInNs ? mTimeFromLastBitInNs : null));

        builder.append(String.format(
                formatWithUncertainty,
                "DopplerShiftInHz",
                mHasDopplerShiftInHz ? mDopplerShiftInHz : null,
                "DopplerShiftUncertaintyInHz",
                mHasDopplerShiftUncertaintyInHz ? mDopplerShiftUncertaintyInHz : null));

        builder.append(String.format(format, "MultipathIndicator", getMultipathIndicatorString()));

        builder.append(String.format(
                format,
                "SnrInDb",
                mHasSnrInDb ? mSnrInDb : null));

        builder.append(String.format(
                formatWithUncertainty,
                "ElevationInDeg",
                mHasElevationInDeg ? mElevationInDeg : null,
                "ElevationUncertaintyInDeg",
                mHasElevationUncertaintyInDeg ? mElevationUncertaintyInDeg : null));

        builder.append(String.format(
                formatWithUncertainty,
                "AzimuthInDeg",
                mHasAzimuthInDeg ? mAzimuthInDeg : null,
                "AzimuthUncertaintyInDeg",
                mHasAzimuthUncertaintyInDeg ? mAzimuthUncertaintyInDeg : null));

        builder.append(String.format(format, "UsedInFix", mUsedInFix));

        return builder.toString();
    }
}
