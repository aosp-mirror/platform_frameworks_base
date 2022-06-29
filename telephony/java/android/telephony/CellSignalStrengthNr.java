/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 5G NR signal strength related information.
 */
public final class CellSignalStrengthNr extends CellSignalStrength implements Parcelable {
    /**
     * The value is used to indicate that the asu level is unknown.
     * Reference: 3GPP TS 27.007 section 8.69.
     * @hide
     */
    public static final int UNKNOWN_ASU_LEVEL = 99;

    private static final boolean VDBG = false;

    private static final String TAG = "CellSignalStrengthNr";

    // Lifted from Default carrier configs and max range of SSRSRP
    // Boundaries: [-140 dB, -44 dB]
    private int[] mSsRsrpThresholds = new int[] {
            -110, /* SIGNAL_STRENGTH_POOR */
            -90, /* SIGNAL_STRENGTH_MODERATE */
            -80, /* SIGNAL_STRENGTH_GOOD */
            -65,  /* SIGNAL_STRENGTH_GREAT */
    };

    // Lifted from Default carrier configs and max range of SSRSRQ
    // Boundaries: [-43 dB, 20 dB]
    private int[] mSsRsrqThresholds = new int[] {
            -31, /* SIGNAL_STRENGTH_POOR */
            -19, /* SIGNAL_STRENGTH_MODERATE */
            -7, /* SIGNAL_STRENGTH_GOOD */
            6  /* SIGNAL_STRENGTH_GREAT */
    };

    // Lifted from Default carrier configs and max range of SSSINR
    // Boundaries: [-23 dB, 40 dB]
    private int[] mSsSinrThresholds = new int[] {
            -5, /* SIGNAL_STRENGTH_POOR */
            5, /* SIGNAL_STRENGTH_MODERATE */
            15, /* SIGNAL_STRENGTH_GOOD */
            30  /* SIGNAL_STRENGTH_GREAT */
    };

    /**
     * Indicates SSRSRP is considered for {@link #getLevel()} and reporting from modem.
     *
     * @hide
     */
    public static final int USE_SSRSRP = 1 << 0;
    /**
     * Indicates SSRSRQ is considered for {@link #getLevel()} and reporting from modem.
     *
     * @hide
     */
    public static final int USE_SSRSRQ = 1 << 1;
    /**
     * Indicates SSSINR is considered for {@link #getLevel()} and reporting from modem.
     *
     * @hide
     */
    public static final int USE_SSSINR = 1 << 2;

    /**
     * Bit-field integer to determine whether to use SS reference signal received power (SSRSRP),
     * SS reference signal received quality (SSRSRQ), or/and SS signal-to-noise and interference
     * ratio (SSSINR) for the number of 5G NR signal bars. If multiple measures are set bit, the
     * parameter whose value is smallest is used to indicate the signal bar.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "USE_" }, value = {
        USE_SSRSRP,
        USE_SSRSRQ,
        USE_SSSINR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignalLevelAndReportCriteriaSource {}

    private int mCsiRsrp;
    private int mCsiRsrq;
    private int mCsiSinr;
    /**
     * CSI channel quality indicator (CQI) table index. There are multiple CQI tables.
     * The definition of CQI in each table is different.
     *
     * Reference: 3GPP TS 138.214 section 5.2.2.1.
     *
     * Range [1, 3].
     */
    private int mCsiCqiTableIndex;
    /**
     * CSI channel quality indicators (CQI) for all subbands.
     *
     * If the CQI report is for the entire wideband, a single CQI index is provided.
     * If the CQI report is for all subbands, one CQI index is provided for each subband,
     * in ascending order of subband index.
     * If CQI is not available, the CQI report is empty.
     *
     * Reference: 3GPP TS 138.214 section 5.2.2.1.
     *
     * Range [0, 15] for each CQI.
     */
    private List<Integer> mCsiCqiReport;
    private int mSsRsrp;
    private int mSsRsrq;
    private int mSsSinr;
    private int mLevel;

    /**
     * Bit-field integer to determine whether to use SS reference signal received power (SSRSRP),
     * SS reference signal received quality (SSRSRQ), or/and SS signal-to-noise and interference
     * ratio (SSSINR) for the number of 5G NR signal bars. If multiple measures are set bit, the
     * parameter whose value is smallest is used to indicate the signal bar.
     *
     *  SSRSRP = 1 << 0,
     *  SSRSRQ = 1 << 1,
     *  SSSINR = 1 << 2,
     *
     * For example, if both SSRSRP and SSSINR are used, the value of key is 5 (1 << 0 | 1 << 2).
     * If the key is invalid or not configured, a default value (SSRSRP = 1 << 0) will apply.
     */
    private int mParametersUseForLevel;

    /** @hide */
    public CellSignalStrengthNr() {
        setDefaultValues();
    }

    /**
     * @param csiRsrp CSI reference signal received power.
     * @param csiRsrq CSI reference signal received quality.
     * @param csiSinr CSI signal-to-noise and interference ratio.
     * @param csiCqiTableIndex CSI CSI channel quality indicator (CQI) table index.
     * @param csiCqiReport CSI channel quality indicators (CQI) for all subbands.
     * @param ssRsrp SS reference signal received power.
     * @param ssRsrq SS reference signal received quality.
     * @param ssSinr SS signal-to-noise and interference ratio.
     * @hide
     */
    public CellSignalStrengthNr(int csiRsrp, int csiRsrq, int csiSinr, int csiCqiTableIndex,
            List<Byte> csiCqiReport, int ssRsrp, int ssRsrq, int ssSinr) {
        mCsiRsrp = inRangeOrUnavailable(csiRsrp, -140, -44);
        mCsiRsrq = inRangeOrUnavailable(csiRsrq, -20, -3);
        mCsiSinr = inRangeOrUnavailable(csiSinr, -23, 23);
        mCsiCqiTableIndex = inRangeOrUnavailable(csiCqiTableIndex, 1, 3);
        mCsiCqiReport = csiCqiReport.stream()
                .map(cqi -> new Integer(inRangeOrUnavailable(Byte.toUnsignedInt(cqi), 0, 15)))
                .collect(Collectors.toList());
        mSsRsrp = inRangeOrUnavailable(ssRsrp, -140, -44);
        mSsRsrq = inRangeOrUnavailable(ssRsrq, -43, 20);
        mSsSinr = inRangeOrUnavailable(ssSinr, -23, 40);
        updateLevel(null, null);
    }

    /**
     * @param csiRsrp CSI reference signal received power.
     * @param csiRsrq CSI reference signal received quality.
     * @param csiSinr CSI signal-to-noise and interference ratio.
     * @param ssRsrp SS reference signal received power.
     * @param ssRsrq SS reference signal received quality.
     * @param ssSinr SS signal-to-noise and interference ratio.
     * @hide
     */
    public CellSignalStrengthNr(
            int csiRsrp, int csiRsrq, int csiSinr, int ssRsrp, int ssRsrq, int ssSinr) {
        this(csiRsrp, csiRsrq, csiSinr, CellInfo.UNAVAILABLE, Collections.emptyList(),
                ssRsrp, ssRsrq, ssSinr);
    }

    /**
     * Flip sign cell strength value when taking in the value from hal
     * @param val cell strength value
     * @return flipped value
     * @hide
     */
    public static int flip(int val) {
        return val != CellInfo.UNAVAILABLE ? -val : val;
    }

    /**
     * Reference: 3GPP TS 38.215.
     * Range: -140 dBm to -44 dBm.
     * @return SS reference signal received power, {@link CellInfo#UNAVAILABLE} means unreported
     * value.
     */
    public int getSsRsrp() {
        return mSsRsrp;
    }

    /**
     * Reference: 3GPP TS 38.215; 3GPP TS 38.133 section 10
     * Range: -43 dB to 20 dB.
     * @return SS reference signal received quality, {@link CellInfo#UNAVAILABLE} means unreported
     * value.
     */
    public int getSsRsrq() {
        return mSsRsrq;
    }

    /**
     * Reference: 3GPP TS 38.215 Sec 5.1.*, 3GPP TS 38.133 10.1.16.1
     * Range: -23 dB to 40 dB
     * @return SS signal-to-noise and interference ratio, {@link CellInfo#UNAVAILABLE} means
     * unreported value.
     */
    public int getSsSinr() {
        return mSsSinr;
    }

    /**
     * Reference: 3GPP TS 38.215.
     * Range: -140 dBm to -44 dBm.
     * @return CSI reference signal received power, {@link CellInfo#UNAVAILABLE} means unreported
     * value.
     */
    public int getCsiRsrp() {
        return mCsiRsrp;
    }

    /**
     * Reference: 3GPP TS 38.215.
     * Range: -20 dB to -3 dB.
     * @return CSI reference signal received quality, {@link CellInfo#UNAVAILABLE} means unreported
     * value.
     */
    public int getCsiRsrq() {
        return mCsiRsrq;
    }

    /**
     * Reference: 3GPP TS 38.215 Sec 5.1.*, 3GPP TS 38.133 10.1.16.1
     * Range: -23 dB to 23 dB
     * @return CSI signal-to-noise and interference ratio, {@link CellInfo#UNAVAILABLE} means
     * unreported value.
     */
    public int getCsiSinr() {
        return mCsiSinr;
    }

    /**
     * Return CSI channel quality indicator (CQI) table index. There are multiple CQI tables.
     * The definition of CQI in each table is different.
     *
     * Reference: 3GPP TS 138.214 section 5.2.2.1.
     *
     * @return the CQI table index if available or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable.
     */
    @IntRange(from = 1, to = 3)
    public int getCsiCqiTableIndex() {
        return mCsiCqiTableIndex;
    }
    /**
     * Return a list of CSI channel quality indicators (CQI) for all subbands.
     *
     * If the CQI report is for the entire wideband, a single CQI index is provided.
     * If the CQI report is for all subbands, one CQI index is provided for each subband,
     * in ascending order of subband index.
     * If CQI is not available, the CQI report is empty.
     *
     * Reference: 3GPP TS 138.214 section 5.2.2.1.
     *
     * @return the CQIs for all subbands if available or empty list if unavailable.
     */
    @NonNull
    @IntRange(from = 0, to = 15)
    public List<Integer> getCsiCqiReport() {
        return mCsiCqiReport;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCsiRsrp);
        dest.writeInt(mCsiRsrq);
        dest.writeInt(mCsiSinr);
        dest.writeInt(mCsiCqiTableIndex);
        dest.writeList(mCsiCqiReport);
        dest.writeInt(mSsRsrp);
        dest.writeInt(mSsRsrq);
        dest.writeInt(mSsSinr);
        dest.writeInt(mLevel);
    }

    private CellSignalStrengthNr(Parcel in) {
        mCsiRsrp = in.readInt();
        mCsiRsrq = in.readInt();
        mCsiSinr = in.readInt();
        mCsiCqiTableIndex = in.readInt();
        mCsiCqiReport = in.readArrayList(Integer.class.getClassLoader(), java.lang.Integer.class);
        mSsRsrp = in.readInt();
        mSsRsrq = in.readInt();
        mSsSinr = in.readInt();
        mLevel = in.readInt();
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mCsiRsrp = CellInfo.UNAVAILABLE;
        mCsiRsrq = CellInfo.UNAVAILABLE;
        mCsiSinr = CellInfo.UNAVAILABLE;
        mCsiCqiTableIndex = CellInfo.UNAVAILABLE;
        mCsiCqiReport = Collections.emptyList();
        mSsRsrp = CellInfo.UNAVAILABLE;
        mSsRsrq = CellInfo.UNAVAILABLE;
        mSsSinr = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        mParametersUseForLevel = USE_SSRSRP;
    }

    /** {@inheritDoc} */
    @Override
    @IntRange(from = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, to = SIGNAL_STRENGTH_GREAT)
    public int getLevel() {
        return mLevel;
    }

    /**
     * Checks if the given parameter type is considered to use for {@link #getLevel()}.
     *
     * Note: if multiple parameter types are considered, the smaller level for one of the
     * parameters would be returned by {@link #getLevel()}
     *
     * @param parameterType bitwise OR of {@link #USE_SSRSRP}, {@link #USE_SSRSRQ},
     *         {@link #USE_SSSINR}
     * @return {@code true} if the level is calculated based on the given parameter type;
     *      {@code false} otherwise.
     *
     */
    private boolean isLevelForParameter(@SignalLevelAndReportCriteriaSource int parameterType) {
        return (parameterType & mParametersUseForLevel) == parameterType;
    }

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        if (cc == null) {
            mParametersUseForLevel = USE_SSRSRP;
        } else {
            mParametersUseForLevel = cc.getInt(
                    CarrierConfigManager.KEY_PARAMETERS_USE_FOR_5G_NR_SIGNAL_BAR_INT, USE_SSRSRP);
            mSsRsrpThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY);
            if (VDBG) {
                Rlog.i(TAG, "Applying 5G NR SSRSRP Thresholds: "
                        + Arrays.toString(mSsRsrpThresholds));
            }
            mSsRsrqThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_5G_NR_SSRSRQ_THRESHOLDS_INT_ARRAY);
            if (VDBG) {
                Rlog.i(TAG, "Applying 5G NR SSRSRQ Thresholds: "
                        + Arrays.toString(mSsRsrqThresholds));
            }
            mSsSinrThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_5G_NR_SSSINR_THRESHOLDS_INT_ARRAY);
            if (VDBG) {
                Rlog.i(TAG, "Applying 5G NR SSSINR Thresholds: "
                        + Arrays.toString(mSsSinrThresholds));
            }
        }
        int ssRsrpLevel = SignalStrength.INVALID;
        int ssRsrqLevel = SignalStrength.INVALID;
        int ssSinrLevel = SignalStrength.INVALID;
        if (isLevelForParameter(USE_SSRSRP)) {
            int rsrpBoost = 0;
            if (ss != null) {
                rsrpBoost = ss.getArfcnRsrpBoost();
            }
            ssRsrpLevel = updateLevelWithMeasure(mSsRsrp + rsrpBoost, mSsRsrpThresholds);
            if (VDBG) {
                Rlog.i(TAG, "Updated 5G NR SSRSRP Level: " + ssRsrpLevel);
            }
        }
        if (isLevelForParameter(USE_SSRSRQ)) {
            ssRsrqLevel = updateLevelWithMeasure(mSsRsrq, mSsRsrqThresholds);
            if (VDBG) {
                Rlog.i(TAG, "Updated 5G NR SSRSRQ Level: " + ssRsrqLevel);
            }
        }
        if (isLevelForParameter(USE_SSSINR)) {
            ssSinrLevel = updateLevelWithMeasure(mSsSinr, mSsSinrThresholds);
            if (VDBG) {
                Rlog.i(TAG, "Updated 5G NR SSSINR Level: " + ssSinrLevel);
            }
        }
        // Apply the smaller value among three levels of three measures.
        mLevel = Math.min(Math.min(ssRsrpLevel, ssRsrqLevel), ssSinrLevel);
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
            level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        } else if (measure >= thresholds[3]) {
            level = SIGNAL_STRENGTH_GREAT;
        } else if (measure >= thresholds[2]) {
            level = SIGNAL_STRENGTH_GOOD;
        } else if (measure >= thresholds[1]) {
            level = SIGNAL_STRENGTH_MODERATE;
        }  else if (measure >= thresholds[0]) {
            level = SIGNAL_STRENGTH_POOR;
        } else {
            level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
        return level;
    }

    /**
     * Get the RSRP in ASU.
     *
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSRP in ASU 0..97, 255, or UNAVAILABLE
     */
    @Override
    public int getAsuLevel() {
        int asuLevel;
        int nrDbm = getDbm();
        if (nrDbm == CellInfo.UNAVAILABLE) {
            asuLevel = UNKNOWN_ASU_LEVEL;
        } else if (nrDbm <= -140) {
            asuLevel = 0;
        } else if (nrDbm >= -43) {
            asuLevel = 97;
        } else {
            asuLevel = nrDbm + 140;
        }
        return asuLevel;
    }

    /**
     * Get the SS-RSRP as dBm value -140..-44dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    @Override
    public int getDbm() {
        return mSsRsrp;
    }

    /** @hide */
    public CellSignalStrengthNr(CellSignalStrengthNr s) {
        mCsiRsrp = s.mCsiRsrp;
        mCsiRsrq = s.mCsiRsrq;
        mCsiSinr = s.mCsiSinr;
        mCsiCqiTableIndex = s.mCsiCqiTableIndex;
        mCsiCqiReport = s.mCsiCqiReport;
        mSsRsrp = s.mSsRsrp;
        mSsRsrq = s.mSsRsrq;
        mSsSinr = s.mSsSinr;
        mLevel = s.mLevel;
        mParametersUseForLevel = s.mParametersUseForLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthNr copy() {
        return new CellSignalStrengthNr(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCsiRsrp, mCsiRsrq, mCsiSinr, mCsiCqiTableIndex,
                mCsiCqiReport, mSsRsrp, mSsRsrq, mSsSinr, mLevel);
    }

    private static final CellSignalStrengthNr sInvalid = new CellSignalStrengthNr();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CellSignalStrengthNr) {
            CellSignalStrengthNr o = (CellSignalStrengthNr) obj;
            return mCsiRsrp == o.mCsiRsrp && mCsiRsrq == o.mCsiRsrq && mCsiSinr == o.mCsiSinr
                    && mCsiCqiTableIndex == o.mCsiCqiTableIndex
                    && mCsiCqiReport.equals(o.mCsiCqiReport)
                    && mSsRsrp == o.mSsRsrp && mSsRsrq == o.mSsRsrq && mSsSinr == o.mSsSinr
                    && mLevel == o.mLevel;
        }
        return false;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append(TAG + ":{")
                .append(" csiRsrp = " + mCsiRsrp)
                .append(" csiRsrq = " + mCsiRsrq)
                .append(" csiCqiTableIndex = " + mCsiCqiTableIndex)
                .append(" csiCqiReport = " + mCsiCqiReport)
                .append(" ssRsrp = " + mSsRsrp)
                .append(" ssRsrq = " + mSsRsrq)
                .append(" ssSinr = " + mSsSinr)
                .append(" level = " + mLevel)
                .append(" parametersUseForLevel = " + mParametersUseForLevel)
                .append(" }")
                .toString();
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Parcelable.Creator<CellSignalStrengthNr> CREATOR =
            new Parcelable.Creator<CellSignalStrengthNr>() {
        @Override
        public CellSignalStrengthNr createFromParcel(Parcel in) {
            return new CellSignalStrengthNr(in);
        }

        @Override
        public CellSignalStrengthNr[] newArray(int size) {
            return new CellSignalStrengthNr[size];
        }
    };
}
