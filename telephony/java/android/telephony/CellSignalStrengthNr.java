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
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

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
    private int mLevel;

    /** @hide */
    public CellSignalStrengthNr() {
        setDefaultValues();
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
        mCsiRsrp = inRangeOrUnavailable(csiRsrp, -140, -44);
        mCsiRsrq = inRangeOrUnavailable(csiRsrq, -20, -3);
        mCsiSinr = inRangeOrUnavailable(csiSinr, -23, 23);
        mSsRsrp = inRangeOrUnavailable(ssRsrp, -140, -44);
        mSsRsrq = inRangeOrUnavailable(ssRsrq, -20, -3);
        mSsSinr = inRangeOrUnavailable(ssSinr, -23, 40);
        updateLevel(null, null);
    }

    /**
     * @hide
     * @param ss signal strength from modem.
     */
    public CellSignalStrengthNr(android.hardware.radio.V1_4.NrSignalStrength ss) {
        this(ss.csiRsrp, ss.csiRsrq, ss.csiSinr, ss.ssRsrp, ss.ssRsrq, ss.ssSinr);
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
        dest.writeInt(mLevel);
    }

    private CellSignalStrengthNr(Parcel in) {
        mCsiRsrp = in.readInt();
        mCsiRsrq = in.readInt();
        mCsiSinr = in.readInt();
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
        mSsRsrp = CellInfo.UNAVAILABLE;
        mSsRsrq = CellInfo.UNAVAILABLE;
        mSsSinr = CellInfo.UNAVAILABLE;
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
        if (mCsiRsrp == CellInfo.UNAVAILABLE) {
            mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        } else if (mCsiRsrp >= SIGNAL_GREAT_THRESHOLD) {
            mLevel = SIGNAL_STRENGTH_GREAT;
        } else if (mCsiRsrp >= SIGNAL_GOOD_THRESHOLD) {
            mLevel = SIGNAL_STRENGTH_GOOD;
        } else if (mCsiRsrp >= SIGNAL_MODERATE_THRESHOLD) {
            mLevel = SIGNAL_STRENGTH_MODERATE;
        } else {
            mLevel = SIGNAL_STRENGTH_POOR;
        }
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
     * Get the CSI-RSRP as dBm value -140..-44dBm or {@link CellInfo#UNAVAILABLE UNAVAILABLE}.
     */
    @Override
    public int getDbm() {
        return mCsiRsrp;
    }

    /** @hide */
    public CellSignalStrengthNr(CellSignalStrengthNr s) {
        mCsiRsrp = s.mCsiRsrp;
        mCsiRsrq = s.mCsiRsrq;
        mCsiSinr = s.mCsiSinr;
        mSsRsrp = s.mSsRsrp;
        mSsRsrq = s.mSsRsrq;
        mSsSinr = s.mSsSinr;
        mLevel = s.mLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthNr copy() {
        return new CellSignalStrengthNr(this);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCsiRsrp, mCsiRsrq, mCsiSinr, mSsRsrp, mSsRsrq, mSsSinr, mLevel);
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
                .append(" csiSinr = " + mCsiSinr)
                .append(" ssRsrp = " + mSsRsrp)
                .append(" ssRsrq = " + mSsRsrq)
                .append(" ssSinr = " + mSsSinr)
                .append(" level = " + mLevel)
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
