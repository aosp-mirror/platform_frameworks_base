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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.CarrierConfigManager;
import android.util.Log;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Contains phone signal strength related information.
 */
public class SignalStrength implements Parcelable {

    private static final String LOG_TAG = "SignalStrength";
    private static final boolean DBG = false;

    /** @hide */
    public static final int SIGNAL_STRENGTH_NONE_OR_UNKNOWN
            = TelephonyProtoEnums.SIGNAL_STRENGTH_NONE_OR_UNKNOWN; // = 0
    /** @hide */
    public static final int SIGNAL_STRENGTH_POOR
            = TelephonyProtoEnums.SIGNAL_STRENGTH_POOR; // = 1
    /** @hide */
    public static final int SIGNAL_STRENGTH_MODERATE
            = TelephonyProtoEnums.SIGNAL_STRENGTH_MODERATE; // = 2
    /** @hide */
    public static final int SIGNAL_STRENGTH_GOOD
            = TelephonyProtoEnums.SIGNAL_STRENGTH_GOOD; // = 3
    /** @hide */
    public static final int SIGNAL_STRENGTH_GREAT
            = TelephonyProtoEnums.SIGNAL_STRENGTH_GREAT; // = 4
    /** @hide */
    public static final int NUM_SIGNAL_STRENGTH_BINS = 5;
    /** @hide */
    public static final String[] SIGNAL_STRENGTH_NAMES = {
        "none", "poor", "moderate", "good", "great"
    };

    /**
     * Use Integer.MAX_VALUE because -1 is a valid value in signal strength.
     * @hide
     */
    public static final int INVALID = Integer.MAX_VALUE;

    private static final int LTE_RSRP_THRESHOLDS_NUM = 4;
    private static final int MAX_LTE_RSRP = -44;
    private static final int MIN_LTE_RSRP = -140;

    private static final int WCDMA_RSCP_THRESHOLDS_NUM = 4;
    private static final int MAX_WCDMA_RSCP = -24;
    private static final int MIN_WCDMA_RSCP = -120;

    /* The type of signal measurement */
    private static final String MEASUMENT_TYPE_RSCP = "rscp";

    /** Parameters reported by the Radio */
    private int mGsmSignalStrength; // Valid values are (0-31, 99) as defined in TS 27.007 8.5
    private int mGsmBitErrorRate;   // bit error rate (0-7, 99) as defined in TS 27.007 8.5
    private int mCdmaDbm;   // This value is the RSSI value
    private int mCdmaEcio;  // This value is the Ec/Io
    private int mEvdoDbm;   // This value is the EVDO RSSI value
    private int mEvdoEcio;  // This value is the EVDO Ec/Io
    private int mEvdoSnr;   // Valid values are 0-8.  8 is the highest signal to noise ratio
    private int mLteSignalStrength;
    private int mLteRsrp;
    private int mLteRsrq;
    private int mLteRssnr;
    private int mLteCqi;
    private int mTdScdmaRscp; // Valid values are -24...-120dBm or INVALID if unknown
    private int mWcdmaSignalStrength;
    private int mWcdmaRscpAsu;  // the WCDMA RSCP in ASU as reported from the HAL
    private int mWcdmaRscp;     // the WCDMA RSCP in dBm

    /** Parameters from the framework */
    private int mLteRsrpBoost; // offset to be reduced from the rsrp threshold while calculating
                                // signal strength level
    private boolean mIsGsm; // This value is set by the ServiceStateTracker
                            // onSignalStrengthResult.
    private boolean mUseOnlyRsrpForLteLevel; // Use only RSRP for the number of LTE signal bar.

    // The threshold of LTE RSRP for determining the display level of LTE signal bar. Note that the
    // min and max are fixed at MIN_LTE_RSRP (-140) and MAX_LTE_RSRP (-44).
    private int mLteRsrpThresholds[] = new int[LTE_RSRP_THRESHOLDS_NUM];

    // The type of default measurement for determining the display level of WCDMA signal bar.
    private String mWcdmaDefaultSignalMeasurement;

    // The threshold of WCDMA RSCP for determining the display level of WCDMA signal bar. Note that
    // the min and max are fixed at MIN_WCDMA_RSCP (-120) and MAX_WCDMA_RSCP (-24).
    private int mWcdmaRscpThresholds[] = new int[WCDMA_RSCP_THRESHOLDS_NUM];

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
    public static SignalStrength newFromBundle(Bundle m) {
        SignalStrength ret;
        ret = new SignalStrength();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     *
     * @hide
     */
    public SignalStrength() {
        this(true);
    }

    /**
     * This constructor is used to create SignalStrength with default
     * values and set the gsmFlag with the value passed in the input
     *
     * @param gsmFlag true if Gsm Phone,false if Cdma phone
     * @return newly created SignalStrength
     * @hide
     */
    public SignalStrength(boolean gsmFlag) {
        mGsmSignalStrength = 99;
        mGsmBitErrorRate = -1;
        mCdmaDbm = -1;
        mCdmaEcio = -1;
        mEvdoDbm = -1;
        mEvdoEcio = -1;
        mEvdoSnr = -1;
        mLteSignalStrength = 99;
        mLteRsrp = INVALID;
        mLteRsrq = INVALID;
        mLteRssnr = INVALID;
        mLteCqi = INVALID;
        mTdScdmaRscp = INVALID;
        mWcdmaSignalStrength = 99;
        mWcdmaRscp = INVALID;
        mWcdmaRscpAsu = 255;
        mLteRsrpBoost = 0;
        mIsGsm = gsmFlag;
        mUseOnlyRsrpForLteLevel = false;
        mWcdmaDefaultSignalMeasurement = "";
        setLteRsrpThresholds(getDefaultLteRsrpThresholds());
        setWcdmaRscpThresholds(getDefaultWcdmaRscpThresholds());
    }

    /**
     * Constructor with all fields present
     *
     * @hide
     */
    public SignalStrength(
            int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            int tdScdmaRscp, int wcdmaSignalStrength, int wcdmaRscpAsu,
            // values Added by config
            int lteRsrpBoost, boolean gsmFlag, boolean lteLevelBaseOnRsrp,
            String wcdmaDefaultMeasurement) {
        mGsmSignalStrength = gsmSignalStrength;
        mGsmBitErrorRate = gsmBitErrorRate;
        mCdmaDbm = cdmaDbm;
        mCdmaEcio = cdmaEcio;
        mEvdoDbm = evdoDbm;
        mEvdoEcio = evdoEcio;
        mEvdoSnr = evdoSnr;
        mLteSignalStrength = lteSignalStrength;
        mLteRsrp = lteRsrp;
        mLteRsrq = lteRsrq;
        mLteRssnr = lteRssnr;
        mLteCqi = lteCqi;
        mTdScdmaRscp = INVALID;
        mWcdmaSignalStrength = wcdmaSignalStrength;
        mWcdmaRscpAsu = wcdmaRscpAsu;
        mWcdmaRscp = wcdmaRscpAsu - 120;
        mLteRsrpBoost = lteRsrpBoost;
        mIsGsm = gsmFlag;
        mUseOnlyRsrpForLteLevel = lteLevelBaseOnRsrp;
        mWcdmaDefaultSignalMeasurement = wcdmaDefaultMeasurement;
        setLteRsrpThresholds(getDefaultLteRsrpThresholds());
        setWcdmaRscpThresholds(getDefaultWcdmaRscpThresholds());
        if (DBG) log("initialize: " + toString());
    }

    /**
     * Constructor for only values provided by Radio HAL V1.0
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            int tdScdmaRscp) {
        this(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, lteSignalStrength, lteRsrp,
                lteRsrq, lteRssnr, lteCqi, tdScdmaRscp, 99, INVALID, 0, true, false, "");
    }

    /**
     * Constructor for only values provided by Radio HAL V1.2
     *
     * @hide
     */
    public SignalStrength(int gsmSignalStrength, int gsmBitErrorRate,
            int cdmaDbm, int cdmaEcio,
            int evdoDbm, int evdoEcio, int evdoSnr,
            int lteSignalStrength, int lteRsrp, int lteRsrq, int lteRssnr, int lteCqi,
            int tdScdmaRscp, int wcdmaSignalStrength, int wcdmaRscp) {
        this(gsmSignalStrength, gsmBitErrorRate, cdmaDbm, cdmaEcio,
                evdoDbm, evdoEcio, evdoSnr, lteSignalStrength, lteRsrp,
                lteRsrq, lteRssnr, lteCqi, tdScdmaRscp, wcdmaSignalStrength, wcdmaRscp, 0, true,
                false, "");
    }

    /**
     * Copy constructors
     *
     * @param s Source SignalStrength
     *
     * @hide
     */
    public SignalStrength(SignalStrength s) {
        copyFrom(s);
    }

    /**
     * @hide
     */
    protected void copyFrom(SignalStrength s) {
        mGsmSignalStrength = s.mGsmSignalStrength;
        mGsmBitErrorRate = s.mGsmBitErrorRate;
        mCdmaDbm = s.mCdmaDbm;
        mCdmaEcio = s.mCdmaEcio;
        mEvdoDbm = s.mEvdoDbm;
        mEvdoEcio = s.mEvdoEcio;
        mEvdoSnr = s.mEvdoSnr;
        mLteSignalStrength = s.mLteSignalStrength;
        mLteRsrp = s.mLteRsrp;
        mLteRsrq = s.mLteRsrq;
        mLteRssnr = s.mLteRssnr;
        mLteCqi = s.mLteCqi;
        mTdScdmaRscp = s.mTdScdmaRscp;
        mWcdmaSignalStrength = s.mWcdmaSignalStrength;
        mWcdmaRscpAsu = s.mWcdmaRscpAsu;
        mWcdmaRscp = s.mWcdmaRscp;
        mLteRsrpBoost = s.mLteRsrpBoost;
        mIsGsm = s.mIsGsm;
        mUseOnlyRsrpForLteLevel = s.mUseOnlyRsrpForLteLevel;
        mWcdmaDefaultSignalMeasurement = s.mWcdmaDefaultSignalMeasurement;
        setLteRsrpThresholds(s.mLteRsrpThresholds);
        setWcdmaRscpThresholds(s.mWcdmaRscpThresholds);
    }

    /**
     * Construct a SignalStrength object from the given parcel.
     *
     * @hide
     */
    public SignalStrength(Parcel in) {
        if (DBG) log("Size of signalstrength parcel:" + in.dataSize());

        mGsmSignalStrength = in.readInt();
        mGsmBitErrorRate = in.readInt();
        mCdmaDbm = in.readInt();
        mCdmaEcio = in.readInt();
        mEvdoDbm = in.readInt();
        mEvdoEcio = in.readInt();
        mEvdoSnr = in.readInt();
        mLteSignalStrength = in.readInt();
        mLteRsrp = in.readInt();
        mLteRsrq = in.readInt();
        mLteRssnr = in.readInt();
        mLteCqi = in.readInt();
        mTdScdmaRscp = in.readInt();
        mWcdmaSignalStrength = in.readInt();
        mWcdmaRscpAsu = in.readInt();
        mWcdmaRscp = in.readInt();
        mLteRsrpBoost = in.readInt();
        mIsGsm = in.readBoolean();
        mUseOnlyRsrpForLteLevel = in.readBoolean();
        mWcdmaDefaultSignalMeasurement = in.readString();
        in.readIntArray(mLteRsrpThresholds);
        in.readIntArray(mWcdmaRscpThresholds);
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mGsmSignalStrength);
        out.writeInt(mGsmBitErrorRate);
        out.writeInt(mCdmaDbm);
        out.writeInt(mCdmaEcio);
        out.writeInt(mEvdoDbm);
        out.writeInt(mEvdoEcio);
        out.writeInt(mEvdoSnr);
        out.writeInt(mLteSignalStrength);
        out.writeInt(mLteRsrp);
        out.writeInt(mLteRsrq);
        out.writeInt(mLteRssnr);
        out.writeInt(mLteCqi);
        out.writeInt(mTdScdmaRscp);
        out.writeInt(mWcdmaSignalStrength);
        out.writeInt(mWcdmaRscpAsu);
        out.writeInt(mWcdmaRscp);
        out.writeInt(mLteRsrpBoost);
        out.writeBoolean(mIsGsm);
        out.writeBoolean(mUseOnlyRsrpForLteLevel);
        out.writeString(mWcdmaDefaultSignalMeasurement);
        out.writeIntArray(mLteRsrpThresholds);
        out.writeIntArray(mWcdmaRscpThresholds);
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
    public static final Parcelable.Creator<SignalStrength> CREATOR = new Parcelable.Creator() {
        public SignalStrength createFromParcel(Parcel in) {
            return new SignalStrength(in);
        }

        public SignalStrength[] newArray(int size) {
            return new SignalStrength[size];
        }
    };

    /**
     * Validate the individual signal strength fields as per the range
     * specified in ril.h
     * Set to invalid any field that is not in the valid range
     * Cdma, evdo, lte rsrp & rsrq values are sign converted
     * when received from ril interface
     *
     * @return
     *      Valid values for all signalstrength fields
     * @hide
     */
    public void validateInput() {
        if (DBG) log("Signal before validate=" + this);
        // TS 27.007 8.5
        mGsmSignalStrength = mGsmSignalStrength >= 0 ? mGsmSignalStrength : 99;
        mWcdmaSignalStrength = (mWcdmaSignalStrength >= 0) ? mWcdmaSignalStrength : 99;
        mLteSignalStrength = (mLteSignalStrength >= 0) ? mLteSignalStrength : 99;
        // BER no change;

        // WCDMA RSCP valid values are -120 through -24 as defined in TS 27.007 8.69
        // but are reported in ASU which is 0 through 96, so we do the conversion here
        mWcdmaRscpAsu =
                ((mWcdmaRscpAsu - 120 >= MIN_WCDMA_RSCP) && (mWcdmaRscpAsu - 120 <= MAX_WCDMA_RSCP))
                ? mWcdmaRscpAsu : 255;
        mWcdmaRscp = ((mWcdmaRscp >= MIN_WCDMA_RSCP) && (mWcdmaRscp <= MAX_WCDMA_RSCP))
                ? mWcdmaRscp : INVALID;

        mCdmaDbm = mCdmaDbm > 0 ? -mCdmaDbm : -120;
        mCdmaEcio = (mCdmaEcio >= 0) ? -mCdmaEcio : -160;

        mEvdoDbm = (mEvdoDbm > 0) ? -mEvdoDbm : -120;
        mEvdoEcio = (mEvdoEcio >= 0) ? -mEvdoEcio : -160;
        mEvdoSnr = ((mEvdoSnr >= 0) && (mEvdoSnr <= 8)) ? mEvdoSnr : -1;

        // TS 36.214 Physical Layer Section 5.1.3, TS 36.331 RRC
        mLteRsrp = ((-mLteRsrp >= MIN_LTE_RSRP) && (-mLteRsrp <= MAX_LTE_RSRP)) ? -mLteRsrp
                                : SignalStrength.INVALID;
        mLteRsrq = ((mLteRsrq >= 3) && (mLteRsrq <= 20)) ? -mLteRsrq : SignalStrength.INVALID;
        mLteRssnr = ((mLteRssnr >= -200) && (mLteRssnr <= 300)) ? mLteRssnr
                : SignalStrength.INVALID;

        mTdScdmaRscp = ((mTdScdmaRscp >= 0) && (mTdScdmaRscp <= 96))
                ? (mTdScdmaRscp - 120) : SignalStrength.INVALID;
        // Cqi no change
        if (DBG) log("Signal after validate=" + this);
    }

    /**
     * Fix {@link #mIsGsm} based on the signal strength data.
     *
     * @hide
     */
    public void fixType() {
        mIsGsm = getCdmaRelatedSignalStrength() == SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    /**
     * @param true - Gsm, Lte phones
     *        false - Cdma phones
     *
     * Used by voice phone to set the mIsGsm
     *        flag
     * @hide
     */
    public void setGsm(boolean gsmFlag) {
        mIsGsm = gsmFlag;
    }

    /**
     * @param useOnlyRsrpForLteLevel true if it uses only RSRP for the number of LTE signal bar,
     * otherwise false.
     *
     * Used by phone to use only RSRP or not for the number of LTE signal bar.
     * @hide
     */
    public void setUseOnlyRsrpForLteLevel(boolean useOnlyRsrpForLteLevel) {
        mUseOnlyRsrpForLteLevel = useOnlyRsrpForLteLevel;
    }

    /**
     * @param defaultMeasurement sets the type of WCDMA default signal measurement
     *
     * Used by phone to determine default measurement type for calculation WCDMA signal level.
     * @hide
     */
    public void setWcdmaDefaultSignalMeasurement(String defaultMeasurement) {
        mWcdmaDefaultSignalMeasurement = defaultMeasurement;
    }

    /**
     * @param lteRsrpBoost - signal strength offset
     *
     * Used by phone to set the lte signal strength offset which will be
     * reduced from rsrp threshold while calculating signal strength level
     *
     * @hide
     */
    public void setLteRsrpBoost(int lteRsrpBoost) {
        mLteRsrpBoost = lteRsrpBoost;
    }

    /**
     * Sets the threshold array for determining the display level of LTE signal bar.
     *
     * @param lteRsrpThresholds int array for determining the display level.
     *
     * @hide
     */
    public void setLteRsrpThresholds(int[] lteRsrpThresholds) {
        if ((lteRsrpThresholds == null)
                || (lteRsrpThresholds.length != LTE_RSRP_THRESHOLDS_NUM)) {
            Log.wtf(LOG_TAG, "setLteRsrpThresholds - lteRsrpThresholds is invalid.");
            return;
        }
        System.arraycopy(lteRsrpThresholds, 0, mLteRsrpThresholds, 0, LTE_RSRP_THRESHOLDS_NUM);
    }

    /**
     * Get the GSM Signal Strength, valid values are (0-31, 99) as defined in TS
     * 27.007 8.5
     */
    public int getGsmSignalStrength() {
        return this.mGsmSignalStrength;
    }

    /**
     * Get the GSM bit error rate (0-7, 99) as defined in TS 27.007 8.5
     */
    public int getGsmBitErrorRate() {
        return this.mGsmBitErrorRate;
    }

    /**
     * Sets the threshold array for determining the display level of WCDMA signal bar.
     *
     * @param wcdmaRscpThresholds int array for determining the display level.
     *
     * @hide
     */
    public void setWcdmaRscpThresholds(int[] wcdmaRscpThresholds) {
        if ((wcdmaRscpThresholds == null)
                || (wcdmaRscpThresholds.length != WCDMA_RSCP_THRESHOLDS_NUM)) {
            Log.wtf(LOG_TAG, "setWcdmaRscpThresholds - wcdmaRscpThresholds is invalid.");
            return;
        }
        System.arraycopy(wcdmaRscpThresholds, 0, mWcdmaRscpThresholds, 0,
                WCDMA_RSCP_THRESHOLDS_NUM);
    }

    /**
     * Get the CDMA RSSI value in dBm
     */
    public int getCdmaDbm() {
        return this.mCdmaDbm;
    }

    /**
     * Get the CDMA Ec/Io value in dB*10
     */
    public int getCdmaEcio() {
        return this.mCdmaEcio;
    }

    /**
     * Get the EVDO RSSI value in dBm
     */
    public int getEvdoDbm() {
        return this.mEvdoDbm;
    }

    /**
     * Get the EVDO Ec/Io value in dB*10
     */
    public int getEvdoEcio() {
        return this.mEvdoEcio;
    }

    /**
     * Get the signal to noise ratio. Valid values are 0-8. 8 is the highest.
     */
    public int getEvdoSnr() {
        return this.mEvdoSnr;
    }

    /** @hide */
    public int getLteSignalStrength() {
        return mLteSignalStrength;
    }

    /** @hide */
    public int getLteRsrp() {
        return mLteRsrp;
    }

    /** @hide */
    public int getLteRsrq() {
        return mLteRsrq;
    }

    /** @hide */
    public int getLteRssnr() {
        return mLteRssnr;
    }

    /** @hide */
    public int getLteCqi() {
        return mLteCqi;
    }

    /** @hide */
    public int getLteRsrpBoost() {
        return mLteRsrpBoost;
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
        int level = mIsGsm ? getGsmRelatedSignalStrength() : getCdmaRelatedSignalStrength();
        if (DBG) log("getLevel=" + level);
        return level;
    }

    /**
     * Get the signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getAsuLevel() {
        int asuLevel = 0;
        if (mIsGsm) {
            if (mLteRsrp != SignalStrength.INVALID) {
                asuLevel = getLteAsuLevel();
            } else if (mTdScdmaRscp != SignalStrength.INVALID) {
                asuLevel = getTdScdmaAsuLevel();
            } else if (mWcdmaRscp != SignalStrength.INVALID) {
                asuLevel = getWcdmaAsuLevel();
            } else {
                asuLevel = getGsmAsuLevel();
            }
        } else {
            int cdmaAsuLevel = getCdmaAsuLevel();
            int evdoAsuLevel = getEvdoAsuLevel();
            if (evdoAsuLevel == 0) {
                /* We don't know evdo use, cdma */
                asuLevel = cdmaAsuLevel;
            } else if (cdmaAsuLevel == 0) {
                /* We don't know cdma use, evdo */
                asuLevel = evdoAsuLevel;
            } else {
                /* We know both, use the lowest level */
                asuLevel = cdmaAsuLevel < evdoAsuLevel ? cdmaAsuLevel : evdoAsuLevel;
            }
        }
        if (DBG) log("getAsuLevel=" + asuLevel);
        return asuLevel;
    }

    /**
     * Get the signal strength as dBm
     *
     * @hide
     */
    public int getDbm() {
        int dBm = INVALID;

        if(isGsm()) {
            dBm = getLteDbm();
            if (dBm == INVALID) {
                if (getTdScdmaLevel() == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                    if (getWcdmaDbm() == INVALID) {
                        dBm = getGsmDbm();
                    } else {
                        dBm = getWcdmaDbm();
                    }
                } else {
                    dBm = getTdScdmaDbm();
                }
            }
        } else {
            int cdmaDbm = getCdmaDbm();
            int evdoDbm = getEvdoDbm();

            return (evdoDbm == -120) ? cdmaDbm : ((cdmaDbm == -120) ? evdoDbm
                    : (cdmaDbm < evdoDbm ? cdmaDbm : evdoDbm));
        }
        if (DBG) log("getDbm=" + dBm);
        return dBm;
    }

    /**
     * Get Gsm signal strength as dBm
     *
     * @hide
     */
    public int getGsmDbm() {
        int dBm;

        int gsmSignalStrength = getGsmSignalStrength();
        int asu = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
        if (asu != -1) {
            dBm = -113 + (2 * asu);
        } else {
            dBm = -1;
        }
        if (DBG) log("getGsmDbm=" + dBm);
        return dBm;
    }

    /**
     * Get gsm as level 0..4
     *
     * @hide
     */
    public int getGsmLevel() {
        int level;

        // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
        // asu = 0 (-113dB or less) is very weak
        // signal, its better to show 0 bars to the user in such cases.
        // asu = 99 is a special case, where the signal strength is unknown.
        int asu = getGsmSignalStrength();
        if (asu <= 2 || asu == 99) level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (asu >= 12) level = SIGNAL_STRENGTH_GREAT;
        else if (asu >= 8)  level = SIGNAL_STRENGTH_GOOD;
        else if (asu >= 5)  level = SIGNAL_STRENGTH_MODERATE;
        else level = SIGNAL_STRENGTH_POOR;
        if (DBG) log("getGsmLevel=" + level);
        return level;
    }

    /**
     * Get the gsm signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getGsmAsuLevel() {
        // ASU ranges from 0 to 31 - TS 27.007 Sec 8.5
        // asu = 0 (-113dB or less) is very weak
        // signal, its better to show 0 bars to the user in such cases.
        // asu = 99 is a special case, where the signal strength is unknown.
        int level = getGsmSignalStrength();
        if (DBG) log("getGsmAsuLevel=" + level);
        return level;
    }

    /**
     * Get cdma as level 0..4
     *
     * @hide
     */
    public int getCdmaLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int levelDbm;
        int levelEcio;

        if (cdmaDbm >= -75) levelDbm = SIGNAL_STRENGTH_GREAT;
        else if (cdmaDbm >= -85) levelDbm = SIGNAL_STRENGTH_GOOD;
        else if (cdmaDbm >= -95) levelDbm = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaDbm >= -100) levelDbm = SIGNAL_STRENGTH_POOR;
        else levelDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) levelEcio = SIGNAL_STRENGTH_GREAT;
        else if (cdmaEcio >= -110) levelEcio = SIGNAL_STRENGTH_GOOD;
        else if (cdmaEcio >= -130) levelEcio = SIGNAL_STRENGTH_MODERATE;
        else if (cdmaEcio >= -150) levelEcio = SIGNAL_STRENGTH_POOR;
        else levelEcio = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelDbm < levelEcio) ? levelDbm : levelEcio;
        if (DBG) log("getCdmaLevel=" + level);
        return level;
    }

    /**
     * Get the cdma signal level as an asu value between 0..31, 99 is unknown
     *
     * @hide
     */
    public int getCdmaAsuLevel() {
        final int cdmaDbm = getCdmaDbm();
        final int cdmaEcio = getCdmaEcio();
        int cdmaAsuLevel;
        int ecioAsuLevel;

        if (cdmaDbm >= -75) cdmaAsuLevel = 16;
        else if (cdmaDbm >= -82) cdmaAsuLevel = 8;
        else if (cdmaDbm >= -90) cdmaAsuLevel = 4;
        else if (cdmaDbm >= -95) cdmaAsuLevel = 2;
        else if (cdmaDbm >= -100) cdmaAsuLevel = 1;
        else cdmaAsuLevel = 99;

        // Ec/Io are in dB*10
        if (cdmaEcio >= -90) ecioAsuLevel = 16;
        else if (cdmaEcio >= -100) ecioAsuLevel = 8;
        else if (cdmaEcio >= -115) ecioAsuLevel = 4;
        else if (cdmaEcio >= -130) ecioAsuLevel = 2;
        else if (cdmaEcio >= -150) ecioAsuLevel = 1;
        else ecioAsuLevel = 99;

        int level = (cdmaAsuLevel < ecioAsuLevel) ? cdmaAsuLevel : ecioAsuLevel;
        if (DBG) log("getCdmaAsuLevel=" + level);
        return level;
    }

    /**
     * Get Evdo as level 0..4
     *
     * @hide
     */
    public int getEvdoLevel() {
        int evdoDbm = getEvdoDbm();
        int evdoSnr = getEvdoSnr();
        int levelEvdoDbm;
        int levelEvdoSnr;

        if (evdoDbm >= -65) levelEvdoDbm = SIGNAL_STRENGTH_GREAT;
        else if (evdoDbm >= -75) levelEvdoDbm = SIGNAL_STRENGTH_GOOD;
        else if (evdoDbm >= -90) levelEvdoDbm = SIGNAL_STRENGTH_MODERATE;
        else if (evdoDbm >= -105) levelEvdoDbm = SIGNAL_STRENGTH_POOR;
        else levelEvdoDbm = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (evdoSnr >= 7) levelEvdoSnr = SIGNAL_STRENGTH_GREAT;
        else if (evdoSnr >= 5) levelEvdoSnr = SIGNAL_STRENGTH_GOOD;
        else if (evdoSnr >= 3) levelEvdoSnr = SIGNAL_STRENGTH_MODERATE;
        else if (evdoSnr >= 1) levelEvdoSnr = SIGNAL_STRENGTH_POOR;
        else levelEvdoSnr = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        int level = (levelEvdoDbm < levelEvdoSnr) ? levelEvdoDbm : levelEvdoSnr;
        if (DBG) log("getEvdoLevel=" + level);
        return level;
    }

    /**
     * Get the evdo signal level as an asu value between 0..31, 99 is unknown
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
     * Get LTE as dBm
     *
     * @hide
     */
    public int getLteDbm() {
        return mLteRsrp;
    }

    /**
     * Get LTE as level 0..4
     *
     * @hide
     */
    public int getLteLevel() {
        /*
         * TS 36.214 Physical Layer Section 5.1.3
         * TS 36.331 RRC
         *
         * RSSI = received signal + noise
         * RSRP = reference signal dBm
         * RSRQ = quality of signal dB = Number of Resource blocks*RSRP/RSSI
         * SNR = gain = signal/noise ratio = -10log P1/P2 dB
         */
        int rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN, rsrpIconLevel = -1, snrIconLevel = -1;

        if (mLteRsrp > MAX_LTE_RSRP || mLteRsrp < MIN_LTE_RSRP) {
            if (mLteRsrp != INVALID) {
                Log.wtf(LOG_TAG, "getLteLevel - invalid lte rsrp: mLteRsrp=" + mLteRsrp);
            }
        } else if (mLteRsrp >= (mLteRsrpThresholds[3] - mLteRsrpBoost)) {
            rsrpIconLevel = SIGNAL_STRENGTH_GREAT;
        } else if (mLteRsrp >= (mLteRsrpThresholds[2] - mLteRsrpBoost)) {
            rsrpIconLevel = SIGNAL_STRENGTH_GOOD;
        } else if (mLteRsrp >= (mLteRsrpThresholds[1] - mLteRsrpBoost)) {
            rsrpIconLevel = SIGNAL_STRENGTH_MODERATE;
        } else if (mLteRsrp >= (mLteRsrpThresholds[0] - mLteRsrpBoost)) {
            rsrpIconLevel = SIGNAL_STRENGTH_POOR;
        } else {
            rsrpIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }

        if (useOnlyRsrpForLteLevel()) {
            log("getLTELevel - rsrp = " + rsrpIconLevel);
            if (rsrpIconLevel != -1) {
                return rsrpIconLevel;
            }
        }

        /*
         * Values are -200 dB to +300 (SNR*10dB) RS_SNR >= 13.0 dB =>4 bars 4.5
         * dB <= RS_SNR < 13.0 dB => 3 bars 1.0 dB <= RS_SNR < 4.5 dB => 2 bars
         * -3.0 dB <= RS_SNR < 1.0 dB 1 bar RS_SNR < -3.0 dB/No Service Antenna
         * Icon Only
         */
        if (mLteRssnr > 300) snrIconLevel = -1;
        else if (mLteRssnr >= 130) snrIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mLteRssnr >= 45) snrIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mLteRssnr >= 10) snrIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mLteRssnr >= -30) snrIconLevel = SIGNAL_STRENGTH_POOR;
        else if (mLteRssnr >= -200)
            snrIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (DBG) log("getLTELevel - rsrp:" + mLteRsrp + " snr:" + mLteRssnr + " rsrpIconLevel:"
                + rsrpIconLevel + " snrIconLevel:" + snrIconLevel
                + " lteRsrpBoost:" + mLteRsrpBoost);

        /* Choose a measurement type to use for notification */
        if (snrIconLevel != -1 && rsrpIconLevel != -1) {
            /*
             * The number of bars displayed shall be the smaller of the bars
             * associated with LTE RSRP and the bars associated with the LTE
             * RS_SNR
             */
            return (rsrpIconLevel < snrIconLevel ? rsrpIconLevel : snrIconLevel);
        }

        if (snrIconLevel != -1) return snrIconLevel;

        if (rsrpIconLevel != -1) return rsrpIconLevel;

        /* Valid values are (0-63, 99) as defined in TS 36.331 */
        // TODO the range here is probably supposed to be (0..31, 99). It's unclear if anyone relies
        // on the current incorrect range check, so this will be fixed in a future release with more
        // soak time
        if (mLteSignalStrength > 63) rssiIconLevel = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (mLteSignalStrength >= 12) rssiIconLevel = SIGNAL_STRENGTH_GREAT;
        else if (mLteSignalStrength >= 8) rssiIconLevel = SIGNAL_STRENGTH_GOOD;
        else if (mLteSignalStrength >= 5) rssiIconLevel = SIGNAL_STRENGTH_MODERATE;
        else if (mLteSignalStrength >= 0) rssiIconLevel = SIGNAL_STRENGTH_POOR;

        if (DBG) log("getLteLevel - rssi:" + mLteSignalStrength + " rssiIconLevel:"
                + rssiIconLevel);
        return rssiIconLevel;

    }

    /**
     * Get the LTE signal level as an asu value between 0..97, 99 is unknown
     * Asu is calculated based on 3GPP RSRP. Refer to 3GPP 27.007 (Ver 10.3.0) Sec 8.69
     *
     * @hide
     */
    public int getLteAsuLevel() {
        int lteAsuLevel = 99;
        int lteDbm = getLteDbm();
        /*
         * 3GPP 27.007 (Ver 10.3.0) Sec 8.69
         * 0   -140 dBm or less
         * 1   -139 dBm
         * 2...96  -138... -44 dBm
         * 97  -43 dBm or greater
         * 255 not known or not detectable
         */
        /*
         * validateInput will always give a valid range between -140 t0 -44 as
         * per ril.h. so RSRP >= -43 & <-140 will fall under asu level 255
         * and not 97 or 0
         */
        if (lteDbm == SignalStrength.INVALID) lteAsuLevel = 255;
        else lteAsuLevel = lteDbm + 140;
        if (DBG) log("Lte Asu level: "+lteAsuLevel);
        return lteAsuLevel;
    }

    /**
     * @return true if this is for GSM
     */
    public boolean isGsm() {
        return this.mIsGsm;
    }

    /**
     * @return true if it uses only RSRP for the number of LTE signal bar, otherwise false.
     *
     * @hide
     */
    public boolean useOnlyRsrpForLteLevel() {
        return this.mUseOnlyRsrpForLteLevel;
    }

    /**
     * @return get TD_SCDMA dbm
     *
     * @hide
     */
    public int getTdScdmaDbm() {
        return this.mTdScdmaRscp;
    }

    /**
     * Get TD-SCDMA as level 0..4
     * Range : 25 to 120
     * INT_MAX: 0x7FFFFFFF denotes invalid value
     * Reference: 3GPP TS 25.123, section 9.1.1.1
     *
     * @hide
     */
    public int getTdScdmaLevel() {
        final int tdScdmaDbm = getTdScdmaDbm();
        int level;

        if ((tdScdmaDbm > -25) || (tdScdmaDbm == SignalStrength.INVALID))
                level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (tdScdmaDbm >= -49) level = SIGNAL_STRENGTH_GREAT;
        else if (tdScdmaDbm >= -73) level = SIGNAL_STRENGTH_GOOD;
        else if (tdScdmaDbm >= -97) level = SIGNAL_STRENGTH_MODERATE;
        else if (tdScdmaDbm >= -110) level = SIGNAL_STRENGTH_POOR;
        else level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (DBG) log("getTdScdmaLevel = " + level);
        return level;
     }

    /**
     * Get the TD-SCDMA signal level as an asu value.
     *
     * @hide
     */
    public int getTdScdmaAsuLevel() {
        final int tdScdmaDbm = getTdScdmaDbm();
        int tdScdmaAsuLevel;

        if (tdScdmaDbm == INVALID) tdScdmaAsuLevel = 255;
        else tdScdmaAsuLevel = tdScdmaDbm + 120;
        if (DBG) log("TD-SCDMA Asu level: " + tdScdmaAsuLevel);
        return tdScdmaAsuLevel;
    }

    /**
     * Gets WCDMA RSCP as a dbm value between -120 and -24, as defined in TS 27.007 8.69.
     *
     * @hide
     */
    public int getWcdmaRscp() {
        return mWcdmaRscp;
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
        final int wcdmaDbm = getWcdmaDbm();
        int wcdmaAsuLevel = 255;
        // validateInput will always give a valid range between -120 to -24 as per ril.h. so RSCP
        // outside range is already set to INVALID
        if (wcdmaDbm == SignalStrength.INVALID) wcdmaAsuLevel =  255;
        else wcdmaAsuLevel = wcdmaDbm + 120;
        if (DBG) log("Wcdma Asu level: " + wcdmaAsuLevel);
        return wcdmaAsuLevel;
    }

    /**
     * Gets WCDMA signal strength as a dbm value between -120 and -24, as defined in TS 27.007 8.69.
     *
     * @hide
     */
    public int getWcdmaDbm() {
        return mWcdmaRscp;
    }

    /**
     * Get WCDMA as level 0..4
     *
     * @hide
     */
    public int getWcdmaLevel() {
        int level = SIGNAL_STRENGTH_NONE_OR_UNKNOWN;

        if (mWcdmaDefaultSignalMeasurement == null) {
            Log.wtf(LOG_TAG, "getWcdmaLevel - WCDMA default signal measurement is invalid.");
            return level;
        }

        switch (mWcdmaDefaultSignalMeasurement) {
            case MEASUMENT_TYPE_RSCP:
                // RSCP valid values are (-120 through -24) as defined in TS 27.007 8.69
                if (mWcdmaRscp < MIN_WCDMA_RSCP || mWcdmaRscp > MAX_WCDMA_RSCP) {
                    if (mWcdmaRscp != INVALID) {
                        Log.wtf(LOG_TAG, "getWcdmaLevel - invalid WCDMA RSCP: mWcdmaRscp="
                                + mWcdmaRscp);
                    }
                } else if (mWcdmaRscp >= mWcdmaRscpThresholds[3]) {
                    level = SIGNAL_STRENGTH_GREAT;
                } else if (mWcdmaRscp >= mWcdmaRscpThresholds[2]) {
                    level = SIGNAL_STRENGTH_GOOD;
                } else if (mWcdmaRscp >= mWcdmaRscpThresholds[1]) {
                    level = SIGNAL_STRENGTH_MODERATE;
                } else if (mWcdmaRscp >= mWcdmaRscpThresholds[0]) {
                    level = SIGNAL_STRENGTH_POOR;
                }
                if (DBG) log("getWcdmaLevel=" + level + " WcdmaRscp=" + mWcdmaRscp);
                break;

            default:
                // RSSI valid values are (0..31) as defined in TS 27.007 8.5
                if (mWcdmaSignalStrength < 0 || mWcdmaSignalStrength > 31) {
                    if (mWcdmaSignalStrength != 99) {
                        Log.wtf(LOG_TAG, "getWcdmaLevel - invalid WCDMA RSSI: mWcdmaSignalStrength="
                                + mWcdmaSignalStrength);
                    }
                } else if (mWcdmaSignalStrength >= 18) {
                    level = SIGNAL_STRENGTH_GREAT;
                } else if (mWcdmaSignalStrength >= 13) {
                    level = SIGNAL_STRENGTH_GOOD;
                } else if (mWcdmaSignalStrength >= 8) {
                    level = SIGNAL_STRENGTH_MODERATE;
                } else if (mWcdmaSignalStrength >= 3) {
                    level = SIGNAL_STRENGTH_POOR;
                }
                if (DBG) log("getWcdmaLevel=" + level + " WcdmaSignalStrength=" +
                        mWcdmaSignalStrength);
                break;

        }
        return level;
    }

   /**
     * @return hash code
     */
    @Override
    public int hashCode() {
        int primeNum = 31;
        return ((mGsmSignalStrength * primeNum)
                + (mGsmBitErrorRate * primeNum)
                + (mCdmaDbm * primeNum) + (mCdmaEcio * primeNum)
                + (mEvdoDbm * primeNum) + (mEvdoEcio * primeNum) + (mEvdoSnr * primeNum)
                + (mLteSignalStrength * primeNum) + (mLteRsrp * primeNum)
                + (mLteRsrq * primeNum) + (mLteRssnr * primeNum) + (mLteCqi * primeNum)
                + (mLteRsrpBoost * primeNum) + (mTdScdmaRscp * primeNum)
                + (mWcdmaSignalStrength * primeNum) + (mWcdmaRscpAsu * primeNum)
                + (mWcdmaRscp * primeNum) + (mIsGsm ? 1 : 0) + (mUseOnlyRsrpForLteLevel ? 1 : 0)
                + (Objects.hashCode(mWcdmaDefaultSignalMeasurement))
                + (Arrays.hashCode(mLteRsrpThresholds)) + (Arrays.hashCode(mWcdmaRscpThresholds)));
    }

    /**
     * @return true if the signal strengths are the same
     */
    @Override
    public boolean equals (Object o) {
        SignalStrength s;

        try {
            s = (SignalStrength) o;
        } catch (ClassCastException ex) {
            return false;
        }

        if (o == null) {
            return false;
        }

        return (mGsmSignalStrength == s.mGsmSignalStrength
                && mGsmBitErrorRate == s.mGsmBitErrorRate
                && mCdmaDbm == s.mCdmaDbm
                && mCdmaEcio == s.mCdmaEcio
                && mEvdoDbm == s.mEvdoDbm
                && mEvdoEcio == s.mEvdoEcio
                && mEvdoSnr == s.mEvdoSnr
                && mLteSignalStrength == s.mLteSignalStrength
                && mLteRsrp == s.mLteRsrp
                && mLteRsrq == s.mLteRsrq
                && mLteRssnr == s.mLteRssnr
                && mLteCqi == s.mLteCqi
                && mLteRsrpBoost == s.mLteRsrpBoost
                && mTdScdmaRscp == s.mTdScdmaRscp
                && mWcdmaSignalStrength == s.mWcdmaSignalStrength
                && mWcdmaRscpAsu == s.mWcdmaRscpAsu
                && mWcdmaRscp == s.mWcdmaRscp
                && mIsGsm == s.mIsGsm
                && mUseOnlyRsrpForLteLevel == s.mUseOnlyRsrpForLteLevel
                && Objects.equals(mWcdmaDefaultSignalMeasurement, s.mWcdmaDefaultSignalMeasurement)
                && Arrays.equals(mLteRsrpThresholds, s.mLteRsrpThresholds)
                && Arrays.equals(mWcdmaRscpThresholds, s.mWcdmaRscpThresholds));
    }

    /**
     * @return string representation.
     */
    @Override
    public String toString() {
        return ("SignalStrength:"
                + " " + mGsmSignalStrength
                + " " + mGsmBitErrorRate
                + " " + mCdmaDbm
                + " " + mCdmaEcio
                + " " + mEvdoDbm
                + " " + mEvdoEcio
                + " " + mEvdoSnr
                + " " + mLteSignalStrength
                + " " + mLteRsrp
                + " " + mLteRsrq
                + " " + mLteRssnr
                + " " + mLteCqi
                + " " + mLteRsrpBoost
                + " " + mTdScdmaRscp
                + " " + mWcdmaSignalStrength
                + " " + mWcdmaRscpAsu
                + " " + mWcdmaRscp
                + " " + (mIsGsm ? "gsm|lte" : "cdma")
                + " " + (mUseOnlyRsrpForLteLevel ? "use_only_rsrp_for_lte_level" :
                         "use_rsrp_and_rssnr_for_lte_level")
                + " " + mWcdmaDefaultSignalMeasurement
                + " " + (Arrays.toString(mLteRsrpThresholds))
                + " " + (Arrays.toString(mWcdmaRscpThresholds)));
    }

    /** Returns the signal strength related to GSM. */
    private int getGsmRelatedSignalStrength() {
        int level = getLteLevel();
        if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            level = getTdScdmaLevel();
            if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                level = getWcdmaLevel();
                if (level == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
                    level = getGsmLevel();
                }
            }
        }
        return level;
    }

    /** Returns the signal strength related to CDMA. */
    private int getCdmaRelatedSignalStrength() {
        int level;
        int cdmaLevel = getCdmaLevel();
        int evdoLevel = getEvdoLevel();
        if (evdoLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know evdo, use cdma */
            level = cdmaLevel;
        } else if (cdmaLevel == SIGNAL_STRENGTH_NONE_OR_UNKNOWN) {
            /* We don't know cdma, use evdo */
            level = evdoLevel;
        } else {
            /* We know both, use the lowest level */
            level = cdmaLevel < evdoLevel ? cdmaLevel : evdoLevel;
        }
        return level;
    }

    /**
     * Set SignalStrength based on intent notifier map
     *
     * @param m intent notifier map
     * @hide
     */
    private void setFromNotifierBundle(Bundle m) {
        mGsmSignalStrength = m.getInt("GsmSignalStrength");
        mGsmBitErrorRate = m.getInt("GsmBitErrorRate");
        mCdmaDbm = m.getInt("CdmaDbm");
        mCdmaEcio = m.getInt("CdmaEcio");
        mEvdoDbm = m.getInt("EvdoDbm");
        mEvdoEcio = m.getInt("EvdoEcio");
        mEvdoSnr = m.getInt("EvdoSnr");
        mLteSignalStrength = m.getInt("LteSignalStrength");
        mLteRsrp = m.getInt("LteRsrp");
        mLteRsrq = m.getInt("LteRsrq");
        mLteRssnr = m.getInt("LteRssnr");
        mLteCqi = m.getInt("LteCqi");
        mLteRsrpBoost = m.getInt("LteRsrpBoost");
        mTdScdmaRscp = m.getInt("TdScdma");
        mWcdmaSignalStrength = m.getInt("WcdmaSignalStrength");
        mWcdmaRscpAsu = m.getInt("WcdmaRscpAsu");
        mWcdmaRscp = m.getInt("WcdmaRscp");
        mIsGsm = m.getBoolean("IsGsm");
        mUseOnlyRsrpForLteLevel = m.getBoolean("UseOnlyRsrpForLteLevel");
        mWcdmaDefaultSignalMeasurement = m.getString("WcdmaDefaultSignalMeasurement");
        ArrayList<Integer> lteRsrpThresholds = m.getIntegerArrayList("lteRsrpThresholds");
        for (int i = 0; i < lteRsrpThresholds.size(); i++) {
            mLteRsrpThresholds[i] = lteRsrpThresholds.get(i);
        }
        ArrayList<Integer> wcdmaRscpThresholds = m.getIntegerArrayList("wcdmaRscpThresholds");
        for (int i = 0; i < wcdmaRscpThresholds.size(); i++) {
            mWcdmaRscpThresholds[i] = wcdmaRscpThresholds.get(i);
        }
    }

    /**
     * Set intent notifier Bundle based on SignalStrength
     *
     * @param m intent notifier Bundle
     * @hide
     */
    public void fillInNotifierBundle(Bundle m) {
        m.putInt("GsmSignalStrength", mGsmSignalStrength);
        m.putInt("GsmBitErrorRate", mGsmBitErrorRate);
        m.putInt("CdmaDbm", mCdmaDbm);
        m.putInt("CdmaEcio", mCdmaEcio);
        m.putInt("EvdoDbm", mEvdoDbm);
        m.putInt("EvdoEcio", mEvdoEcio);
        m.putInt("EvdoSnr", mEvdoSnr);
        m.putInt("LteSignalStrength", mLteSignalStrength);
        m.putInt("LteRsrp", mLteRsrp);
        m.putInt("LteRsrq", mLteRsrq);
        m.putInt("LteRssnr", mLteRssnr);
        m.putInt("LteCqi", mLteCqi);
        m.putInt("LteRsrpBoost", mLteRsrpBoost);
        m.putInt("TdScdma", mTdScdmaRscp);
        m.putInt("WcdmaSignalStrength", mWcdmaSignalStrength);
        m.putInt("WcdmaRscpAsu", mWcdmaRscpAsu);
        m.putInt("WcdmaRscp", mWcdmaRscp);
        m.putBoolean("IsGsm", mIsGsm);
        m.putBoolean("UseOnlyRsrpForLteLevel", mUseOnlyRsrpForLteLevel);
        m.putString("WcdmaDefaultSignalMeasurement", mWcdmaDefaultSignalMeasurement);
        ArrayList<Integer> lteRsrpThresholds = new ArrayList<Integer>();
        for (int value : mLteRsrpThresholds) {
            lteRsrpThresholds.add(value);
        }
        m.putIntegerArrayList("lteRsrpThresholds", lteRsrpThresholds);
        ArrayList<Integer> wcdmaRscpThresholds = new ArrayList<Integer>();
        for (int value : mWcdmaRscpThresholds) {
            wcdmaRscpThresholds.add(value);
        }
        m.putIntegerArrayList("wcdmaRscpThresholds", wcdmaRscpThresholds);
    }

    /**
     * Gets the default threshold array for determining the display level of LTE signal bar.
     *
     * @return int array for determining the display level.
     */
    private int[] getDefaultLteRsrpThresholds() {
        return CarrierConfigManager.getDefaultConfig().getIntArray(
                CarrierConfigManager.KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY);
    }

    /**
     * Gets the default threshold array for determining the display level of WCDMA signal bar.
     *
     * @return int array for determining the display level.
     */
    private int[] getDefaultWcdmaRscpThresholds() {
        return CarrierConfigManager.getDefaultConfig().getIntArray(
                CarrierConfigManager.KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY);
    }

    /**
     * log
     */
    private static void log(String s) {
        Rlog.w(LOG_TAG, s);
    }
}
