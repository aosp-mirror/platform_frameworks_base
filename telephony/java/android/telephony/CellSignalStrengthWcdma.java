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
import android.annotation.StringDef;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.text.TextUtils;

import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Wcdma signal strength related information.
 */
public final class CellSignalStrengthWcdma extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthWcdma";
    private static final boolean DBG = false;

    private static final int WCDMA_RSSI_MAX = -51;
    private static final int WCDMA_RSSI_GREAT = -77;
    private static final int WCDMA_RSSI_GOOD = -87;
    private static final int WCDMA_RSSI_MODERATE = -97;
    private static final int WCDMA_RSSI_POOR = -107;
    private static final int WCDMA_RSSI_MIN = -113;

    private static final int[] sRssiThresholds = new int[]{
            WCDMA_RSSI_POOR, WCDMA_RSSI_MODERATE, WCDMA_RSSI_GOOD, WCDMA_RSSI_GREAT};

    private static final int WCDMA_RSCP_MAX = -24;
    private static final int WCDMA_RSCP_GREAT = -85;
    private static final int WCDMA_RSCP_GOOD = -95;
    private static final int WCDMA_RSCP_MODERATE = -105;
    private static final int WCDMA_RSCP_POOR = -115;
    private static final int WCDMA_RSCP_MIN = -120;

    private static final int[] sRscpThresholds = new int[] {
            WCDMA_RSCP_POOR, WCDMA_RSCP_MODERATE, WCDMA_RSCP_GOOD, WCDMA_RSCP_GREAT};

    // TODO: Because these are used as values in CarrierConfig, they should be exposed somehow.
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({LEVEL_CALCULATION_METHOD_RSSI, LEVEL_CALCULATION_METHOD_RSCP})
    public @interface LevelCalculationMethod {}
    /** @hide */
    public static final String LEVEL_CALCULATION_METHOD_RSSI = "rssi";
    /** @hide */
    public static final String LEVEL_CALCULATION_METHOD_RSCP = "rscp";

    // Default to RSSI for backwards compatibility with older devices
    private static final String DEFAULT_LEVEL_CALCULATION_METHOD = LEVEL_CALCULATION_METHOD_RSSI;

    private int mRssi; // in dBm [-113, 51] or CellInfo.UNAVAILABLE if unknown

    @UnsupportedAppUsage
    private int mBitErrorRate; // bit error rate (0-7, 99) as defined in TS 27.007 8.5 or
                               // CellInfo.UNAVAILABLE if unknown
    private int mRscp; // in dBm [-120, -24]
    private int mEcNo; // range -24, 1, CellInfo.UNAVAILABLE if unknown
    private int mLevel;

    /** @hide */
    public CellSignalStrengthWcdma() {
        setDefaultValues();
    }

    /** @hide */
    public CellSignalStrengthWcdma(int rssi, int ber, int rscp, int ecno) {
        mRssi = inRangeOrUnavailable(rssi, WCDMA_RSSI_MIN, WCDMA_RSSI_MAX);
        mBitErrorRate = inRangeOrUnavailable(ber, 0, 7, 99);
        mRscp = inRangeOrUnavailable(rscp, -120, -24);
        mEcNo = inRangeOrUnavailable(ecno, -24, 1);
        updateLevel(null, null);
    }

    /** @hide */
    public CellSignalStrengthWcdma(android.hardware.radio.V1_0.WcdmaSignalStrength wcdma) {
        // Convert from HAL values as part of construction.
        this(getRssiDbmFromAsu(wcdma.signalStrength), wcdma.bitErrorRate,
                CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE);

        if (mRssi == CellInfo.UNAVAILABLE && mRscp == CellInfo.UNAVAILABLE) {
            setDefaultValues();
        }
    }

    /** @hide */
    public CellSignalStrengthWcdma(android.hardware.radio.V1_2.WcdmaSignalStrength wcdma) {
        // Convert from HAL values as part of construction.
        this(getRssiDbmFromAsu(wcdma.base.signalStrength),
                    wcdma.base.bitErrorRate,
                    getRscpDbmFromAsu(wcdma.rscp),
                    getEcNoDbFromAsu(wcdma.ecno));

        if (mRssi == CellInfo.UNAVAILABLE && mRscp == CellInfo.UNAVAILABLE) {
            setDefaultValues();
        }
    }

    /** @hide */
    public CellSignalStrengthWcdma(CellSignalStrengthWcdma s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthWcdma s) {
        mRssi = s.mRssi;
        mBitErrorRate = s.mBitErrorRate;
        mRscp = s.mRscp;
        mEcNo = s.mEcNo;
        mLevel = s.mLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthWcdma copy() {
        return new CellSignalStrengthWcdma(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mRssi = CellInfo.UNAVAILABLE;
        mBitErrorRate = CellInfo.UNAVAILABLE;
        mRscp = CellInfo.UNAVAILABLE;
        mEcNo = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /** {@inheritDoc} */
    @Override
    @IntRange(from = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, to = SIGNAL_STRENGTH_GREAT)
    public int getLevel() {
        return mLevel;
    }

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        String calcMethod;
        int[] rscpThresholds;

        if (cc == null) {
            calcMethod = DEFAULT_LEVEL_CALCULATION_METHOD;
            rscpThresholds = sRscpThresholds;
        } else {
            // TODO: abstract this entire thing into a series of functions
            calcMethod = cc.getString(
                    CarrierConfigManager.KEY_WCDMA_DEFAULT_SIGNAL_STRENGTH_MEASUREMENT_STRING,
                    DEFAULT_LEVEL_CALCULATION_METHOD);
            if (TextUtils.isEmpty(calcMethod)) calcMethod = DEFAULT_LEVEL_CALCULATION_METHOD;
            rscpThresholds = cc.getIntArray(
                    CarrierConfigManager.KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY);
            if (rscpThresholds == null || rscpThresholds.length != NUM_SIGNAL_STRENGTH_THRESHOLDS) {
                rscpThresholds = sRscpThresholds;
            }
        }

        int level = NUM_SIGNAL_STRENGTH_THRESHOLDS;
        switch (calcMethod) {
            case LEVEL_CALCULATION_METHOD_RSCP:
                if (mRscp < WCDMA_RSCP_MIN || mRscp > WCDMA_RSCP_MAX) {
                    mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                    return;
                }
                while (level > 0 && mRscp < rscpThresholds[level - 1]) level--;
                mLevel = level;
                return;
            default:
                loge("Invalid Level Calculation Method for CellSignalStrengthWcdma = "
                        + calcMethod);
                /** fall through */
            case LEVEL_CALCULATION_METHOD_RSSI:
                if (mRssi < WCDMA_RSSI_MIN || mRssi > WCDMA_RSSI_MAX) {
                    mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                    return;
                }
                while (level > 0 && mRssi < sRssiThresholds[level - 1]) level--;
                mLevel = level;
                return;
        }
    }

    /**
     * Get the RSCP as dBm value -120..-24dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    @Override
    public int getDbm() {
        if (mRscp != CellInfo.UNAVAILABLE) return mRscp;
        return mRssi;
    }

    /**
     * Get the RSCP in ASU.
     *
     * Asu is calculated based on 3GPP RSCP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSCP in ASU 0..96, 255, or UNAVAILABLE
     */
    @Override
    public int getAsuLevel() {
        if (mRscp != CellInfo.UNAVAILABLE) return getAsuFromRscpDbm(mRscp);
        // For historical reasons, if RSCP is unavailable, this API will very incorrectly return
        // RSSI. This hackery will be removed when most devices are using Radio HAL 1.2+
        if (mRssi != CellInfo.UNAVAILABLE) return getAsuFromRssiDbm(mRssi);
        return getAsuFromRscpDbm(CellInfo.UNAVAILABLE);
    }

    /**
     * Get the RSSI as dBm
     *
     * @hide
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * Get the RSCP as dBm
     *
     * @hide
     */
    public int getRscp() {
        return mRscp;
    }

    /**
     * Get the Ec/No (Energy per chip over the noise spectral density) as dB.
     *
     * Reference: TS 25.133 Section 9.1.2.3
     *
     * @return the Ec/No of the measured cell in the range [-24, 1] or
     * {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} if unavailable
     */
    public int getEcNo() {
        return mEcNo;
    }

    /**
     * Return the Bit Error Rate
     *
     * @returns the bit error rate (0-7, 99) as defined in TS 27.007 8.5 or UNAVAILABLE.
     * @hide
     */
    public int getBitErrorRate() {
        return mBitErrorRate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRssi, mBitErrorRate, mRscp, mEcNo, mLevel);
    }

    private static final CellSignalStrengthWcdma sInvalid = new CellSignalStrengthWcdma();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CellSignalStrengthWcdma)) return false;
        CellSignalStrengthWcdma s = (CellSignalStrengthWcdma) o;

        return mRssi == s.mRssi
                && mBitErrorRate == s.mBitErrorRate
                && mRscp == s.mRscp
                && mEcNo == s.mEcNo
                && mLevel == s.mLevel;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthWcdma:"
                + " ss=" + mRssi
                + " ber=" + mBitErrorRate
                + " rscp=" + mRscp
                + " ecno=" + mEcNo
                + " level=" + mLevel;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mRssi);
        dest.writeInt(mBitErrorRate);
        dest.writeInt(mRscp);
        dest.writeInt(mEcNo);
        dest.writeInt(mLevel);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the token is already been processed.
     */
    private CellSignalStrengthWcdma(Parcel in) {
        mRssi = in.readInt();
        mBitErrorRate = in.readInt();
        mRscp = in.readInt();
        mEcNo = in.readInt();
        mLevel = in.readInt();
        if (DBG) log("CellSignalStrengthWcdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Parcelable.Creator<CellSignalStrengthWcdma> CREATOR =
            new Parcelable.Creator<CellSignalStrengthWcdma>() {
        @Override
        public CellSignalStrengthWcdma createFromParcel(Parcel in) {
            return new CellSignalStrengthWcdma(in);
        }

        @Override
        public CellSignalStrengthWcdma[] newArray(int size) {
            return new CellSignalStrengthWcdma[size];
        }
    };

    /**
     * log warning
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }

    /**
     * log error
     */
    private static void loge(String s) {
        Rlog.e(LOG_TAG, s);
    }
}
