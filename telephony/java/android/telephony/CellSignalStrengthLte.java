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
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.telephony.Rlog;

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

    /**
     * Indicates RSRP is considered for {@link #getLevel()} and reported from modem.
     *
     * @hide
     */
    public static final int USE_RSRP = 1 << 0;
    /**
     * Indicates RSRQ is considered for {@link #getLevel()} and reported from modem.
     *
     * @hide
     */
    public static final int USE_RSRQ = 1 << 1;
    /**
     * Indicates RSSNR is considered for {@link #getLevel()} and reported from modem.
     *
     * @hide
     */
    public static final int USE_RSSNR = 1 << 2;

    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mSignalStrength; // To be removed
    private int mRssi;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRsrp;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRsrq;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mRssnr;
    /**
     * CSI channel quality indicator (CQI) table index. There are multiple CQI tables.
     * The definition of CQI in each table is different.
     *
     * Reference: 3GPP TS 136.213 section 7.2.3.
     *
     * Range [1, 6].
     */
    private int mCqiTableIndex;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mCqi;
    @UnsupportedAppUsage(maxTargetSdk = android.os.Build.VERSION_CODES.P)
    private int mTimingAdvance;
    private int mLevel;

    /**
     * Bit-field integer to determine whether to use Reference Signal Received Power (RSRP),
     * Reference Signal Received Quality (RSRQ), and/or Reference Signal Signal to Noise Ratio
     * (RSSNR) for the number of LTE signal bars. If multiple measures are set, the parameter
     * whose signal level value is smallest is used to indicate the signal level.
     *
     *  RSRP = 1 << 0,
     *  RSRQ = 1 << 1,
     *  RSSNR = 1 << 2,
     *
     * For example, if both RSRP and RSRQ are used, the value of key is 3 (1 << 0 | 1 << 1).
     * If the key is invalid or not configured, a default value (RSRP = 1 << 0) will apply.
     */
    private int mParametersUseForLevel;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CellSignalStrengthLte() {
        setDefaultValues();
    }

    /**
     * Construct a cell signal strength
     *
     * @param rssi in dBm [-113,-51], {@link CellInfo#UNAVAILABLE}
     * @param rsrp in dBm [-140,-43], {@link CellInfo#UNAVAILABLE}
     * @param rsrq in dB [-34, 3], {@link CellInfo#UNAVAILABLE}
     * @param rssnr in dB [-20, +30], {@link CellInfo#UNAVAILABLE}
     * @param cqiTableIndex [1, 6], {@link CellInfo#UNAVAILABLE}
     * @param cqi [0, 15], {@link CellInfo#UNAVAILABLE}
     * @param timingAdvance [0, 1282], {@link CellInfo#UNAVAILABLE}
     *
     */
    /** @hide */
    public CellSignalStrengthLte(int rssi, int rsrp, int rsrq, int rssnr, int cqiTableIndex,
            int cqi, int timingAdvance) {
        mRssi = inRangeOrUnavailable(rssi, -113, -51);
        mSignalStrength = mRssi;
        mRsrp = inRangeOrUnavailable(rsrp, -140, -43);
        mRsrq = inRangeOrUnavailable(rsrq, -34, 3);
        mRssnr = inRangeOrUnavailable(rssnr, -20, 30);
        mCqiTableIndex = inRangeOrUnavailable(cqiTableIndex, 1, 6);
        mCqi = inRangeOrUnavailable(cqi, 0, 15);
        mTimingAdvance = inRangeOrUnavailable(timingAdvance, 0, 1282);
        updateLevel(null, null);
    }

    /**
     * Construct a cell signal strength
     *
     * @param rssi in dBm [-113,-51], {@link CellInfo#UNAVAILABLE}
     * @param rsrp in dBm [-140,-43], {@link CellInfo#UNAVAILABLE}
     * @param rsrq in dB [-34, 3], {@link CellInfo#UNAVAILABLE}
     * @param rssnr in dB [-20, +30], {@link CellInfo#UNAVAILABLE}
     * @param cqi [0, 15], {@link CellInfo#UNAVAILABLE}
     * @param timingAdvance [0, 1282], {@link CellInfo#UNAVAILABLE}
     *
     */
    /** @hide */
    public CellSignalStrengthLte(int rssi, int rsrp, int rsrq, int rssnr, int cqi,
            int timingAdvance) {
        this(rssi, rsrp, rsrq, rssnr, CellInfo.UNAVAILABLE, cqi, timingAdvance);
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
        mCqiTableIndex = s.mCqiTableIndex;
        mCqi = s.mCqi;
        mTimingAdvance = s.mTimingAdvance;
        mLevel = s.mLevel;
        mParametersUseForLevel = s.mParametersUseForLevel;
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
        mCqiTableIndex = CellInfo.UNAVAILABLE;
        mCqi = CellInfo.UNAVAILABLE;
        mTimingAdvance = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        mParametersUseForLevel = USE_RSRP;
    }

    /** {@inheritDoc} */
    @Override
    @IntRange(from = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, to = SIGNAL_STRENGTH_GREAT)
    public int getLevel() {
        return mLevel;
    }

    // Lifted from Default carrier configs and max range of RSRP
    private static final int[] sRsrpThresholds = new int[] {
            -115, /* SIGNAL_STRENGTH_POOR */
            -105, /* SIGNAL_STRENGTH_MODERATE */
            -95,  /* SIGNAL_STRENGTH_GOOD */
            -85   /* SIGNAL_STRENGTH_GREAT */
    };

    // Lifted from Default carrier configs and max range of RSRQ
    private static final int[] sRsrqThresholds = new int[] {
            -19, /* SIGNAL_STRENGTH_POOR */
            -17, /* SIGNAL_STRENGTH_MODERATE */
            -14, /* SIGNAL_STRENGTH_GOOD */
            -12  /* SIGNAL_STRENGTH_GREAT */
    };
    // Lifted from Default carrier configs and max range of RSSNR
    private static final int[] sRssnrThresholds = new int[] {
            -3, /* SIGNAL_STRENGTH_POOR */
            1,  /* SIGNAL_STRENGTH_MODERATE */
            5,  /* SIGNAL_STRENGTH_GOOD */
            13  /* SIGNAL_STRENGTH_GREAT */
    };
    private static final int sRsrpBoost = 0;

    /**
     * Checks if the given parameter type is considered to use for {@link #getLevel()}.
     *
     * Note: if multiple parameter types are considered, the smaller level for one of the
     * parameters would be returned by {@link #getLevel()}
     *
     * @param parameterType bitwise OR of {@link #USE_RSRP}, {@link #USE_RSRQ},
     *         {@link #USE_RSSNR}
     * @return {@code true} if the level is calculated based on the given parameter type;
     *      {@code false} otherwise.
     */
    private boolean isLevelForParameter(int parameterType) {
        return (parameterType & mParametersUseForLevel) == parameterType;
    }

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        int[] rsrpThresholds, rsrqThresholds, rssnrThresholds;
        boolean rsrpOnly;
        if (cc == null) {
            mParametersUseForLevel = USE_RSRP;
            rsrpThresholds = sRsrpThresholds;
            rsrqThresholds = sRsrqThresholds;
            rssnrThresholds = sRssnrThresholds;
            rsrpOnly = false;
        } else {
            mParametersUseForLevel = cc.getInt(
                    CarrierConfigManager.KEY_PARAMETERS_USED_FOR_LTE_SIGNAL_BAR_INT);
            if (DBG) {
                Rlog.i(LOG_TAG, "Using signal strength level: " + mParametersUseForLevel);
            }
            rsrpThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY);
            if (rsrpThresholds == null) rsrpThresholds = sRsrpThresholds;
            if (DBG) {
                Rlog.i(LOG_TAG, "Applying LTE RSRP Thresholds: "
                        + Arrays.toString(rsrpThresholds));
            }
            rsrqThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_LTE_RSRQ_THRESHOLDS_INT_ARRAY);
            if (rsrqThresholds == null) rsrqThresholds = sRsrqThresholds;
            if (DBG) {
                Rlog.i(LOG_TAG, "Applying LTE RSRQ Thresholds: "
                        + Arrays.toString(rsrqThresholds));
            }
            rssnrThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_LTE_RSSNR_THRESHOLDS_INT_ARRAY);
            if (rssnrThresholds == null) rssnrThresholds = sRssnrThresholds;
            if (DBG) {
                Rlog.i(LOG_TAG, "Applying LTE RSSNR Thresholds: "
                        + Arrays.toString(rssnrThresholds));
            }
            rsrpOnly = cc.getBoolean(
                    CarrierConfigManager.KEY_USE_ONLY_RSRP_FOR_LTE_SIGNAL_BAR_BOOL, false);
        }

        int rsrpBoost = 0;
        if (ss != null) {
            rsrpBoost = ss.getArfcnRsrpBoost();
        }

        int rsrp = inRangeOrUnavailable(mRsrp + rsrpBoost, MIN_LTE_RSRP, MAX_LTE_RSRP);

        if (rsrpOnly) {
            int level = updateLevelWithMeasure(rsrp, rsrpThresholds);
            if (DBG) log("updateLevel() - rsrp = " + level);
            if (level != SignalStrength.INVALID) {
                mLevel = level;
                return;
            }
        }

        int rsrpLevel = SignalStrength.INVALID;
        int rsrqLevel = SignalStrength.INVALID;
        int rssnrLevel = SignalStrength.INVALID;

        if (isLevelForParameter(USE_RSRP)) {
            rsrpLevel = updateLevelWithMeasure(rsrp, rsrpThresholds);
            if (DBG) {
                Rlog.i(LOG_TAG, "Updated 4G LTE RSRP Level: " + rsrpLevel);
            }
        }
        if (isLevelForParameter(USE_RSRQ)) {
            rsrqLevel = updateLevelWithMeasure(mRsrq, rsrqThresholds);
            if (DBG) {
                Rlog.i(LOG_TAG, "Updated 4G LTE RSRQ Level: " + rsrqLevel);
            }
        }
        if (isLevelForParameter(USE_RSSNR)) {
            rssnrLevel = updateLevelWithMeasure(mRssnr, rssnrThresholds);
            if (DBG) {
                Rlog.i(LOG_TAG, "Updated 4G LTE RSSNR Level: " + rssnrLevel);
            }
        }
        // Apply the smaller value among three levels of three measures.
        mLevel = Math.min(Math.min(rsrpLevel, rsrqLevel), rssnrLevel);

        if (mLevel == SignalStrength.INVALID) {
            int rssiLevel;
            if (mRssi > -51) {
                rssiLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            } else if (mRssi >= -89) {
                rssiLevel = SIGNAL_STRENGTH_GREAT;
            } else if (mRssi >= -97) {
                rssiLevel = SIGNAL_STRENGTH_GOOD;
            } else if (mRssi >= -103) {
                rssiLevel = SIGNAL_STRENGTH_MODERATE;
            } else if (mRssi >= -113) {
                rssiLevel = SIGNAL_STRENGTH_POOR;
            } else {
                rssiLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            }
            if (DBG) log("getLteLevel - rssi:" + mRssi + " rssiIconLevel:" + rssiLevel);
            mLevel = rssiLevel;
        }
    }

    /**
     * Update level with corresponding measure and thresholds.
     *
     * @param measure corresponding signal measure
     * @param thresholds corresponding signal thresholds
     * @return level of the signal strength
     */
    private int updateLevelWithMeasure(int measure, int[] thresholds) {
        int level;
        if (measure == CellInfo.UNAVAILABLE) {
            level = SignalStrength.INVALID;
        } else if (measure >= thresholds[3]) {
            level = SIGNAL_STRENGTH_GREAT;
        } else if (measure >= thresholds[2]) {
            level = SIGNAL_STRENGTH_GOOD;
        } else if (measure >= thresholds[1]) {
            level = SIGNAL_STRENGTH_MODERATE;
        } else if (measure >= thresholds[0]) {
            level = SIGNAL_STRENGTH_POOR;
        } else {
            level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
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
        return mRssi;
    }

    /**
     * Get reference signal signal-to-noise ratio in dB
     * Range: -20 dB to +30 dB.
     *
     * @return the RSSNR if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE} if unavailable.
     */
    public int getRssnr() {
        return mRssnr;
    }

    /**
     * Get reference signal received power in dBm
     * Range: -140 dBm to -43 dBm.
     *
     * @return the RSRP of the measured cell or {@link CellInfo#UNAVAILABLE} if
     * unavailable.
     */
    public int getRsrp() {
        return mRsrp;
    }

    /**
     * Get table index for channel quality indicator
     *
     * Reference: 3GPP TS 136.213 section 7.2.3.
     *
     * @return the CQI table index if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    @IntRange(from = 1, to = 6)
    public int getCqiTableIndex() {
        return mCqiTableIndex;
    }

    /**
     * Get channel quality indicator
     *
     * Reference: 3GPP TS 136.213 section 7.2.3.
     *
     * @return the CQI if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    @IntRange(from = 0, to = 15)
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
        return Objects.hash(mRssi, mRsrp, mRsrq, mRssnr, mCqiTableIndex, mCqi, mTimingAdvance,
                mLevel);
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
                && mCqiTableIndex == s.mCqiTableIndex
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
                + " cqiTableIndex=" + mCqiTableIndex
                + " cqi=" + mCqi
                + " ta=" + mTimingAdvance
                + " level=" + mLevel
                + " parametersUseForLevel=" + mParametersUseForLevel;
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
        dest.writeInt(mCqiTableIndex);
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
        mCqiTableIndex = in.readInt();
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

    /** @hide */
    public static int convertRssnrUnitFromTenDbToDB(int rssnr) {
        return (int) Math.floor((float) rssnr / 10);
    }

    /** @hide */
    public static int convertRssiAsuToDBm(int rssiAsu) {
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
