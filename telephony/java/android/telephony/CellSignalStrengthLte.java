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

import android.annotation.IntRange;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.Arrays;
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

    private static final int MAX_LTE_RSRP = -44;
    private static final int MIN_LTE_RSRP = -140;

    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mSignalStrength; // To be removed
    private int mRssi;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRsrp;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRsrq;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRssnr;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mCqi;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mTimingAdvance;
    private int mLevel;

    /** @hide */
    @UnsupportedAppUsage
    public CellSignalStrengthLte() {
        setDefaultValues();
    }

    /**
     * Construct a cell signal strength
     *
     * @param rssi in dBm [-113,-51], UNKNOWN
     * @param rsrp in dBm [-140,-43], UNKNOWN
     * @param rsrq in dB [-20,-3], UNKNOWN
     * @param rssnr in 10*dB [-200, +300], UNKNOWN
     * @param cqi [0, 15], UNKNOWN
     * @param timingAdvance [0, 1282], UNKNOWN
     *
     */
    /** @hide */
    public CellSignalStrengthLte(int rssi, int rsrp, int rsrq, int rssnr, int cqi,
            int timingAdvance) {

        mRssi = inRangeOrUnavailable(rssi, -113, -51);
        mSignalStrength = mRssi;
        mRsrp = inRangeOrUnavailable(rsrp, -140, -43);
        mRsrq = inRangeOrUnavailable(rsrq, -20, -3);
        mRssnr = inRangeOrUnavailable(rssnr, -200, 300);
        mCqi = inRangeOrUnavailable(cqi, 0, 15);
        mTimingAdvance = inRangeOrUnavailable(timingAdvance, 0, 1282);
        updateLevel(null, null);
    }

    /** @hide */
    public CellSignalStrengthLte(android.hardware.radio.V1_0.LteSignalStrength lte) {
        // Convert from HAL values as part of construction.
        this(convertRssiAsuToDBm(lte.signalStrength),
                lte.rsrp != CellInfo.UNAVAILABLE ? -lte.rsrp : lte.rsrp,
                lte.rsrq != CellInfo.UNAVAILABLE ? -lte.rsrq : lte.rsrq,
                lte.rssnr, lte.cqi, lte.timingAdvance);
    }

    /** @hide */
    public CellSignalStrengthLte(CellSignalStrengthLte s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthLte s) {
        mSignalStrength = s.mSignalStrength;
        mRssi = s.mRssi;
        mRsrp = s.mRsrp;
        mRsrq = s.mRsrq;
        mRssnr = s.mRssnr;
        mCqi = s.mCqi;
        mTimingAdvance = s.mTimingAdvance;
        mLevel = s.mLevel;
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
        mRssi = CellInfo.UNAVAILABLE;
        mRsrp = CellInfo.UNAVAILABLE;
        mRsrq = CellInfo.UNAVAILABLE;
        mRssnr = CellInfo.UNAVAILABLE;
        mCqi = CellInfo.UNAVAILABLE;
        mTimingAdvance = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /** {@inheritDoc} */
    @Override
    @IntRange(from = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, to = SIGNAL_STRENGTH_GREAT)
    public int getLevel() {
        return mLevel;
    }

    // Lifted from Default carrier configs and max range of RSRP
    private static final int[] sThresholds = new int[]{-115, -105, -95, -85};
    private static final int sRsrpBoost = 0;

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        int[] thresholds;
        boolean rsrpOnly;
        if (cc == null) {
            thresholds = sThresholds;
            rsrpOnly = false;
        } else {
            rsrpOnly = cc.getBoolean(
                    CarrierConfigManager.KEY_USE_ONLY_RSRP_FOR_LTE_SIGNAL_BAR_BOOL, false);
            thresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY);
            if (thresholds == null) thresholds = sThresholds;
            if (DBG) log("updateLevel() carrierconfig - rsrpOnly="
                    + rsrpOnly + ", thresholds=" + Arrays.toString(thresholds));
        }


        int rsrpBoost = 0;
        if (ss != null) {
            rsrpBoost = ss.getLteEarfcnRsrpBoost();
        }

        int rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        int rsrpIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        int snrIconLevel = -1;

        int rsrp = mRsrp + rsrpBoost;

        if (rsrp < MIN_LTE_RSRP || rsrp > MAX_LTE_RSRP) {
            rsrpIconLevel = -1;
        } else {
            rsrpIconLevel = thresholds.length;
            while (rsrpIconLevel > 0 && rsrp < thresholds[rsrpIconLevel - 1]) rsrpIconLevel--;
        }

        if (rsrpOnly) {
            if (DBG) log("updateLevel() - rsrp = " + rsrpIconLevel);
            if (rsrpIconLevel != -1) {
                mLevel = rsrpIconLevel;
                return;
            }
        }

        /*
         * Values are -200 dB to +300 (SNR*10dB) RS_SNR >= 13.0 dB =>4 bars 4.5
         * dB <= RS_SNR < 13.0 dB => 3 bars 1.0 dB <= RS_SNR < 4.5 dB => 2 bars
         * -3.0 dB <= RS_SNR < 1.0 dB 1 bar RS_SNR < -3.0 dB/No Service Antenna
         * Icon Only
         */
        if (mRssnr > 300) snrIconLevel = -1;
        else if (mRssnr >= 130) snrIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mRssnr >= 45) snrIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mRssnr >= 10) snrIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mRssnr >= -30) snrIconLevel = SIGNAL_STRENGTH_POOR;
        else if (mRssnr >= -200)
            snrIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (DBG) log("updateLevel() - rsrp:" + mRsrp + " snr:" + mRssnr + " rsrpIconLevel:"
                + rsrpIconLevel + " snrIconLevel:" + snrIconLevel
                + " lteRsrpBoost:" + sRsrpBoost);

        /* Choose a measurement type to use for notification */
        if (snrIconLevel != -1 && rsrpIconLevel != -1) {
            /*
             * The number of bars displayed shall be the smaller of the bars
             * associated with LTE RSRP and the bars associated with the LTE
             * RS_SNR
             */
            mLevel = (rsrpIconLevel < snrIconLevel ? rsrpIconLevel : snrIconLevel);
            return;
        }

        if (snrIconLevel != -1) {
            mLevel = snrIconLevel;
            return;
        }

        if (rsrpIconLevel != -1) {
            mLevel = rsrpIconLevel;
            return;
        }

        if (mRssi > -51) rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (mRssi >= -89) rssiIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mRssi >= -97) rssiIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mRssi >= -103) rssiIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mRssi >= -113) rssiIconLevel = SIGNAL_STRENGTH_POOR;
        else rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (DBG) log("getLteLevel - rssi:" + mRssi + " rssiIconLevel:"
                + rssiIconLevel);
        mLevel = rssiIconLevel;
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
        return mRssi;
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
     * Get the RSRP in ASU.
     *
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSCP in ASU 0..97, 255, or UNAVAILABLE
     */
    @Override
    public int getAsuLevel() {
        int lteAsuLevel = 99;
        int lteDbm = mRsrp;
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
        return Objects.hash(mRssi, mRsrp, mRsrq, mRssnr, mCqi, mTimingAdvance, mLevel);
    }

    private static final CellSignalStrengthLte sInvalid = new CellSignalStrengthLte();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals (Object o) {
        CellSignalStrengthLte s;

        if (!(o instanceof CellSignalStrengthLte)) return false;
        s = (CellSignalStrengthLte) o;

        return mRssi == s.mRssi
                && mRsrp == s.mRsrp
                && mRsrq == s.mRsrq
                && mRssnr == s.mRssnr
                && mCqi == s.mCqi
                && mTimingAdvance == s.mTimingAdvance
                && mLevel == s.mLevel;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthLte:"
                + " rssi=" + mRssi
                + " rsrp=" + mRsrp
                + " rsrq=" + mRsrq
                + " rssnr=" + mRssnr
                + " cqi=" + mCqi
                + " ta=" + mTimingAdvance
                + " level=" + mLevel;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mRssi);
        // Need to multiply rsrp and rsrq by -1
        // to ensure consistency when reading values written here
        // unless the values are invalid
        dest.writeInt(mRsrp);
        dest.writeInt(mRsrq);
        dest.writeInt(mRssnr);
        dest.writeInt(mCqi);
        dest.writeInt(mTimingAdvance);
        dest.writeInt(mLevel);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the token is already been processed.
     */
    private CellSignalStrengthLte(Parcel in) {
        mRssi = in.readInt();
        mSignalStrength = mRssi;
        mRsrp = in.readInt();
        mRsrq = in.readInt();
        mRssnr = in.readInt();
        mCqi = in.readInt();
        mTimingAdvance = in.readInt();
        mLevel = in.readInt();
        if (DBG) log("CellSignalStrengthLte(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Parcelable.Creator<CellSignalStrengthLte> CREATOR =
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
        if (rssiAsu == SIGNAL_STRENGTH_LTE_RSSI_ASU_UNKNOWN) {
            return CellInfo.UNAVAILABLE;
        }
        if ((rssiAsu < SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MIN_VALUE
                || rssiAsu > SIGNAL_STRENGTH_LTE_RSSI_VALID_ASU_MAX_VALUE)) {
            Rlog.e(LOG_TAG, "convertRssiAsuToDBm: invalid RSSI in ASU=" + rssiAsu);
            return CellInfo.UNAVAILABLE;
        }
        return -113 + (2 * rssiAsu);
    }
}
