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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

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

    private static final String TAG = "CellSignalStrengthNr";

    /**
     * These threshold values are copied from LTE.
     * TODO: make it configurable via CarrierConfig.
     */
    private static final int SIGNAL_GREAT_THRESHOLD = -95;
    private static final int SIGNAL_GOOD_THRESHOLD = -105;
    private static final int SIGNAL_MODERATE_THRESHOLD = -115;

    private int mCsiRsrp;
    private int mCsiRsrq;
    private int mCsiSinr;
    private int mSsRsrp;
    private int mSsRsrq;
    private int mSsSinr;

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
        mCsiRsrp = csiRsrp;
        mCsiRsrq = csiRsrq;
        mCsiSinr = csiSinr;
        mSsRsrp = ssRsrp;
        mSsRsrq = ssRsrq;
        mSsSinr = ssSinr;
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
     * Reference: 3GPP TS 38.215.
     * Range: -20 dB to -3 dB.
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
        dest.writeInt(mSsRsrp);
        dest.writeInt(mSsRsrq);
        dest.writeInt(mSsSinr);
    }

    private CellSignalStrengthNr(Parcel in) {
        mCsiRsrp = in.readInt();
        mCsiRsrq = in.readInt();
        mCsiSinr = in.readInt();
        mSsRsrp = in.readInt();
        mSsRsrq = in.readInt();
        mSsSinr = in.readInt();
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mCsiRsrp = CellInfo.UNAVAILABLE;
        mCsiRsrq = CellInfo.UNAVAILABLE;
        mCsiSinr = CellInfo.UNAVAILABLE;
        mSsRsrp = CellInfo.UNAVAILABLE;
        mSsRsrq = CellInfo.UNAVAILABLE;
        mSsSinr = CellInfo.UNAVAILABLE;
    }

    @Override
    public int getLevel() {
        if (mCsiRsrp == CellInfo.UNAVAILABLE) {
            return SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        } else if (mCsiRsrp >= SIGNAL_GREAT_THRESHOLD) {
            return SIGNAL_STRENGTH_GREAT;
        } else if (mCsiRsrp >= SIGNAL_GOOD_THRESHOLD) {
            return SIGNAL_STRENGTH_GOOD;
        } else if (mCsiRsrp >= SIGNAL_MODERATE_THRESHOLD) {
            return SIGNAL_STRENGTH_MODERATE;
        } else {
            return SIGNAL_STRENGTH_POOR;
        }
    }

    /**
     * Calculates the NR signal as an asu value between 0..97, 99 is unknown.
     * Asu is calculated based on 3GPP RSRP, refer to 3GPP TS 27.007 section 8.69.
     * @return an integer represent the asu level of the signal strength.
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

    @Override
    public int getDbm() {
        return mCsiRsrp;
    }

    /** @hide */
    @Override
    public CellSignalStrength copy() {
        return new CellSignalStrengthNr(
                mCsiRsrp, mCsiRsrq, mCsiSinr, mSsRsrp, mSsRsrq, mSsSinr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCsiRsrp, mCsiRsrq, mCsiSinr, mSsRsrp, mSsRsrq, mSsSinr);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CellSignalStrengthNr) {
            CellSignalStrengthNr o = (CellSignalStrengthNr) obj;
            return mCsiRsrp == o.mCsiRsrp && mCsiRsrq == o.mCsiRsrq && mCsiSinr == o.mCsiSinr
                    && mSsRsrp == o.mSsRsrp && mSsRsrq == o.mSsRsrq && mSsSinr == o.mSsSinr;
        }
        return false;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append(TAG + ":{")
                .append(" csiRsrp = " + mCsiRsrp)
                .append(" csiRsrq = " + mCsiRsrq)
                .append(" csiSinr = " + mCsiSinr)
                .append(" ssRsrp = " + mSsRsrp)
                .append(" ssRsrq = " + mSsRsrq)
                .append(" ssSinr = " + mSsSinr)
                .append(" }")
                .toString();
    }

    /** Implement the Parcelable interface */
    public static final Parcelable.Creator<CellSignalStrengthNr> CREATOR =
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
