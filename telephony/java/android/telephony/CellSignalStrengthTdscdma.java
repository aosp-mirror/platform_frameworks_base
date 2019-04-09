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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.Objects;

/**
 * Tdscdma signal strength related information.
 *
 * This class provides signal strength and signal quality information for the TD-SCDMA air
 * interface. For more information see 3gpp 25.225.
 */
public final class CellSignalStrengthTdscdma extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthTdscdma";
    private static final boolean DBG = false;

    // These levels are arbitrary but carried over from SignalStrength.java for consistency.
    private static final int TDSCDMA_RSCP_MAX = -24;
    private static final int TDSCDMA_RSCP_GREAT = -49;
    private static final int TDSCDMA_RSCP_GOOD = -73;
    private static final int TDSCDMA_RSCP_MODERATE = -97;
    private static final int TDSCDMA_RSCP_POOR = -110;
    private static final int TDSCDMA_RSCP_MIN = -120;


    private int mRssi; // in dBm [-113, -51], CellInfo.UNAVAILABLE

    private int mBitErrorRate; // bit error rate (0-7, 99) as defined in TS 27.007 8.5 or
                               // CellInfo.UNAVAILABLE if unknown
    private int mRscp; // Pilot Power in dBm [-120, -24] or CellInfo.UNAVAILABLE
                       // CellInfo.UNAVAILABLE if unknown

    private int mLevel;

    /** @hide */
    public CellSignalStrengthTdscdma() {
        setDefaultValues();
    }

    /**
     * @param rssi in dBm [-113, -51] or UNAVAILABLE
     * @param ber [0-7], 99 or UNAVAILABLE
     * @param rscp in dBm [-120, -24] or UNAVAILABLE
     *
     * @hide
     */
    public CellSignalStrengthTdscdma(int rssi, int ber, int rscp) {
        mRssi = inRangeOrUnavailable(rssi, -113, -51);
        mBitErrorRate = inRangeOrUnavailable(ber, 0, 7, 99);
        mRscp = inRangeOrUnavailable(rscp, -120, -24);
        updateLevel(null, null);
    }

    /** @hide */
    public CellSignalStrengthTdscdma(android.hardware.radio.V1_0.TdScdmaSignalStrength tdscdma) {
        // Convert from HAL values as part of construction.
        this(CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
                tdscdma.rscp != CellInfo.UNAVAILABLE ? -tdscdma.rscp : tdscdma.rscp);

        if (mRssi == CellInfo.UNAVAILABLE && mRscp == CellInfo.UNAVAILABLE) {
            setDefaultValues();
        }
    }

    /** @hide */
    public CellSignalStrengthTdscdma(android.hardware.radio.V1_2.TdscdmaSignalStrength tdscdma) {
        // Convert from HAL values as part of construction.
        this(getRssiDbmFromAsu(tdscdma.signalStrength),
                tdscdma.bitErrorRate, getRscpDbmFromAsu(tdscdma.rscp));

        if (mRssi == CellInfo.UNAVAILABLE && mRscp == CellInfo.UNAVAILABLE) {
            setDefaultValues();
        }
    }

    /** @hide */
    public CellSignalStrengthTdscdma(CellSignalStrengthTdscdma s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthTdscdma s) {
        mRssi = s.mRssi;
        mBitErrorRate = s.mBitErrorRate;
        mRscp = s.mRscp;
        mLevel = s.mLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthTdscdma copy() {
        return new CellSignalStrengthTdscdma(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mRssi = CellInfo.UNAVAILABLE;
        mBitErrorRate = CellInfo.UNAVAILABLE;
        mRscp = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }


    /** {@inheritDoc} */
    @Override
    @IntRange(from = 0, to = 4)
    public int getLevel() {
        return mLevel;
    }

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        if (mRscp > TDSCDMA_RSCP_MAX) mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (mRscp >= TDSCDMA_RSCP_GREAT) mLevel = SIGNAL_STRENGTH_GREAT;
        else if (mRscp >= TDSCDMA_RSCP_GOOD)  mLevel = SIGNAL_STRENGTH_GOOD;
        else if (mRscp >= TDSCDMA_RSCP_MODERATE)  mLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mRscp >= TDSCDMA_RSCP_POOR) mLevel = SIGNAL_STRENGTH_POOR;
        else mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /**
     * Get the RSCP as dBm value -120..-24dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    @Override
    public int getDbm() {
        return mRscp;
    }

    /**
     * Get the RSCP as dBm value -120..-24dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    public int getRscp() {
        return mRscp;
    }

    /**
     * Get the RSSI as dBm value -113..-51dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     *
     * @hide
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * Get the BER as an ASU value 0..7, 99, or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     * @hide
     */
    public int getBitErrorRate() {
        return mBitErrorRate;
    }

    /**
     * Get the RSCP in ASU.
     *
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSCP in ASU 0..96, 255, or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    @Override
    public int getAsuLevel() {
        if (mRscp != CellInfo.UNAVAILABLE) return getAsuFromRscpDbm(mRscp);
        // For historical reasons, if RSCP is unavailable, this API will very incorrectly return
        // RSSI. This hackery will be removed when most devices are using Radio HAL 1.2+
        if (mRssi != CellInfo.UNAVAILABLE) return getAsuFromRssiDbm(mRssi);
        return getAsuFromRscpDbm(CellInfo.UNAVAILABLE);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRssi, mBitErrorRate, mRscp, mLevel);
    }

    private static final CellSignalStrengthTdscdma sInvalid = new CellSignalStrengthTdscdma();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CellSignalStrengthTdscdma)) return false;
        CellSignalStrengthTdscdma s = (CellSignalStrengthTdscdma) o;

        return mRssi == s.mRssi
                && mBitErrorRate == s.mBitErrorRate
                && mRscp == s.mRscp
                && mLevel == s.mLevel;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthTdscdma:"
                + " rssi=" + mRssi
                + " ber=" + mBitErrorRate
                + " rscp=" + mRscp
                + " level=" + mLevel;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mRssi);
        dest.writeInt(mBitErrorRate);
        dest.writeInt(mRscp);
        dest.writeInt(mLevel);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the token is already been processed.
     */
    private CellSignalStrengthTdscdma(Parcel in) {
        mRssi = in.readInt();
        mBitErrorRate = in.readInt();
        mRscp = in.readInt();
        mLevel = in.readInt();
        if (DBG) log("CellSignalStrengthTdscdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    @NonNull
    public static final Parcelable.Creator<CellSignalStrengthTdscdma> CREATOR =
            new Parcelable.Creator<CellSignalStrengthTdscdma>() {
        @Override
        public @NonNull CellSignalStrengthTdscdma createFromParcel(Parcel in) {
            return new CellSignalStrengthTdscdma(in);
        }

        @Override
        public @NonNull CellSignalStrengthTdscdma[] newArray(int size) {
            return new CellSignalStrengthTdscdma[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
