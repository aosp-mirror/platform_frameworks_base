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
 * Signal strength related information.
 */
public final class CellSignalStrengthCdma extends CellSignalStrength implements Parcelable {

    private static final String LOG_TAG = "CellSignalStrengthCdma";
    private static final boolean DBG = false;

    private int mCdmaDbm;   // This value is the RSSI value
    private int mCdmaEcio;  // This value is the Ec/Io
    private int mEvdoDbm;   // This value is the EVDO RSSI value
    private int mEvdoEcio;  // This value is the EVDO Ec/Io
    private int mEvdoSnr;   // Valid values are 0-8.  8 is the highest signal to noise ratio

    /**
     * Empty constructor
     *
     * @hide
     */
    public CellSignalStrengthCdma() {
        setDefaultValues();
    }

    /**
     * Constructor
     *
     * @hide
     */
    public CellSignalStrengthCdma(int cdmaDbm, int cdmaEcio, int evdoDbm, int evdoEcio,
            int evdoSnr) {
        initialize(cdmaDbm, cdmaEcio, evdoDbm, evdoEcio, evdoSnr);
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public CellSignalStrengthCdma(CellSignalStrengthCdma s) {
        copyFrom(s);
    }

    /**
     * Initialize all the values
     *
     * @param cdmaDbm
     * @param cdmaEcio
     * @param evdoDbm
     * @param evdoEcio
     * @param evdoSnr
     *
     * @hide
     */
    public void initialize(int cdmaDbm, int cdmaEcio, int evdoDbm, int evdoEcio, int evdoSnr) {
        mCdmaDbm = cdmaDbm;
        mCdmaEcio = cdmaEcio;
        mEvdoDbm = evdoDbm;
        mEvdoEcio = evdoEcio;
        mEvdoSnr = evdoSnr;
    }

    /**
     * @hide
     */
    protected void copyFrom(CellSignalStrengthCdma s) {
        mCdmaDbm = s.mCdmaDbm;
        mCdmaEcio = s.mCdmaEcio;
        mEvdoDbm = s.mEvdoDbm;
        mEvdoEcio = s.mEvdoEcio;
        mEvdoSnr = s.mEvdoSnr;
    }

    /**
     * @hide
     */
    @Override
    public CellSignalStrengthCdma copy() {
        return new CellSignalStrengthCdma(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mCdmaDbm = Integer.MAX_VALUE;
        mCdmaEcio = Integer.MAX_VALUE;
        mEvdoDbm = Integer.MAX_VALUE;
        mEvdoEcio = Integer.MAX_VALUE;
        mEvdoSnr = Integer.MAX_VALUE;
    }

    /**
     * Get signal level as an int from 0..4
     */
    @Override
    public int getLevel() {
        int level;

        int cdmaLevel = getCdmaLevel();
        int evdoLevel = getEvdoLevel();
        if (evdoLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know evdo, use cdma */
            level = getCdmaLevel();
        } else if (cdmaLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know cdma, use evdo */
            level = getEvdoLevel();
        } else {
            /* We know both, use the lowest level */
            level = cdmaLevel < evdoLevel ? cdmaLevel : evdoLevel;
        }
        if (DBG) log("getLevel=" + level);
        return level;
    }

    /**
     * Get the signal level as an asu value between 0..97, 99 is unknown
     */
    @Override
    public int getAsuLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int cdmaAsuLevel;
        int ecioAsuLevel;

        if (cdmaDbm == Integer.MAX_VALUE) cdmaAsuLevel = 99;
        else if (cdmaDbm >= -75) cdmaAsuLevel = 16;
        else if (cdmaDbm >= -82) cdmaAsuLevel = 8;
        else if (cdmaDbm >= -90) cdmaAsuLevel = 4;
        else if (cdmaDbm >= -95) cdmaAsuLevel = 2;
        else if (cdmaDbm >= -100) cdmaAsuLevel = 1;
        else cdmaAsuLevel = 99;

        // Ec/Io are in dB*10
        if (cdmaEcio == Integer.MAX_VALUE) ecioAsuLevel = 99;
        else if (cdmaEcio >= -90) ecioAsuLevel = 16;
        else if (cdmaEcio >= -100) ecioAsuLevel = 8;
        else if (cdmaEcio >= -115) ecioAsuLevel = 4;
        else if (cdmaEcio >= -130) ecioAsuLevel = 2;
        else if (cdmaEcio >= -150) ecioAsuLevel = 1;
        else ecioAsuLevel = 99;

        int level = (cdmaAsuLevel < ecioAsuLevel) ? cdmaAsuLevel : ecioAsuLevel;
        if (DBG) log("getAsuLevel=" + level);
        return level;
    }

    /**
     * Get cdma as level 0..4
     */
    public int getCdmaLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int levelDbm;
        int levelEcio;

        if (cdmaDbm == Integer.MAX_VALUE) levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (cdmaDbm >= -75) levelDbm = SIGNAL_STRENGTH_GREAT;
        else if (cdmaDbm >= -85) levelDbm = SIGNAL_STRENGTH_GOOD;
        else if (cdmaDbm >= -95) levelDbm = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaDbm >= -100) levelDbm = SIGNAL_STRENGTH_POOR;
        else levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        // Ec/Io are in dB*10
        if (cdmaEcio == Integer.MAX_VALUE) levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (cdmaEcio >= -90) levelEcio = SIGNAL_STRENGTH_GREAT;
        else if (cdmaEcio >= -110) levelEcio = SIGNAL_STRENGTH_GOOD;
        else if (cdmaEcio >= -130) levelEcio = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaEcio >= -150) levelEcio = SIGNAL_STRENGTH_POOR;
        else levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelDbm < levelEcio) ? levelDbm : levelEcio;
        if (DBG) log("getCdmaLevel=" + level);
        return level;
    }

    /**
     * Get Evdo as level 0..4
     */
    public int getEvdoLevel() {
        int evdoDbm = getEvdoDbm();
        int evdoSnr = getEvdoSnr();
        int levelEvdoDbm;
        int levelEvdoSnr;

        if (evdoDbm == Integer.MAX_VALUE) levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (evdoDbm >= -65) levelEvdoDbm = SIGNAL_STRENGTH_GREAT;
        else if (evdoDbm >= -75) levelEvdoDbm = SIGNAL_STRENGTH_GOOD;
        else if (evdoDbm >= -90) levelEvdoDbm = SIGNAL_STRENGTH_MODERATE;
        else if (evdoDbm >= -105) levelEvdoDbm = SIGNAL_STRENGTH_POOR;
        else levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (evdoSnr == Integer.MAX_VALUE) levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (evdoSnr >= 7) levelEvdoSnr = SIGNAL_STRENGTH_GREAT;
        else if (evdoSnr >= 5) levelEvdoSnr = SIGNAL_STRENGTH_GOOD;
        else if (evdoSnr >= 3) levelEvdoSnr = SIGNAL_STRENGTH_MODERATE;
        else if (evdoSnr >= 1) levelEvdoSnr = SIGNAL_STRENGTH_POOR;
        else levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
        if (DBG) log("getEvdoLevel=" + level);
        return level;
    }

    /**
     * Get the signal strength as dBm
     */
    @Override
    public int getDbm() {
        int cdmaDbm = getCdmaDbm();
        int evdoDbm = getEvdoDbm();

        // Use the lower value to be conservative
        return (cdmaDbm < evdoDbm) ? cdmaDbm : evdoDbm;
    }

    /**
     * Get the CDMA RSSI value in dBm
     */
    public int getCdmaDbm() {
        return mCdmaDbm;
    }
    /** @hide */
    public void setCdmaDbm(int cdmaDbm) {
        mCdmaDbm = cdmaDbm;
    }

    /**
     * Get the CDMA Ec/Io value in dB*10
     */
    public int getCdmaEcio() {
        return mCdmaEcio;
    }
    /** @hide */
    public void setCdmaEcio(int cdmaEcio) {
        mCdmaEcio = cdmaEcio;
    }

    /**
     * Get the EVDO RSSI value in dBm
     */
    public int getEvdoDbm() {
        return mEvdoDbm;
    }
    /** @hide */
    public void setEvdoDbm(int evdoDbm) {
        mEvdoDbm = evdoDbm;
    }

    /**
     * Get the EVDO Ec/Io value in dB*10
     */
    public int getEvdoEcio() {
        return mEvdoEcio;
    }
    /** @hide */
    public void setEvdoEcio(int evdoEcio) {
        mEvdoEcio = evdoEcio;
    }

    /**
     * Get the signal to noise ratio. Valid values are 0-8. 8 is the highest.
     */
    public int getEvdoSnr() {
        return mEvdoSnr;
    }
    /** @hide */
    public void setEvdoSnr(int evdoSnr) {
        mEvdoSnr = evdoSnr;
    }

    @Override
    public int hashCode() {
        int primeNum = 31;
        return ((mCdmaDbm * primeNum) + (mCdmaEcio * primeNum)
                + (mEvdoDbm * primeNum) + (mEvdoEcio * primeNum) + (mEvdoSnr * primeNum));
    }

    @Override
    public boolean equals (Object o) {
        CellSignalStrengthCdma s;

        try {
            s = (CellSignalStrengthCdma) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return mCdmaDbm == s.mCdmaDbm
                && mCdmaEcio == s.mCdmaEcio
                && mEvdoDbm == s.mEvdoDbm
                && mEvdoEcio == s.mEvdoEcio
                && mEvdoSnr == s.mEvdoSnr;
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return "CellSignalStrengthCdma:"
                + " cdmaDbm=" + mCdmaDbm
                + " cdmaEcio=" + mCdmaEcio
                + " evdoDbm=" + mEvdoDbm
                + " evdoEcio=" + mEvdoEcio
                + " evdoSnr=" + mEvdoSnr;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        // Need to multiply CdmaDbm, CdmaEcio, EvdoDbm and EvdoEcio by -1
        // to ensure consistency when reading values written here
        // unless the value is invalid
        dest.writeInt(mCdmaDbm * (mCdmaDbm != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mCdmaEcio * (mCdmaEcio != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mEvdoDbm * (mEvdoDbm != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mEvdoEcio * (mEvdoEcio != Integer.MAX_VALUE ? -1 : 1));
        dest.writeInt(mEvdoSnr);
    }

    /**
     * Construct a SignalStrength object from the given parcel
     * where the TYPE_CDMA token is already been processed.
     */
    private CellSignalStrengthCdma(Parcel in) {
        // CdmaDbm, CdmaEcio, EvdoDbm and EvdoEcio are written into
        // the parcel as positive values.
        // Need to convert into negative values unless the value is invalid
        mCdmaDbm = in.readInt();
        if (mCdmaDbm != Integer.MAX_VALUE) mCdmaDbm *= -1;
        mCdmaEcio = in.readInt();
        if (mCdmaEcio != Integer.MAX_VALUE) mCdmaEcio *= -1;
        mEvdoDbm = in.readInt();
        if (mEvdoDbm != Integer.MAX_VALUE) mEvdoDbm *= -1;
        mEvdoEcio = in.readInt();
        if (mEvdoEcio != Integer.MAX_VALUE) mEvdoEcio *= -1;
        mEvdoSnr = in.readInt();
        if (DBG) log("CellSignalStrengthCdma(Parcel): " + toString());
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @SuppressWarnings("hiding")
    public static final Parcelable.Creator<CellSignalStrengthCdma> CREATOR =
            new Parcelable.Creator<CellSignalStrengthCdma>() {
        @Override
        public CellSignalStrengthCdma createFromParcel(Parcel in) {
            return new CellSignalStrengthCdma(in);
        }

        @Override
        public CellSignalStrengthCdma[] newArray(int size) {
            return new CellSignalStrengthCdma[size];
        }
    };

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
