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
import android.os.PersistableBundle;
import android.telephony.Rlog;

import java.util.Objects;

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
    private int mLevel;

    /** @hide */
    public CellSignalStrengthCdma() {
        setDefaultValues();
    }

    /**
     * SignalStrength constructor for input from the HAL.
     *
     * Note that values received from the HAL require coersion to be compatible here. All values
     * reported through IRadio are the negative of the actual values (which results in a positive
     * input to this method.
     *
     * <p>Note that this HAL is inconsistent with UMTS-based radio techs as the value indicating
     * that a field is unreported is negative, rather than a large(r) positive number.
     * <p>Also note that to keep the public-facing methods of this class consistent with others,
     * unreported values are coerced to {@link android.telephony.CellInfo#UNAVAILABLE UNAVAILABLE}
     * rather than left as -1, which is a departure from SignalStrength, which is stuck with the
     * values it currently reports.
     *
     * @param cdmaDbm CDMA signal strength value or CellInfo.UNAVAILABLE if invalid.
     * @param cdmaEcio CDMA pilot/noise ratio or CellInfo.UNAVAILABLE  if invalid.
     * @param evdoDbm negative of the EvDO signal strength value or CellInfo.UNAVAILABLE if invalid.
     * @param evdoEcio negative of the EvDO pilot/noise ratio or CellInfo.UNAVAILABLE if invalid.
     * @param evdoSnr an SNR value 0..8 or CellInfo.UNVAILABLE if invalid.
     * @hide
     */
    public CellSignalStrengthCdma(int cdmaDbm, int cdmaEcio, int evdoDbm, int evdoEcio,
            int evdoSnr) {
        mCdmaDbm = inRangeOrUnavailable(cdmaDbm, -120, 0);
        mCdmaEcio = inRangeOrUnavailable(cdmaEcio, -160, 0);
        mEvdoDbm = inRangeOrUnavailable(evdoDbm, -120, 0);
        mEvdoEcio = inRangeOrUnavailable(evdoEcio, -160, 0);
        mEvdoSnr = inRangeOrUnavailable(evdoSnr, 0, 8);

        updateLevel(null, null);
    }

    /** @hide */
    public CellSignalStrengthCdma(android.hardware.radio.V1_0.CdmaSignalStrength cdma,
            android.hardware.radio.V1_0.EvdoSignalStrength evdo) {
        // Convert from HAL values as part of construction.
        this(-cdma.dbm, -cdma.ecio, -evdo.dbm, -evdo.ecio, evdo.signalNoiseRatio);
    }

    /** @hide */
    public CellSignalStrengthCdma(CellSignalStrengthCdma s) {
        copyFrom(s);
    }

    /** @hide */
    protected void copyFrom(CellSignalStrengthCdma s) {
        mCdmaDbm = s.mCdmaDbm;
        mCdmaEcio = s.mCdmaEcio;
        mEvdoDbm = s.mEvdoDbm;
        mEvdoEcio = s.mEvdoEcio;
        mEvdoSnr = s.mEvdoSnr;
        mLevel = s.mLevel;
    }

    /** @hide */
    @Override
    public CellSignalStrengthCdma copy() {
        return new CellSignalStrengthCdma(this);
    }

    /** @hide */
    @Override
    public void setDefaultValues() {
        mCdmaDbm = CellInfo.UNAVAILABLE;
        mCdmaEcio = CellInfo.UNAVAILABLE;
        mEvdoDbm = CellInfo.UNAVAILABLE;
        mEvdoEcio = CellInfo.UNAVAILABLE;
        mEvdoSnr = CellInfo.UNAVAILABLE;
        mLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /**
     * Retrieve an abstract level value for the overall signal strength.
     *
     * @return a single integer from 0 to 4 representing the general signal quality.
     *     0 represents very poor signal strength while 4 represents a very strong signal strength.
     */
    @Override
    public int getLevel() {
        return mLevel;
    }

    /** @hide */
    @Override
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        int cdmaLevel = getCdmaLevel();
        int evdoLevel = getEvdoLevel();
        if (evdoLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know evdo, use cdma */
            mLevel = getCdmaLevel();
        } else if (cdmaLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know cdma, use evdo */
            mLevel = getEvdoLevel();
        } else {
            /* We know both, use the lowest level */
            mLevel = cdmaLevel < evdoLevel ? cdmaLevel : evdoLevel;
        }
    }

    /**
     * Get the 1xRTT Level in (Android) ASU.
     *
     * There is no standard definition of ASU for CDMA; however, Android defines it as the
     * the lesser of the following two results (for 1xRTT):
     * <table>
     *     <thead><tr><th>RSSI Range (dBm)</th><th>ASU Value</th></tr><thead>
     *     <tbody>
     *         <tr><td>-75..</td><td>16</td></tr>
     *         <tr><td>-82..-76</td><td>8</td></tr>
     *         <tr><td>-90..-83</td><td>4</td></tr>
     *         <tr><td>-95..-91</td><td>2</td></tr>
     *         <tr><td>-100..-96</td><td>1</td></tr>
     *         <tr><td>..-101</td><td>99</td></tr>
     *     </tbody>
     * </table>
     * <table>
     *     <thead><tr><th>Ec/Io Range (dB)</th><th>ASU Value</th></tr><thead>
     *     <tbody>
     *         <tr><td>-90..</td><td>16</td></tr>
     *         <tr><td>-100..-91</td><td>8</td></tr>
     *         <tr><td>-115..-101</td><td>4</td></tr>
     *         <tr><td>-130..-116</td><td>2</td></tr>
     *         <tr><td>--150..-131</td><td>1</td></tr>
     *         <tr><td>..-151</td><td>99</td></tr>
     *     </tbody>
     * </table>
     * @return 1xRTT Level in Android ASU {1,2,4,8,16,99}
     */
    @Override
    public int getAsuLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int cdmaAsuLevel;
        int ecioAsuLevel;

        if (cdmaDbm == CellInfo.UNAVAILABLE) cdmaAsuLevel = 99;
        else if (cdmaDbm >= -75) cdmaAsuLevel = 16;
        else if (cdmaDbm >= -82) cdmaAsuLevel = 8;
        else if (cdmaDbm >= -90) cdmaAsuLevel = 4;
        else if (cdmaDbm >= -95) cdmaAsuLevel = 2;
        else if (cdmaDbm >= -100) cdmaAsuLevel = 1;
        else cdmaAsuLevel = 99;

        // Ec/Io are in dB*10
        if (cdmaEcio == CellInfo.UNAVAILABLE) ecioAsuLevel = 99;
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

        if (cdmaDbm == CellInfo.UNAVAILABLE) levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (cdmaDbm >= -75) levelDbm = SIGNAL_STRENGTH_GREAT;
        else if (cdmaDbm >= -85) levelDbm = SIGNAL_STRENGTH_GOOD;
        else if (cdmaDbm >= -95) levelDbm = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaDbm >= -100) levelDbm = SIGNAL_STRENGTH_POOR;
        else levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        // Ec/Io are in dB*10
        if (cdmaEcio == CellInfo.UNAVAILABLE) levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
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

        if (evdoDbm == CellInfo.UNAVAILABLE) levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (evdoDbm >= -65) levelEvdoDbm = SIGNAL_STRENGTH_GREAT;
        else if (evdoDbm >= -75) levelEvdoDbm = SIGNAL_STRENGTH_GOOD;
        else if (evdoDbm >= -90) levelEvdoDbm = SIGNAL_STRENGTH_MODERATE;
        else if (evdoDbm >= -105) levelEvdoDbm = SIGNAL_STRENGTH_POOR;
        else levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (evdoSnr == CellInfo.UNAVAILABLE) levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
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
     * Get the EVDO Level in (Android) ASU.
     *
     * There is no standard definition of ASU for CDMA; however, Android defines it as the
     * the lesser of the following two results (for EVDO):
     * <table>
     *     <thead><tr><th>RSSI Range (dBm)</th><th>ASU Value</th></tr><thead>
     *     <tbody>
     *         <tr><td>-65..</td><td>16</td></tr>
     *         <tr><td>-75..-66</td><td>8</td></tr>
     *         <tr><td>-85..-76</td><td>4</td></tr>
     *         <tr><td>-95..-86</td><td>2</td></tr>
     *         <tr><td>-105..-96</td><td>1</td></tr>
     *         <tr><td>..-106</td><td>99</td></tr>
     *     </tbody>
     * </table>
     * <table>
     *     <thead><tr><th>SNR Range (unitless)</th><th>ASU Value</th></tr><thead>
     *     <tbody>
     *         <tr><td>7..</td><td>16</td></tr>
     *         <tr><td>6</td><td>8</td></tr>
     *         <tr><td>5</td><td>4</td></tr>
     *         <tr><td>3..4</td><td>2</td></tr>
     *         <tr><td>1..2</td><td>1</td></tr>
     *         <tr><td>0</td><td>99</td></tr>
     *     </tbody>
     * </table>
     *
     * @return EVDO Level in Android ASU {1,2,4,8,16,99}
     *
     * @hide
     */
    public int getEvdoAsuLevel() {
        int evdoDbm = getEvdoDbm();
        int evdoSnr = getEvdoSnr();
        int levelEvdoDbm;
        int levelEvdoSnr;

        if (evdoDbm >= -65) levelEvdoDbm = 16;
        else if (evdoDbm >= -75) levelEvdoDbm = 8;
        else if (evdoDbm >= -85) levelEvdoDbm = 4;
        else if (evdoDbm >= -95) levelEvdoDbm = 2;
        else if (evdoDbm >= -105) levelEvdoDbm = 1;
        else levelEvdoDbm = 99;

        if (evdoSnr >= 7) levelEvdoSnr = 16;
        else if (evdoSnr >= 6) levelEvdoSnr = 8;
        else if (evdoSnr >= 5) levelEvdoSnr = 4;
        else if (evdoSnr >= 3) levelEvdoSnr = 2;
        else if (evdoSnr >= 1) levelEvdoSnr = 1;
        else levelEvdoSnr = 99;

        int level = (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
        if (DBG) log("getEvdoAsuLevel=" + level);
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
        return Objects.hash(mCdmaDbm, mCdmaEcio, mEvdoDbm, mEvdoEcio, mEvdoSnr, mLevel);
    }

    private static final CellSignalStrengthCdma sInvalid = new CellSignalStrengthCdma();

    /** @hide */
    @Override
    public boolean isValid() {
        return !this.equals(sInvalid);
    }

    @Override
    public boolean equals (Object o) {
        CellSignalStrengthCdma s;
        if (!(o instanceof CellSignalStrengthCdma)) return false;
        s = (CellSignalStrengthCdma) o;

        return mCdmaDbm == s.mCdmaDbm
                && mCdmaEcio == s.mCdmaEcio
                && mEvdoDbm == s.mEvdoDbm
                && mEvdoEcio == s.mEvdoEcio
                && mEvdoSnr == s.mEvdoSnr
                && mLevel == s.mLevel;
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
                + " evdoSnr=" + mEvdoSnr
                + " level=" + mLevel;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (DBG) log("writeToParcel(Parcel, int): " + toString());
        dest.writeInt(mCdmaDbm);
        dest.writeInt(mCdmaEcio);
        dest.writeInt(mEvdoDbm);
        dest.writeInt(mEvdoEcio);
        dest.writeInt(mEvdoSnr);
        dest.writeInt(mLevel);
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
        mCdmaEcio = in.readInt();
        mEvdoDbm = in.readInt();
        mEvdoEcio = in.readInt();
        mEvdoSnr = in.readInt();
        mLevel = in.readInt();
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
