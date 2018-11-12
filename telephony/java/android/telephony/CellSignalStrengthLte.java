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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * LTE signal strength related information.
 */
public final class CellSignalStrengthLte extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthLte";
    private static final boolean DBG = false;

    /**
     * Indicates the unknown or undetectable RSSI value in ASU.
     *
     * Reference: TS 27.007 8.5 - Signal quality +CSQ
     */
    private static final int SIGNAL_STRENGTH_LTE_RSSI_ASU_UNKNOWN = 99;
    /**
     * Indicates the maximum valid RSSI value in ASU.
     *
     * Reference: TS 27.007 8.5 - Signal quality +CSQ
     */
    private static final int SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MAX_VALUE = 31;
    /**
     * Indicates the minimum valid RSSI value in ASU.
     *
     * Reference: TS 27.007 8.5 - Signal quality +CSQ
     */
    private static final int SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MIN_VALUE = 0;

    @UnsupportedAppUsage
    private int mSignalStrength;
    @UnsupportedAppUsage
    private int mRsrp;
    @UnsupportedAppUsage
    private int mRsrq;
    @UnsupportedAppUsage
    private int mRssnr;
    @UnsupportedAppUsage
    private int mCqi;
    @UnsupportedAppUsage
    private int mTimingAdvance;

    /** @hide */
    @UnsupportedAppUsage
    public CellSignalStrengthLte() {
        setDefaultValues();
    }

    /** @hide */
    public CellSignalStrengthLte(int signalStrength, int rsrp, int rsrq, int rssnr, int cqi,
            int timingAdvance) {
        mSignalStrength = signalStrength;
        mRsrp = rsrp;
        mRsrq = rsrq;
        mRssnr = rssnr;
        mCqi = cqi;
        mTimingAdvance = timingAdvance;
    }

    /** @hide */
    public CellSignalStrengthLte(CellSignalStrengthLte s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthLte s) {
        mSignalStrength = s.mSignalStrength;
        mRsrp = s.mRsrp;
        mRsrq = s.mRsrq;
        mRssnr = s.mRssnr;
        mCqi = s.mCqi;
        mTimingAdvance = s.mTimingAdvance;
    }

    /** @hide */
    @Override
    public CellSignalStrengthLte copy() {
        return new CellSignalStrengthLte(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mSignalStrength = CellInfo.UNAVAILABLE;
        mRsrp = CellInfo.UNAVAILABLE;
        mRsrq = CellInfo.UNAVAILABLE;
        mRssnr = CellInfo.UNAVAILABLE;
        mCqi = CellInfo.UNAVAILABLE;
        mTimingAdvance = CellInfo.UNAVAILABLE;
    }

    /**
     * Retrieve an abstract level value for the overall signal strength.
     *
     * @return a single integer from 0 to 4 representing the general signal quality.
     *     0 represents very poor signal strength while 4 represents a very strong signal strength.
     */
    @Override
    public int getLevel() {
        int levelRsrp = 0;
        int levelRssnr = 0;

        if (mRsrp == CellInfo.UNAVAILABLE) levelRsrp = 0;
        else if (mRsrp >= -95) levelRsrp = SIGNAL_STRENGTH_GREAT;
        else if (mRsrp >= -105) levelRsrp = SIGNAL_STRENGTH_GOOD;
        else if (mRsrp >= -115) levelRsrp = SIGNAL_STRENGTH_MODERATE;
        else levelRsrp = SIGNAL_STRENGTH_POOR;

        // See RIL_LTE_SignalStrength in ril.h
        if (mRssnr == CellInfo.UNAVAILABLE) levelRssnr = 0;
        else if (mRssnr >= 45) levelRssnr = SIGNAL_STRENGTH_GREAT;
        else if (mRssnr >= 10) levelRssnr = SIGNAL_STRENGTH_GOOD;
        else if (mRssnr >= -30) levelRssnr = SIGNAL_STRENGTH_MODERATE;
        else levelRssnr = SIGNAL_STRENGTH_POOR;

        int level;
        if (mRsrp == CellInfo.UNAVAILABLE) {
            level = levelRssnr;
        } else if (mRssnr == CellInfo.UNAVAILABLE) {
            level = levelRsrp;
        } else {
            level = (levelRssnr < levelRsrp) ? levelRssnr : levelRsrp;
        }

        if (DBG) log("Lte rsrp level: " + levelRsrp
                + " snr level: " + levelRssnr + " level: " + level);
        return level;
    }

    /**
     * Get reference signal received quality
     *
     * @return the RSRQ if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getRsrq() {
        return mRsrq;
    }

    /**
     * Get Received Signal Strength Indication (RSSI) in dBm
     *
     * The value range is [-113, -51] inclusively or {@link CellInfo#UNAVAILABLE} if unavailable.
     *
     * Reference: TS 27.007 8.5 Signal quality +CSQ
     *
     * @return the RSSI if available or {@link CellInfo#UNAVAILABLE} if unavailable.
     */
    public int getRssi() {
        return convertRssiAsuToDBm(mSignalStrength);
    }

    /**
     * Get reference signal signal-to-noise ratio
     *
     * @return the RSSNR if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getRssnr() {
        return mRssnr;
    }

    /**
     * Get reference signal received power in dBm
     *
     * @return the RSRP of the measured cell.
     */
    public int getRsrp() {
        return mRsrp;
    }

    /**
     * Get channel quality indicator
     *
     * @return the CQI if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getCqi() {
        return mCqi;
    }

    /**
     * Get signal strength in dBm
     *
     * @return the RSRP of the measured cell.
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
        if (lteDbm == CellInfo.UNAVAILABLE) lteAsuLevel = 99;
        else if (lteDbm <= -140) lteAsuLevel = 0;
        else if (lteDbm >= -43) lteAsuLevel = 97;
        else lteAsuLevel = lteDbm + 140;
        if (DBG) log("Lte Asu level: "+lteAsuLevel);
        return lteAsuLevel;
    }

    /**
     * Get the timing advance value for LTE, as a value in range of 0..1282.
     * {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} is reported when there is no
     * active RRC connection. Refer to 3GPP 36.213 Sec 4.2.3
     *
     * @return the LTE timing advance if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    public int getTimingAdvance() {
        return mTimingAdvance;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSignalStrength, mRsrp, mRsrq, mRssnr, mCqi, mTimingAdvance);
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
        dest.writeInt(mRsrp * (mRsrp != CellInfo.UNAVAILABLE ? -1 : 1));
        dest.writeInt(mRsrq * (mRsrq != CellInfo.UNAVAILABLE ? -1 : 1));
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
        if (mRsrp != CellInfo.UNAVAILABLE) mRsrp *= -1;
        mRsrq = in.readInt();
        if (mRsrq != CellInfo.UNAVAILABLE) mRsrq *= -1;
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

    private static int convertRssiAsuToDBm(int rssiAsu) {
        if (rssiAsu != SIGNAL_STRENGTH_LTE_RSSI_ASU_UNKNOWN
                && (rssiAsu < SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MIN_VALUE
                || rssiAsu > SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MAX_VALUE)) {
            Rlog.e(LOG_TAG, "convertRssiAsuToDBm: invalid RSSI in ASU=" + rssiAsu);
            return CellInfo.UNAVAILABLE;
        }
        if (rssiAsu == SIGNAL_STRENGTH_LTE_RSSI_ASU_UNKNOWN) {
            return CellInfo.UNAVAILABLE;
        }
        return -113 + (2 * rssiAsu);
    }
}
