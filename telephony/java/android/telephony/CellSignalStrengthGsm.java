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
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.telephony.Rlog;

import java.util.Objects;

/**
 * GSM signal strength related information.
 */
public final class CellSignalStrengthGsm extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthGsm";
    private static final boolean DBG = false;

    private static final int GSM_RSSI_MAX = -51;
    private static final int GSM_RSSI_GREAT = -89;
    private static final int GSM_RSSI_GOOD = -97;
    private static final int GSM_RSSI_MODERATE = -103;
    private static final int GSM_RSSI_POOR = -107;
    private static final int GSM_RSSI_MIN = -113;

    private static final int[] sRssiThresholds = new int[] {
            GSM_RSSI_POOR, GSM_RSSI_MODERATE, GSM_RSSI_GOOD, GSM_RSSI_GREAT};

    private int mRssi; // in dBm [-113, -51] or UNAVAILABLE
    @UnsupportedAppUsage
    private int mBitErrorRate; // bit error rate (0-7, 99) TS 27.007 8.5 or UNAVAILABLE
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mTimingAdvance; // range from 0-219 or CellInfo.UNAVAILABLE if unknown
    private int mLevel;

    /** @hide */
    @UnsupportedAppUsage
    public CellSignalStrengthGsm() {
        setDefaultValues();
    }

    /** @hide */
    public CellSignalStrengthGsm(int rssi, int ber, int ta) {
        mRssi = inRangeOrUnavailable(rssi, GSM_RSSI_MIN, GSM_RSSI_MAX);
        mBitErrorRate = inRangeOrUnavailable(ber, 0, 7, 99);
        mTimingAdvance = inRangeOrUnavailable(ta, 0, 219);
        updateLevel(null, null);
    }

    /** @hide */
    public CellSignalStrengthGsm(android.hardware.radio.V1_0.GsmSignalStrength gsm) {
        // Convert from HAL values as part of construction.
        this(getRssiDbmFromAsu(gsm.signalStrength), gsm.bitErrorRate, gsm.timingAdvance);

        if (mRssi == CellInfo.UNAVAILABLE) {
            setDefaultValues();
        }
    }

    /** @hide */
    public CellSignalStrengthGsm(CellSignalStrengthGsm s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthGsm s) {
        mRssi = s.mRssi;
        mBitErrorRate = s.mBitErrorRate;
        mTimingAdvance = s.mTimingAdvance;
        mLevel = s.mLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthGsm copy() {
        return new CellSignalStrengthGsm(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mRssi = CellInfo.UNAVAILABLE;
        mBitErrorRate = CellInfo.UNAVAILABLE;
        mTimingAdvance = CellInfo.UNAVAILABLE;
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
        int[] rssiThresholds;
        if (cc == null) {
            rssiThresholds = sRssiThresholds;
        } else {
            rssiThresholds = cc.getIntArray(CarrierConfigManager.KEY_GSM_RSSI_THRESHOLDS_INT_ARRAY);
            if (rssiThresholds == null || rssiThresholds.length != NUM_SIGNAL_STRENGTH_THRESHOLDS) {
                rssiThresholds = sRssiThresholds;
            }
        }
        int level = NUM_SIGNAL_STRENGTH_THRESHOLDS;
        if (mRssi < GSM_RSSI_MIN || mRssi > GSM_RSSI_MAX) {
            mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
            return;
        }
        while (level > 0 && mRssi < rssiThresholds[level - 1]) level--;
        mLevel = level;
    }

    /**
     * Get the GSM timing advance between 0..219 symbols (normally 0..63).
     * <p>{@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE} is reported when there is no RR
     * connection. Refer to 3GPP 45.010 Sec 5.8.
     *
     * @return the current GSM timing advance, if available.
     */
    public int getTimingAdvance() {
        return mTimingAdvance;
    }

    /**
     * Get the signal strength as dBm
     */
    @Override
    public int getDbm() {
        return mRssi;
    }

    /**
     * Get the RSSI in ASU.
     *
     * Asu is calculated based on 3GPP RSSI. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSSI in ASU 0..31, 99, or UNAVAILABLE
     */
    @Override
    public int getAsuLevel() {
        return getAsuFromRssiDbm(mRssi);
    }

    /**
     * Return the Received Signal Strength Indicator
     *
     * @return the RSSI in dBm (-113, -51) or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}.
     * @hide
     */
    public int getRssi() {
        return mRssi;
    }

    /**
     * Return the Bit Error Rate
     *
     * @return the bit error rate (0-7, 99) as defined in TS 27.007 8.5 or
     *         {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    public int getBitErrorRate() {
        return mBitErrorRate;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRssi, mBitErrorRate, mTimingAdvance);
    }

    private static final CellSignalStrengthGsm sInvalid = new CellSignalStrengthGsm();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CellSignalStrengthGsm)) return false;
        CellSignalStrengthGsm s = (CellSignalStrengthGsm) o;

        return mRssi == s.mRssi
                && mBitErrorRate == s.mBitErrorRate
                && mTimingAdvance == s.mTimingAdvance
                && mLevel == s.mLevel;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthGsm:"
                + " rssi=" + mRssi
                + " ber=" + mBitErrorRate
                + " mTa=" + mTimingAdvance
                + " mLevel=" + mLevel;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mRssi);
        dest.writeInt(mBitErrorRate);
        dest.writeInt(mTimingAdvance);
        dest.writeInt(mLevel);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the token is already been processed.
     */
    private CellSignalStrengthGsm(Parcel in) {
        mRssi = in.readInt();
        mBitErrorRate = in.readInt();
        mTimingAdvance = in.readInt();
        mLevel = in.readInt();
        if (DBG) log("CellSignalStrengthGsm(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final @android.annotation.NonNull Parcelable.Creator<CellSignalStrengthGsm> CREATOR =
            new Parcelable.Creator<CellSignalStrengthGsm>() {
        @Override
        public CellSignalStrengthGsm createFromParcel(Parcel in) {
            return new CellSignalStrengthGsm(in);
        }

        @Override
        public CellSignalStrengthGsm[] newArray(int size) {
            return new CellSignalStrengthGsm[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
