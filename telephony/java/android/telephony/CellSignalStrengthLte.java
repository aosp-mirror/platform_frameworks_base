/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;

/**
 * LTE signal strength related information.
 */
public final class CellSignalStrengthLte extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthLte";
    private static final boolean DBG = false;

    private int mSignalStrength;
    private int mRsrp;
    private int mRsrq;
    private int mRssnr;
    private int mCqi;
    private int mTimingAdvance;

    /**
     * Empty constructor
     *
     * @hide
     */
    public CellSignalStrengthLte() {
        setDefaultValues();
    }

    /**
     * Constructor
     *
     * @hide
     */
    public CellSignalStrengthLte(int signalStrength, int rsrp, int rsrq, int rssnr, int cqi,
            int timingAdvance) {
        initialize(signalStrength, rsrp, rsrq, rssnr, cqi, timingAdvance);
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public CellSignalStrengthLte(CellSignalStrengthLte s) {
        copyFrom(s);
    }

    /**
     * Initialize all the values
     *
     * @param lteSignalStrength
     * @param rsrp
     * @param rsrq
     * @param rssnr
     * @param cqi
     *
     * @hide
     */
    public void initialize(int lteSignalStrength, int rsrp, int rsrq, int rssnr, int cqi,
            int timingAdvance) {
        mSignalStrength = lteSignalStrength;
        mRsrp = rsrp;
        mRsrq = rsrq;
        mRssnr = rssnr;
        mCqi = cqi;
        mTimingAdvance = timingAdvance;
    }

    /**
     * Initialize from the SignalStrength structure.
     *
     * @param ss
     *
     * @hide
     */
    public void initialize(SignalStrength ss, int timingAdvance) {
        mSignalStrength = ss.getLteSignalStrength();
        mRsrp = ss.getLteRsrp();
        mRsrq = ss.getLteRsrq();
        mRssnr = ss.getLteRssnr();
        mCqi = ss.getLteCqi();
        mTimingAdvance = timingAdvance;
    }

    /**
     * @hide
     */
    protected void copyFrom(CellSignalStrengthLte s) {
        mSignalStrength = s.mSignalStrength;
        mRsrp = s.mRsrp;
        mRsrq = s.mRsrq;
        mRssnr = s.mRssnr;
        mCqi = s.mCqi;
        mTimingAdvance = s.mTimingAdvance;
    }

    /**
     * @hide
     */
    @Override
    public CellSignalStrengthLte copy() {
        return new CellSignalStrengthLte(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mSignalStrength = Integer.MAX_VALUE;
        mRsrp = Integer.MAX_VALUE;
        mRsrq = Integer.MAX_VALUE;
        mRssnr = Integer.MAX_VALUE;
        mCqi = Integer.MAX_VALUE;
        mTimingAdvance = Integer.MAX_VALUE;
    }

    /**
     * Get signal level as an int from 0..4
     */
    @Override
    public int getLevel() {
        int levelRsrp = 0;
        int levelRssnr = 0;

        if (mRsrp == Integer.MAX_VALUE) levelRsrp = 0;
        else if (mRsrp >= -95) levelRsrp = SIGNAL_STRENGTH_GREAT;
        else if (mRsrp >= -105) levelRsrp = SIGNAL_STRENGTH_GOOD;
        else if (mRsrp >= -115) levelRsrp = SIGNAL_STRENGTH_MODERATE;
        else levelRsrp = SIGNAL_STRENGTH_POOR;

        // See RIL_LTE_SignalStrength in ril.h
        if (mRssnr == Integer.MAX_VALUE) levelRssnr = 0;
        else if (mRssnr >= 45) levelRssnr = SIGNAL_STRENGTH_GREAT;
        else if (mRssnr >= 10) levelRssnr = SIGNAL_STRENGTH_GOOD;
        else if (mRssnr >= -30) levelRssnr = SIGNAL_STRENGTH_MODERATE;
        else levelRssnr = SIGNAL_STRENGTH_POOR;

        int level;
        if (mRsrp == Integer.MAX_VALUE)
            level = levelRssnr;
        else if (mRssnr == Integer.MAX_VALUE)
            level = levelRsrp;
        else
            level = (levelRssnr < levelRsrp) ? levelRssnr : levelRsrp;

        if (DBG) log("Lte rsrp level: " + levelRsrp
                + " snr level: " + levelRssnr + " level: " + level);
        return level;
    }

    /**
     * Get reference signal received quality
     */
    public int getRsrq() {
        return mRsrq;
    }

    /**
     * Get reference signal signal-to-noise ratio
     */
    public int getRssnr() {
        return mRssnr;
    }

    /**
     * Get reference signal received power
     */
    public int getRsrp() {
        return mRsrp;
    }

    /**
     * Get channel quality indicator
     */
    public int getCqi() {
        return mCqi;
    }

    /**
     * Get signal strength as dBm
     */
    @Override
    public int getDbm() {
        return mRsrp;
    }

    /**
     * Get the LTE signal level as an asu value between 0..97, 99 is unknown
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     */
    @Override
    public int getAsuLevel() {
        int lteAsuLevel = 99;
        int lteDbm = getDbm();
        if (lteDbm == Integer.MAX_VALUE) lteAsuLevel = 99;
        else if (lteDbm <= -140) lteAsuLevel = 0;
        else if (lteDbm >= -43) lteAsuLevel = 97;
        else lteAsuLevel = lteDbm + 140;
        if (DBG) log("Lte Asu level: "+lteAsuLevel);
        return lteAsuLevel;
    }

    /**
     * Get the timing advance value for LTE, as a value between 0..63.
     * Integer.MAX_VALUE is reported when there is no active RRC
     * connection. Refer to 3GPP 36.213 Sec 4.2.3
     * @return the LTE timing advance, if available.
     */
    public int getTimingAdvance() {
        return mTimingAdvance;
    }

    @Override
    public int hashCode() {
        int primeNum = 31;
        return (mSignalStrength * primeNum) + (mRsrp * primeNum)
                + (mRsrq * primeNum) + (mRssnr * primeNum) + (mCqi * primeNum)
                + (mTimingAdvance * primeNum);
    }

    @Override
    public boolean equals (Object o) {
        CellSignalStrengthLte s;

        try {
            s = (CellSignalStrengthLte) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return mSignalStrength == s.mSignalStrength
                && mRsrp == s.mRsrp
                && mRsrq == s.mRsrq
                && mRssnr == s.mRssnr
                && mCqi == s.mCqi
                && mTimingAdvance == s.mTimingAdvance;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthLte:"
                + " ss=" + mSignalStrength
                + " rsrp=" + mRsrp
                + " rsrq=" + mRsrq
                + " rssnr=" + mRssnr
                + " cqi=" + mCqi
                + " ta=" + mTimingAdvance;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mSignalStrength);
        // Need to multiply rsrp and rsrq by -1
        // to ensure consistency when reading values written here
        // unless the values are invalid
        dest.writeInt(mRsrp * (mRsrp != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mRsrq * (mRsrq != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mRssnr);
        dest.writeInt(mCqi);
        dest.writeInt(mTimingAdvance);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the token is already been processed.
     */
    private CellSignalStrengthLte(Parcel in) {
        mSignalStrength = in.readInt();
        // rsrp and rsrq are written into the parcel as positive values.
        // Need to convert into negative values unless the values are invalid
        mRsrp = in.readInt();
        if (mRsrp != Integer.MAX_VALUE) mRsrp *= -1;
        mRsrq = in.readInt();
        if (mRsrq != Integer.MAX_VALUE) mRsrq *= -1;
        mRssnr = in.readInt();
        mCqi = in.readInt();
        mTimingAdvance = in.readInt();
        if (DBG) log("CellSignalStrengthLte(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Parcelable.Creator<CellSignalStrengthLte> CREATOR =
            new Parcelable.Creator<CellSignalStrengthLte>() {
        @Override
        public CellSignalStrengthLte createFromParcel(Parcel in) {
            return new CellSignalStrengthLte(in);
        }

        @Override
        public CellSignalStrengthLte[] newArray(int size) {
            return new CellSignalStrengthLte[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
