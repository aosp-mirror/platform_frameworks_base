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

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Contains phone signal strength related information.
 */
public class SignalStrength implements Parcelable {

    private static final String LOG_TAG = "SignalStrength";
    private static final boolean DBG = false;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN =
            CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN; // = 0
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int SIGNAL_STRENGTH_POOR =
            CellSignalStrength.SIGNAL_STRENGTH_POOR; // = 1
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int SIGNAL_STRENGTH_MODERATE =
            CellSignalStrength.SIGNAL_STRENGTH_MODERATE; // = 2
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int SIGNAL_STRENGTH_GOOD =
            CellSignalStrength.SIGNAL_STRENGTH_GOOD; // = 3
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int SIGNAL_STRENGTH_GREAT =
            CellSignalStrength.SIGNAL_STRENGTH_GREAT; // = 4
    /** @hide */
    @UnsupportedAppUsage
    public static final int NUM_SIGNAL_STRENGTH_BINS = 5;

    /** SIGNAL_STRENGTH_NAMES is currently used by BatteryStats, but to-be-removed soon. */
    /** @hide */
    public static final String[] SIGNAL_STRENGTH_NAMES = {
        "none", "poor", "moderate", "good", "great"
    };

    /**
     * Indicates the invalid measures of signal strength.
     *
     * For example, this can be returned by {@link #getEvdoDbm()} or {@link #getCdmaDbm()}
     */
    public static final int INVALID = Integer.MAX_VALUE;

    private static final int LTE_RSRP_THRESHOLDS_NUM = 4;

    private static final int WCDMA_RSCP_THRESHOLDS_NUM = 4;

    /* The type of signal measurement */
    private static final String MEASUREMENT_TYPE_RSCP = "rscp";

    CellSignalStrengthCdma mCdma;
    CellSignalStrengthGsm mGsm;
    CellSignalStrengthWcdma mWcdma;
    CellSignalStrengthTdscdma mTdscdma;
    CellSignalStrengthLte mLte;

    /**
     * Create a new SignalStrength from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created SignalStrength
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static SignalStrength newFromBundle(Bundle m) {
        SignalStrength ret;
        ret = new SignalStrength();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * This constructor is used to create SignalStrength with default
     * values.
     *
     * @return newly created SignalStrength
     * @hide
     */
    @UnsupportedAppUsage
    public SignalStrength() {
        this(new CellSignalStrengthCdma(), new CellSignalStrengthGsm(),
                new CellSignalStrengthWcdma(), new CellSignalStrengthTdscdma(),
                new CellSignalStrengthLte());
    }

    /**
     * Constructor with all fields present
     *
     * @hide
     */
    public SignalStrength(
            @NonNull CellSignalStrengthCdma cdma,
            @NonNull CellSignalStrengthGsm gsm,
            @NonNull CellSignalStrengthWcdma wcdma,
            @NonNull CellSignalStrengthTdscdma tdscdma,
            @NonNull CellSignalStrengthLte lte) {
        mCdma = cdma;
        mGsm = gsm;
        mWcdma = wcdma;
        mTdscdma = tdscdma;
        mLte = lte;
    }

    /**
     * Constructor for Radio HAL V1.0
     *
     * @hide
     */
    public SignalStrength(android.hardware.radio.V1_0.SignalStrength signalStrength) {
        this(new CellSignalStrengthCdma(signalStrength.cdma, signalStrength.evdo),
                new CellSignalStrengthGsm(signalStrength.gw),
                new CellSignalStrengthWcdma(),
                new CellSignalStrengthTdscdma(signalStrength.tdScdma),
                new CellSignalStrengthLte(signalStrength.lte));
    }

    /**
     * Constructor for Radio HAL V1.2
     *
     * @hide
     */
    public SignalStrength(android.hardware.radio.V1_2.SignalStrength signalStrength) {
        this(new CellSignalStrengthCdma(signalStrength.cdma, signalStrength.evdo),
                new CellSignalStrengthGsm(signalStrength.gsm),
                new CellSignalStrengthWcdma(signalStrength.wcdma),
                new CellSignalStrengthTdscdma(signalStrength.tdScdma),
                new CellSignalStrengthLte(signalStrength.lte));
    }

    private CellSignalStrength getPrimary() {
        // This behavior is intended to replicate the legacy behavior of getLevel() by prioritizing
        // newer faster RATs for default/for display purposes.
        if (mLte.isValid()) return mLte;
        if (mCdma.isValid()) return mCdma;
        if (mTdscdma.isValid()) return mTdscdma;
        if (mWcdma.isValid()) return mWcdma;
        if (mGsm.isValid()) return mGsm;
        return mLte;
    }

    /**
     * Returns a List of CellSignalStrength Components of this SignalStrength Report.
     *
     * Use this API to access underlying
     * {@link android.telephony#CellSignalStrength CellSignalStrength} objects that provide more
     * granular information about the SignalStrength report. Only valid (non-empty)
     * CellSignalStrengths will be returned. The order of any returned elements is not guaranteed,
     * and the list may contain more than one instance of a CellSignalStrength type.
     *
     * @return a List of CellSignalStrength or an empty List if there are no valid measurements.
     *
     * @see android.telephony#CellSignalStrength
     * @see android.telephony#CellSignalStrengthNr
     * @see android.telephony#CellSignalStrengthLte
     * @see android.telephony#CellSignalStrengthTdscdma
     * @see android.telephony#CellSignalStrengthWcdma
     * @see android.telephony#CellSignalStrengthCdma
     * @see android.telephony#CellSignalStrengthGsm
     */
    public @NonNull List<CellSignalStrength> getCellSignalStrengths() {
        List<CellSignalStrength> cssList = new ArrayList<>(2); // Usually have 2 or fewer elems
        if (mLte.isValid()) cssList.add(mLte);
        if (mCdma.isValid()) cssList.add(mCdma);
        if (mTdscdma.isValid()) cssList.add(mTdscdma);
        if (mWcdma.isValid()) cssList.add(mWcdma);
        if (mGsm.isValid()) cssList.add(mGsm);
        return cssList;
    }

    /** @hide */
    public void updateLevel(PersistableBundle cc, ServiceState ss) {
        mCdma.updateLevel(cc, ss);
        mGsm.updateLevel(cc, ss);
        mWcdma.updateLevel(cc, ss);
        mTdscdma.updateLevel(cc, ss);
        mLte.updateLevel(cc, ss);
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    @UnsupportedAppUsage
    public SignalStrength(SignalStrength s) {
        copyFrom(s);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    protected void copyFrom(SignalStrength s) {
        mCdma = new CellSignalStrengthCdma(s.mCdma);
        mGsm = new CellSignalStrengthGsm(s.mGsm);
        mWcdma = new CellSignalStrengthWcdma(s.mWcdma);
        mTdscdma = new CellSignalStrengthTdscdma(s.mTdscdma);
        mLte = new CellSignalStrengthLte(s.mLte);
    }

    /**
     * Construct a SignalStrength object from the given parcel.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public SignalStrength(Parcel in) {
        if (DBG) log("Size of signalstrength parcel:" + in.dataSize());

        mCdma = in.readParcelable(CellSignalStrengthCdma.class.getClassLoader());
        mGsm = in.readParcelable(CellSignalStrengthGsm.class.getClassLoader());
        mWcdma = in.readParcelable(CellSignalStrengthWcdma.class.getClassLoader());
        mTdscdma = in.readParcelable(CellSignalStrengthTdscdma.class.getClassLoader());
        mLte = in.readParcelable(CellSignalStrengthLte.class.getClassLoader());
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mCdma, flags);
        out.writeParcelable(mGsm, flags);
        out.writeParcelable(mWcdma, flags);
        out.writeParcelable(mTdscdma, flags);
        out.writeParcelable(mLte, flags);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable.Creator}
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final Parcelable.Creator<SignalStrength> CREATOR = new Parcelable.Creator() {
        public SignalStrength createFromParcel(Parcel in) {
            return new SignalStrength(in);
        }

        public SignalStrength[] newArray(int size) {
            return new SignalStrength[size];
        }
    };

    /**
     * Get the GSM RSSI in ASU.
     *
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @return RSSI in ASU 0..31, 99, or UNAVAILABLE
     */
    public int getGsmSignalStrength() {
        return mGsm.getAsuLevel();
    }

    /**
     * Get the GSM bit error rate (0-7, 99) as defined in TS 27.007 8.5
     */
    public int getGsmBitErrorRate() {
        return mGsm.getBitErrorRate();
    }

    /**
     * Get the CDMA RSSI value in dBm
     *
     * @return the CDMA RSSI value or {@link #INVALID} if invalid
     */
    public int getCdmaDbm() {
        return mCdma.getCdmaDbm();
    }

    /**
     * Get the CDMA Ec/Io value in dB*10
     */
    public int getCdmaEcio() {
        return mCdma.getCdmaEcio();
    }

    /**
     * Get the EVDO RSSI value in dBm
     *
     * @return the EVDO RSSI value or {@link #INVALID} if invalid
     */
    public int getEvdoDbm() {
        return mCdma.getEvdoDbm();
    }

    /**
     * Get the EVDO Ec/Io value in dB*10
     */
    public int getEvdoEcio() {
        return mCdma.getEvdoEcio();
    }

    /**
     * Get the signal to noise ratio. Valid values are 0-8. 8 is the highest.
     */
    public int getEvdoSnr() {
        return mCdma.getEvdoSnr();
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getLteSignalStrength() {
        return mLte.getRssi();
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getLteRsrp() {
        return mLte.getRsrp();
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getLteRsrq() {
        return mLte.getRsrq();
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getLteRssnr() {
        return mLte.getRssnr();
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getLteCqi() {
        return mLte.getCqi();
    }

    /**
     * Retrieve an abstract level value for the overall signal strength.
     *
     * @return a single integer from 0 to 4 representing the general signal quality.
     *     This may take into account many different radio technology inputs.
     *     0 represents very poor signal strength
     *     while 4 represents a very strong signal strength.
     */
    public int getLevel() {
        int level = getPrimary().getLevel();
        if (level < SIGNAL_STRENGTH_NONE_OR_UNKNOWN || level > SIGNAL_STRENGTH_GREAT) {
            loge("Invalid Level " + level + ", this=" + this);
            return SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
        return getPrimary().getLevel();
    }

    /**
     * Get the signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getAsuLevel() {
        return getPrimary().getAsuLevel();
    }

    /**
     * Get the signal strength as dBm
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getDbm() {
        return getPrimary().getDbm();
    }

    /**
     * Get Gsm signal strength as dBm
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getGsmDbm() {
        return mGsm.getDbm();
    }

    /**
     * Get gsm as level 0..4
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getGsmLevel() {
        return mGsm.getLevel();
    }

    /**
     * Get the gsm signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getGsmAsuLevel() {
        return mGsm.getAsuLevel();
    }

    /**
     * Get cdma as level 0..4
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaLevel() {
        return mCdma.getLevel();
    }

    /**
     * Get the cdma signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaAsuLevel() {
        return mCdma.getAsuLevel();
    }

    /**
     * Get Evdo as level 0..4
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getEvdoLevel() {
        return mCdma.getEvdoLevel();
    }

    /**
     * Get the evdo signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getEvdoAsuLevel() {
        return mCdma.getEvdoAsuLevel();
    }

    /**
     * Get LTE as dBm
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getLteDbm() {
        return mLte.getRsrp();
    }

    /**
     * Get LTE as level 0..4
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getLteLevel() {
        return mLte.getLevel();
    }

    /**
     * Get the LTE signal level as an asu value between 0..97, 99 is unknown
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getLteAsuLevel() {
        return mLte.getAsuLevel();
    }

    /**
     * @return true if this is for GSM
     */
    public boolean isGsm() {
        return !(getPrimary() instanceof CellSignalStrengthCdma);
    }

    /**
     * @return get TD_SCDMA dbm
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getTdScdmaDbm() {
        return mTdscdma.getRscp();
    }

    /**
     * Get TD-SCDMA as level 0..4
     * Range : 25 to 120
     * INT_MAX: 0x7FFFFFFF denotes invalid value
     * Reference: 3GPP TS 25.123, section 9.1.1.1
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getTdScdmaLevel() {
        return mTdscdma.getLevel();
     }

    /**
     * Get the TD-SCDMA signal level as an asu value.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getTdScdmaAsuLevel() {
        return mTdscdma.getAsuLevel();
    }

    /**
     * Gets WCDMA RSCP as a dbm value between -120 and -24, as defined in TS 27.007 8.69.
     *
     * @hide
     */
    public int getWcdmaRscp() {
        return mWcdma.getRscp();
    }

    /**
     * Get the WCDMA signal level as an ASU value between 0-96, 255 is unknown
     *
     * @hide
     */
    public int getWcdmaAsuLevel() {
        /*
         * 3GPP 27.007 (Ver 10.3.0) Sec 8.69
         * 0      -120 dBm or less
         * 1      -119 dBm
         * 2...95 -118... -25 dBm
         * 96     -24 dBm or greater
         * 255    not known or not detectable
         */
        return mWcdma.getAsuLevel();
    }

    /**
     * Gets WCDMA signal strength as a dbm value between -120 and -24, as defined in TS 27.007 8.69.
     *
     * @hide
     */
    public int getWcdmaDbm() {
        return mWcdma.getDbm();
    }

    /**
     * Get WCDMA as level 0..4
     *
     * @hide
     */
    public int getWcdmaLevel() {
        return mWcdma.getLevel();
    }

   /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mCdma, mGsm, mWcdma, mTdscdma, mLte);
    }

    /**
     * @return true if the signal strengths are the same
     */
    @Override
    public boolean equals (Object o) {
        if (!(o instanceof SignalStrength)) return false;

        SignalStrength s = (SignalStrength) o;

        return mCdma.equals(s.mCdma)
            && mGsm.equals(s.mGsm)
            && mWcdma.equals(s.mWcdma)
            && mTdscdma.equals(s.mTdscdma)
            && mLte.equals(s.mLte);
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return new StringBuilder().append("SignalStrength:{")
            .append("mCdma=").append(mCdma)
            .append(",mGsm=").append(mGsm)
            .append(",mWcdma=").append(mWcdma)
            .append(",mTdscdma=").append(mTdscdma)
            .append(",mLte=").append(mLte)
            .append(",primary=").append(getPrimary().getClass().getSimpleName())
            .append("}")
            .toString();
    }

    /**
     * Set SignalStrength based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    @UnsupportedAppUsage
    private void setFromNotifierBundle(Bundle m) {
        mCdma = m.getParcelable("Cdma");
        mGsm = m.getParcelable("Gsm");
        mWcdma = m.getParcelable("Wcdma");
        mTdscdma = m.getParcelable("Tdscdma");
        mLte = m.getParcelable("Lte");
    }

    /**
     * Set intent notifier Bundle based on SignalStrength
     *
     * @param m intent notifier Bundle
     * @hide
     */
    @UnsupportedAppUsage
    public void fillInNotifierBundle(Bundle m) {
        m.putParcelable("Cdma", mCdma);
        m.putParcelable("Gsm", mGsm);
        m.putParcelable("Wcdma", mWcdma);
        m.putParcelable("Tdscdma", mTdscdma);
        m.putParcelable("Lte", mLte);
    }

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
