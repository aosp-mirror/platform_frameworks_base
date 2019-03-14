/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.NetworkRegistrationState.Domain;
import android.telephony.NetworkRegistrationState.NRStatus;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Contains phone state and service related information.
 *
 * The following phone information is included in returned ServiceState:
 *
 * <ul>
 *   <li>Service state: IN_SERVICE, OUT_OF_SERVICE, EMERGENCY_ONLY, POWER_OFF
 *   <li>Duplex mode: UNKNOWN, FDD, TDD
 *   <li>Roaming indicator
 *   <li>Operator name, short name and numeric id
 *   <li>Network selection mode
 * </ul>
 */
public class ServiceState implements Parcelable {

    static final String LOG_TAG = "PHONE";
    static final boolean DBG = false;
    static final boolean VDBG = false;  // STOPSHIP if true

    /**
     * Normal operation condition, the phone is registered
     * with an operator either in home network or in roaming.
     */
    public static final int STATE_IN_SERVICE = TelephonyProtoEnums.SERVICE_STATE_IN_SERVICE; // 0

    /**
     * Phone is not registered with any operator, the phone
     * can be currently searching a new operator to register to, or not
     * searching to registration at all, or registration is denied, or radio
     * signal is not available.
     */
    public static final int STATE_OUT_OF_SERVICE =
            TelephonyProtoEnums.SERVICE_STATE_OUT_OF_SERVICE;  // 1

    /**
     * The phone is registered and locked.  Only emergency numbers are allowed. {@more}
     */
    public static final int STATE_EMERGENCY_ONLY =
            TelephonyProtoEnums.SERVICE_STATE_EMERGENCY_ONLY;  // 2

    /**
     * Radio of telephony is explicitly powered off.
     */
    public static final int STATE_POWER_OFF = TelephonyProtoEnums.SERVICE_STATE_POWER_OFF;  // 3

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "FREQUENCY_RANGE_",
            value = {FREQUENCY_RANGE_UNKNOWN, FREQUENCY_RANGE_LOW, FREQUENCY_RANGE_MID,
                    FREQUENCY_RANGE_HIGH, FREQUENCY_RANGE_MMWAVE})
    public @interface FrequencyRange {}

    /**
     * Indicates frequency range is unknown.
     * @hide
     */
    public static final int FREQUENCY_RANGE_UNKNOWN = -1;

    /**
     * Indicates the frequency range is below 1GHz.
     * @hide
     */
    public static final int FREQUENCY_RANGE_LOW = 1;

    /**
     * Indicates the frequency range is between 1GHz to 3GHz.
     * @hide
     */
    public static final int FREQUENCY_RANGE_MID = 2;

    /**
     * Indicates the frequency range is between 3GHz and 6GHz.
     * @hide
     */
    public static final int FREQUENCY_RANGE_HIGH = 3;

    /**
     * Indicates the frequency range is above 6GHz (millimeter wave frequency).
     * @hide
     */
    public static final int FREQUENCY_RANGE_MMWAVE = 4;

    private static final List<Integer> FREQUENCY_RANGE_ORDER = Arrays.asList(
            FREQUENCY_RANGE_UNKNOWN,
            FREQUENCY_RANGE_LOW,
            FREQUENCY_RANGE_MID,
            FREQUENCY_RANGE_HIGH,
            FREQUENCY_RANGE_MMWAVE);

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DUPLEX_MODE_",
            value = {DUPLEX_MODE_UNKNOWN, DUPLEX_MODE_FDD, DUPLEX_MODE_TDD})
    public @interface DuplexMode {}

    /**
     * Duplex mode for the phone is unknown.
     */
    public static final int DUPLEX_MODE_UNKNOWN = 0;

    /**
     * Duplex mode for the phone is frequency-division duplexing.
     */
    public static final int DUPLEX_MODE_FDD = 1;

    /**
     * Duplex mode for the phone is time-division duplexing.
     */
    public static final int DUPLEX_MODE_TDD = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "RIL_RADIO_TECHNOLOGY_" },
            value = {
                    RIL_RADIO_TECHNOLOGY_UNKNOWN,
                    RIL_RADIO_TECHNOLOGY_GPRS,
                    RIL_RADIO_TECHNOLOGY_EDGE,
                    RIL_RADIO_TECHNOLOGY_UMTS,
                    RIL_RADIO_TECHNOLOGY_IS95A,
                    RIL_RADIO_TECHNOLOGY_IS95B,
                    RIL_RADIO_TECHNOLOGY_1xRTT,
                    RIL_RADIO_TECHNOLOGY_EVDO_0,
                    RIL_RADIO_TECHNOLOGY_EVDO_A,
                    RIL_RADIO_TECHNOLOGY_HSDPA,
                    RIL_RADIO_TECHNOLOGY_HSUPA,
                    RIL_RADIO_TECHNOLOGY_HSPA,
                    RIL_RADIO_TECHNOLOGY_EVDO_B,
                    RIL_RADIO_TECHNOLOGY_EHRPD,
                    RIL_RADIO_TECHNOLOGY_LTE,
                    RIL_RADIO_TECHNOLOGY_HSPAP,
                    RIL_RADIO_TECHNOLOGY_GSM,
                    RIL_RADIO_TECHNOLOGY_TD_SCDMA,
                    RIL_RADIO_TECHNOLOGY_IWLAN,
                    RIL_RADIO_TECHNOLOGY_LTE_CA,
                    RIL_RADIO_TECHNOLOGY_NR})
    public @interface RilRadioTechnology {}
    /**
     * Available radio technologies for GSM, UMTS and CDMA.
     * Duplicates the constants from hardware/radio/include/ril.h
     * This should only be used by agents working with the ril.  Others
     * should use the equivalent TelephonyManager.NETWORK_TYPE_*
     */
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_UNKNOWN = 0;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_GPRS = 1;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_EDGE = 2;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_UMTS = 3;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_IS95A = 4;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_IS95B = 5;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_1xRTT = 6;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_0 = 7;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_A = 8;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_HSDPA = 9;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_HSUPA = 10;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_HSPA = 11;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_B = 12;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_EHRPD = 13;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_LTE = 14;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_HSPAP = 15;
    /**
     * GSM radio technology only supports voice. It does not support data.
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_GSM = 16;
    /** @hide */
    public static final int RIL_RADIO_TECHNOLOGY_TD_SCDMA = 17;
    /**
     * IWLAN
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final int RIL_RADIO_TECHNOLOGY_IWLAN = 18;

    /**
     * LTE_CA
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_LTE_CA = 19;

    /**
     * NR(New Radio) 5G.
     * @hide
     */
    public static final int  RIL_RADIO_TECHNOLOGY_NR = 20;

    /**
     * The number of the radio technologies.
     */
    private static final int NEXT_RIL_RADIO_TECHNOLOGY = 21;

    /** @hide */
    public static final int RIL_RADIO_CDMA_TECHNOLOGY_BITMASK =
            (1 << (RIL_RADIO_TECHNOLOGY_IS95A - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_IS95B - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_1xRTT - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_EVDO_0 - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_EVDO_A - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_EVDO_B - 1))
                    | (1 << (RIL_RADIO_TECHNOLOGY_EHRPD - 1));

    private int mVoiceRegState = STATE_OUT_OF_SERVICE;
    private int mDataRegState = STATE_OUT_OF_SERVICE;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "ROAMING_TYPE_" }, value = {
            ROAMING_TYPE_NOT_ROAMING,
            ROAMING_TYPE_UNKNOWN,
            ROAMING_TYPE_DOMESTIC,
            ROAMING_TYPE_INTERNATIONAL
    })
    public @interface RoamingType {}

    /**
     * Not roaming, registered in home network.
     * @hide
     */
    @SystemApi
    public static final int ROAMING_TYPE_NOT_ROAMING = 0;
    /**
     * registered in a roaming network, but can not tell if it's domestic or international.
     * @hide
     */
    @SystemApi
    public static final int ROAMING_TYPE_UNKNOWN = 1;
    /**
     * registered in a domestic roaming network
     * @hide
     */
    @SystemApi
    public static final int ROAMING_TYPE_DOMESTIC = 2;
    /**
     * registered in an international roaming network
     * @hide
     */
    @SystemApi
    public static final int ROAMING_TYPE_INTERNATIONAL = 3;

    /**
     * Unknown ID. Could be returned by {@link #getCdmaNetworkId()} or {@link #getCdmaSystemId()}
     */
    public static final int UNKNOWN_ID = -1;

    private String mVoiceOperatorAlphaLong;
    private String mVoiceOperatorAlphaShort;
    private String mVoiceOperatorNumeric;
    private String mDataOperatorAlphaLong;
    private String mDataOperatorAlphaShort;
    private String mDataOperatorNumeric;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private boolean mIsManualNetworkSelection;

    private boolean mIsEmergencyOnly;
    /**
     * TODO: remove mRilVoiceRadioTechnology after completely migrate to
     * {@link TelephonyManager.NetworkType}
     */
    @RilRadioTechnology
    private int mRilVoiceRadioTechnology;
    /**
     * TODO: remove mRilDataRadioTechnology after completely migrate to
     * {@link TelephonyManager.NetworkType}
     */
    @RilRadioTechnology
    private int mRilDataRadioTechnology;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private boolean mCssIndicator;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mNetworkId;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mSystemId;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCdmaRoamingIndicator;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCdmaDefaultRoamingIndicator;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCdmaEriIconIndex;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mCdmaEriIconMode;

    @UnsupportedAppUsage
    private boolean mIsUsingCarrierAggregation;

    @FrequencyRange
    private int mNrFrequencyRange;
    private int mChannelNumber;
    private int[] mCellBandwidths = new int[0];

    /* EARFCN stands for E-UTRA Absolute Radio Frequency Channel Number,
     * Reference: 3GPP TS 36.104 5.4.3 */
    private int mLteEarfcnRsrpBoost = 0;

    private List<NetworkRegistrationState> mNetworkRegistrationStates = new ArrayList<>();

    /**
     * get String description of roaming type
     * @hide
     */
    public static final String getRoamingLogString(int roamingType) {
        switch (roamingType) {
            case ROAMING_TYPE_NOT_ROAMING:
                return "home";

            case ROAMING_TYPE_UNKNOWN:
                return "roaming";

            case ROAMING_TYPE_DOMESTIC:
                return "Domestic Roaming";

            case ROAMING_TYPE_INTERNATIONAL:
                return "International Roaming";

            default:
                return "UNKNOWN";
        }
    }

    /**
     * Create a new ServiceState from a intent notifier Bundle
     *
     * This method is used by PhoneStateIntentReceiver and maybe by
     * external applications.
     *
     * @param m Bundle from intent notifier
     * @return newly created ServiceState
     * @hide
     */
    @UnsupportedAppUsage
    public static ServiceState newFromBundle(Bundle m) {
        ServiceState ret;
        ret = new ServiceState();
        ret.setFromNotifierBundle(m);
        return ret;
    }

    /**
     * Empty constructor
     */
    public ServiceState() {
    }

    /**
     * Copy constructors
     *
     * @param s Source service state
     */
    public ServiceState(ServiceState s) {
        copyFrom(s);
    }

    protected void copyFrom(ServiceState s) {
        mVoiceRegState = s.mVoiceRegState;
        mDataRegState = s.mDataRegState;
        mVoiceOperatorAlphaLong = s.mVoiceOperatorAlphaLong;
        mVoiceOperatorAlphaShort = s.mVoiceOperatorAlphaShort;
        mVoiceOperatorNumeric = s.mVoiceOperatorNumeric;
        mDataOperatorAlphaLong = s.mDataOperatorAlphaLong;
        mDataOperatorAlphaShort = s.mDataOperatorAlphaShort;
        mDataOperatorNumeric = s.mDataOperatorNumeric;
        mIsManualNetworkSelection = s.mIsManualNetworkSelection;
        mRilVoiceRadioTechnology = s.mRilVoiceRadioTechnology;
        mRilDataRadioTechnology = s.mRilDataRadioTechnology;
        mCssIndicator = s.mCssIndicator;
        mNetworkId = s.mNetworkId;
        mSystemId = s.mSystemId;
        mCdmaRoamingIndicator = s.mCdmaRoamingIndicator;
        mCdmaDefaultRoamingIndicator = s.mCdmaDefaultRoamingIndicator;
        mCdmaEriIconIndex = s.mCdmaEriIconIndex;
        mCdmaEriIconMode = s.mCdmaEriIconMode;
        mIsEmergencyOnly = s.mIsEmergencyOnly;
        mIsUsingCarrierAggregation = s.mIsUsingCarrierAggregation;
        mChannelNumber = s.mChannelNumber;
        mCellBandwidths = s.mCellBandwidths == null ? null :
                Arrays.copyOf(s.mCellBandwidths, s.mCellBandwidths.length);
        mLteEarfcnRsrpBoost = s.mLteEarfcnRsrpBoost;
        mNetworkRegistrationStates = s.mNetworkRegistrationStates == null ? null :
                new ArrayList<>(s.mNetworkRegistrationStates);
        mNrFrequencyRange = s.mNrFrequencyRange;
    }

    /**
     * Construct a ServiceState object from the given parcel.
     *
     * @deprecated The constructor takes parcel should not be public at the beginning. Use
     * {@link #ServiceState()} instead.
     */
    @Deprecated
    public ServiceState(Parcel in) {
        mVoiceRegState = in.readInt();
        mDataRegState = in.readInt();
        mVoiceOperatorAlphaLong = in.readString();
        mVoiceOperatorAlphaShort = in.readString();
        mVoiceOperatorNumeric = in.readString();
        mDataOperatorAlphaLong = in.readString();
        mDataOperatorAlphaShort = in.readString();
        mDataOperatorNumeric = in.readString();
        mIsManualNetworkSelection = in.readInt() != 0;
        mRilVoiceRadioTechnology = in.readInt();
        mRilDataRadioTechnology = in.readInt();
        mCssIndicator = (in.readInt() != 0);
        mNetworkId = in.readInt();
        mSystemId = in.readInt();
        mCdmaRoamingIndicator = in.readInt();
        mCdmaDefaultRoamingIndicator = in.readInt();
        mCdmaEriIconIndex = in.readInt();
        mCdmaEriIconMode = in.readInt();
        mIsEmergencyOnly = in.readInt() != 0;
        mIsUsingCarrierAggregation = in.readInt() != 0;
        mLteEarfcnRsrpBoost = in.readInt();
        mNetworkRegistrationStates = new ArrayList<>();
        in.readList(mNetworkRegistrationStates, NetworkRegistrationState.class.getClassLoader());
        mChannelNumber = in.readInt();
        mCellBandwidths = in.createIntArray();
        mNrFrequencyRange = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mVoiceRegState);
        out.writeInt(mDataRegState);
        out.writeString(mVoiceOperatorAlphaLong);
        out.writeString(mVoiceOperatorAlphaShort);
        out.writeString(mVoiceOperatorNumeric);
        out.writeString(mDataOperatorAlphaLong);
        out.writeString(mDataOperatorAlphaShort);
        out.writeString(mDataOperatorNumeric);
        out.writeInt(mIsManualNetworkSelection ? 1 : 0);
        out.writeInt(mRilVoiceRadioTechnology);
        out.writeInt(mRilDataRadioTechnology);
        out.writeInt(mCssIndicator ? 1 : 0);
        out.writeInt(mNetworkId);
        out.writeInt(mSystemId);
        out.writeInt(mCdmaRoamingIndicator);
        out.writeInt(mCdmaDefaultRoamingIndicator);
        out.writeInt(mCdmaEriIconIndex);
        out.writeInt(mCdmaEriIconMode);
        out.writeInt(mIsEmergencyOnly ? 1 : 0);
        out.writeInt(mIsUsingCarrierAggregation ? 1 : 0);
        out.writeInt(mLteEarfcnRsrpBoost);
        out.writeList(mNetworkRegistrationStates);
        out.writeInt(mChannelNumber);
        out.writeIntArray(mCellBandwidths);
        out.writeInt(mNrFrequencyRange);
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ServiceState> CREATOR =
            new Parcelable.Creator<ServiceState>() {
        public ServiceState createFromParcel(Parcel in) {
            return new ServiceState(in);
        }

        public ServiceState[] newArray(int size) {
            return new ServiceState[size];
        }
    };

    /**
     * Get current voice service state
     */
    public int getState() {
        return getVoiceRegState();
    }

    /**
     * Get current voice service state
     *
     * @see #STATE_IN_SERVICE
     * @see #STATE_OUT_OF_SERVICE
     * @see #STATE_EMERGENCY_ONLY
     * @see #STATE_POWER_OFF
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public int getVoiceRegState() {
        return mVoiceRegState;
    }

    /**
     * Get current data service state
     *
     * @see #STATE_IN_SERVICE
     * @see #STATE_OUT_OF_SERVICE
     * @see #STATE_EMERGENCY_ONLY
     * @see #STATE_POWER_OFF
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int getDataRegState() {
        return mDataRegState;
    }

    /**
     * Get the current duplex mode
     *
     * @see #DUPLEX_MODE_UNKNOWN
     * @see #DUPLEX_MODE_FDD
     * @see #DUPLEX_MODE_TDD
     *
     * @return Current {@code DuplexMode} for the phone
     */
    @DuplexMode
    public int getDuplexMode() {
        // only support LTE duplex mode
        if (!isLte(mRilDataRadioTechnology)) {
            return DUPLEX_MODE_UNKNOWN;
        }

        int band = AccessNetworkUtils.getOperatingBandForEarfcn(mChannelNumber);
        return AccessNetworkUtils.getDuplexModeForEutranBand(band);
    }

    /**
     * Get the channel number of the current primary serving cell, or -1 if unknown
     *
     * <p>This is EARFCN for LTE, UARFCN for UMTS, and ARFCN for GSM.
     *
     * @return Channel number of primary serving cell
     */
    public int getChannelNumber() {
        return mChannelNumber;
    }

    /**
     * Get an array of cell bandwidths (kHz) for the current serving cells
     *
     * @return Current serving cell bandwidths
     */
    public int[] getCellBandwidths() {
        return mCellBandwidths == null ? new int[0] : mCellBandwidths;
    }

    /**
     * Get current roaming indicator of phone
     * (note: not just decoding from TS 27.007 7.2)
     *
     * @return true if TS 27.007 7.2 roaming is true
     *              and ONS is different from SPN
     */
    public boolean getRoaming() {
        return getVoiceRoaming() || getDataRoaming();
    }

    /**
     * Get current voice network roaming status
     * @return roaming status
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean getVoiceRoaming() {
        return getVoiceRoamingType() != ROAMING_TYPE_NOT_ROAMING;
    }
    /**
     * Get current voice network roaming type
     * @return roaming type
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public @RoamingType int getVoiceRoamingType() {
        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState != null) {
            return regState.getRoamingType();
        }
        return ROAMING_TYPE_NOT_ROAMING;
    }

    /**
     * Get current data network roaming type
     * @return roaming type
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean getDataRoaming() {
        return getDataRoamingType() != ROAMING_TYPE_NOT_ROAMING;
    }

    /**
     * Get whether data network registration state is roaming
     * @return true if registration indicates roaming, false otherwise
     * @hide
     */
    public boolean getDataRoamingFromRegistration() {
        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState != null) {
            return (regState.getRegState() == NetworkRegistrationState.REG_STATE_ROAMING);
        }
        return false;
    }

    /**
     * Get current data network roaming type
     * @return roaming type
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public @RoamingType int getDataRoamingType() {
        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState != null) {
            return regState.getRoamingType();
        }
        return ROAMING_TYPE_NOT_ROAMING;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isEmergencyOnly() {
        return mIsEmergencyOnly;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaRoamingIndicator(){
        return this.mCdmaRoamingIndicator;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaDefaultRoamingIndicator(){
        return this.mCdmaDefaultRoamingIndicator;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaEriIconIndex() {
        return this.mCdmaEriIconIndex;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public int getCdmaEriIconMode() {
        return this.mCdmaEriIconMode;
    }

    /**
     * Get current registered operator name in long alphanumeric format.
     *
     * In GSM/UMTS, long format can be up to 16 characters long.
     * In CDMA, returns the ERI text, if set. Otherwise, returns the ONS.
     *
     * @return long name of operator, null if unregistered or unknown
     */
    public String getOperatorAlphaLong() {
        return mVoiceOperatorAlphaLong;
    }

    /**
     * Get current registered voice network operator name in long alphanumeric format.
     * @return long name of operator
     * @hide
     */
    @UnsupportedAppUsage
    public String getVoiceOperatorAlphaLong() {
        return mVoiceOperatorAlphaLong;
    }

    /**
     * Get current registered data network operator name in long alphanumeric format.
     * @return long name of voice operator
     * @hide
     */
    public String getDataOperatorAlphaLong() {
        return mDataOperatorAlphaLong;
    }

    /**
     * Get current registered operator name in short alphanumeric format.
     *
     * In GSM/UMTS, short format can be up to 8 characters long.
     *
     * @return short name of operator, null if unregistered or unknown
     */
    public String getOperatorAlphaShort() {
        return mVoiceOperatorAlphaShort;
    }

    /**
     * Get current registered voice network operator name in short alphanumeric format.
     * @return short name of operator, null if unregistered or unknown
     * @hide
     */
    @UnsupportedAppUsage
    public String getVoiceOperatorAlphaShort() {
        return mVoiceOperatorAlphaShort;
    }

    /**
     * Get current registered data network operator name in short alphanumeric format.
     * @return short name of operator, null if unregistered or unknown
     * @hide
     */
    @UnsupportedAppUsage
    public String getDataOperatorAlphaShort() {
        return mDataOperatorAlphaShort;
    }

    /**
     * Get current registered operator name in long alphanumeric format if
     * available or short otherwise.
     *
     * @see #getOperatorAlphaLong
     * @see #getOperatorAlphaShort
     *
     * @return name of operator, null if unregistered or unknown
     * @hide
     */
    public String getOperatorAlpha() {
        if (TextUtils.isEmpty(mVoiceOperatorAlphaLong)) {
            return mVoiceOperatorAlphaShort;
        }

        return mVoiceOperatorAlphaLong;
    }

    /**
     * Get current registered operator numeric id.
     *
     * In GSM/UMTS, numeric format is 3 digit country code plus 2 or 3 digit
     * network code.
     *
     * @return numeric format of operator, null if unregistered or unknown
     */
    /*
     * The country code can be decoded using
     * {@link com.android.internal.telephony.MccTable#countryCodeForMcc(int)}.
     */
    public String getOperatorNumeric() {
        return mVoiceOperatorNumeric;
    }

    /**
     * Get current registered voice network operator numeric id.
     * @return numeric format of operator, null if unregistered or unknown
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public String getVoiceOperatorNumeric() {
        return mVoiceOperatorNumeric;
    }

    /**
     * Get current registered data network operator numeric id.
     * @return numeric format of operator, null if unregistered or unknown
     * @hide
     */
    @UnsupportedAppUsage
    public String getDataOperatorNumeric() {
        return mDataOperatorNumeric;
    }

    /**
     * Get current network selection mode.
     *
     * @return true if manual mode, false if automatic mode
     */
    public boolean getIsManualSelection() {
        return mIsManualNetworkSelection;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mVoiceRegState,
                mDataRegState,
                mChannelNumber,
                mCellBandwidths,
                mVoiceOperatorAlphaLong,
                mVoiceOperatorAlphaShort,
                mVoiceOperatorNumeric,
                mDataOperatorAlphaLong,
                mDataOperatorAlphaShort,
                mDataOperatorNumeric,
                mIsManualNetworkSelection,
                mRilVoiceRadioTechnology,
                mRilDataRadioTechnology,
                mCssIndicator,
                mNetworkId,
                mSystemId,
                mCdmaRoamingIndicator,
                mCdmaDefaultRoamingIndicator,
                mCdmaEriIconIndex,
                mCdmaEriIconMode,
                mIsEmergencyOnly,
                mIsUsingCarrierAggregation,
                mLteEarfcnRsrpBoost,
                mNetworkRegistrationStates,
                mNrFrequencyRange);
    }

    @Override
    public boolean equals (Object o) {
        if (!(o instanceof ServiceState)) return false;
        ServiceState s = (ServiceState) o;

        return (mVoiceRegState == s.mVoiceRegState
                && mDataRegState == s.mDataRegState
                && mIsManualNetworkSelection == s.mIsManualNetworkSelection
                && mChannelNumber == s.mChannelNumber
                && Arrays.equals(mCellBandwidths, s.mCellBandwidths)
                && equalsHandlesNulls(mVoiceOperatorAlphaLong, s.mVoiceOperatorAlphaLong)
                && equalsHandlesNulls(mVoiceOperatorAlphaShort, s.mVoiceOperatorAlphaShort)
                && equalsHandlesNulls(mVoiceOperatorNumeric, s.mVoiceOperatorNumeric)
                && equalsHandlesNulls(mDataOperatorAlphaLong, s.mDataOperatorAlphaLong)
                && equalsHandlesNulls(mDataOperatorAlphaShort, s.mDataOperatorAlphaShort)
                && equalsHandlesNulls(mDataOperatorNumeric, s.mDataOperatorNumeric)
                && equalsHandlesNulls(mRilVoiceRadioTechnology, s.mRilVoiceRadioTechnology)
                && equalsHandlesNulls(mRilDataRadioTechnology, s.mRilDataRadioTechnology)
                && equalsHandlesNulls(mCssIndicator, s.mCssIndicator)
                && equalsHandlesNulls(mNetworkId, s.mNetworkId)
                && equalsHandlesNulls(mSystemId, s.mSystemId)
                && equalsHandlesNulls(mCdmaRoamingIndicator, s.mCdmaRoamingIndicator)
                && equalsHandlesNulls(mCdmaDefaultRoamingIndicator,
                        s.mCdmaDefaultRoamingIndicator)
                && mIsEmergencyOnly == s.mIsEmergencyOnly
                && mIsUsingCarrierAggregation == s.mIsUsingCarrierAggregation)
                && (mNetworkRegistrationStates == null ? s.mNetworkRegistrationStates == null :
                        s.mNetworkRegistrationStates != null &&
                        mNetworkRegistrationStates.containsAll(s.mNetworkRegistrationStates))
                && mNrFrequencyRange == s.mNrFrequencyRange;
    }

    /**
     * Convert roaming type to string
     *
     * @param roamingType roaming type
     * @return The roaming type in string format
     *
     * @hide
     */
    public static String roamingTypeToString(@RoamingType int roamingType) {
        switch (roamingType) {
            case ROAMING_TYPE_NOT_ROAMING: return "NOT_ROAMING";
            case ROAMING_TYPE_UNKNOWN: return "UNKNOWN";
            case ROAMING_TYPE_DOMESTIC: return "DOMESTIC";
            case ROAMING_TYPE_INTERNATIONAL: return "INTERNATIONAL";
        }
        return "Unknown roaming type " + roamingType;
    }

    /**
     * Convert radio technology to String
     *
     * @param rt radioTechnology
     * @return String representation of the RAT
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static String rilRadioTechnologyToString(int rt) {
        String rtString;

        switch(rt) {
            case RIL_RADIO_TECHNOLOGY_UNKNOWN:
                rtString = "Unknown";
                break;
            case RIL_RADIO_TECHNOLOGY_GPRS:
                rtString = "GPRS";
                break;
            case RIL_RADIO_TECHNOLOGY_EDGE:
                rtString = "EDGE";
                break;
            case RIL_RADIO_TECHNOLOGY_UMTS:
                rtString = "UMTS";
                break;
            case RIL_RADIO_TECHNOLOGY_IS95A:
                rtString = "CDMA-IS95A";
                break;
            case RIL_RADIO_TECHNOLOGY_IS95B:
                rtString = "CDMA-IS95B";
                break;
            case RIL_RADIO_TECHNOLOGY_1xRTT:
                rtString = "1xRTT";
                break;
            case RIL_RADIO_TECHNOLOGY_EVDO_0:
                rtString = "EvDo-rev.0";
                break;
            case RIL_RADIO_TECHNOLOGY_EVDO_A:
                rtString = "EvDo-rev.A";
                break;
            case RIL_RADIO_TECHNOLOGY_HSDPA:
                rtString = "HSDPA";
                break;
            case RIL_RADIO_TECHNOLOGY_HSUPA:
                rtString = "HSUPA";
                break;
            case RIL_RADIO_TECHNOLOGY_HSPA:
                rtString = "HSPA";
                break;
            case RIL_RADIO_TECHNOLOGY_EVDO_B:
                rtString = "EvDo-rev.B";
                break;
            case RIL_RADIO_TECHNOLOGY_EHRPD:
                rtString = "eHRPD";
                break;
            case RIL_RADIO_TECHNOLOGY_LTE:
                rtString = "LTE";
                break;
            case RIL_RADIO_TECHNOLOGY_HSPAP:
                rtString = "HSPAP";
                break;
            case RIL_RADIO_TECHNOLOGY_GSM:
                rtString = "GSM";
                break;
            case RIL_RADIO_TECHNOLOGY_IWLAN:
                rtString = "IWLAN";
                break;
            case RIL_RADIO_TECHNOLOGY_TD_SCDMA:
                rtString = "TD-SCDMA";
                break;
            case RIL_RADIO_TECHNOLOGY_LTE_CA:
                rtString = "LTE_CA";
                break;
            default:
                rtString = "Unexpected";
                Rlog.w(LOG_TAG, "Unexpected radioTechnology=" + rt);
                break;
        }
        return rtString;
    }

    /**
     * Convert RIL Service State to String
     *
     * @param serviceState
     * @return String representation of the ServiceState
     *
     * @hide
     */
    public static String rilServiceStateToString(int serviceState) {
        switch(serviceState) {
            case STATE_IN_SERVICE:
                return "IN_SERVICE";
            case STATE_OUT_OF_SERVICE:
                return "OUT_OF_SERVICE";
            case STATE_EMERGENCY_ONLY:
                return "EMERGENCY_ONLY";
            case STATE_POWER_OFF:
                return "POWER_OFF";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return new StringBuilder().append("{mVoiceRegState=").append(mVoiceRegState)
            .append("(" + rilServiceStateToString(mVoiceRegState) + ")")
            .append(", mDataRegState=").append(mDataRegState)
            .append("(" + rilServiceStateToString(mDataRegState) + ")")
            .append(", mChannelNumber=").append(mChannelNumber)
            .append(", duplexMode()=").append(getDuplexMode())
            .append(", mCellBandwidths=").append(Arrays.toString(mCellBandwidths))
            .append(", mVoiceOperatorAlphaLong=").append(mVoiceOperatorAlphaLong)
            .append(", mVoiceOperatorAlphaShort=").append(mVoiceOperatorAlphaShort)
            .append(", mDataOperatorAlphaLong=").append(mDataOperatorAlphaLong)
            .append(", mDataOperatorAlphaShort=").append(mDataOperatorAlphaShort)
            .append(", isManualNetworkSelection=").append(mIsManualNetworkSelection)
            .append(mIsManualNetworkSelection ? "(manual)" : "(automatic)")
            .append(", mRilVoiceRadioTechnology=").append(mRilVoiceRadioTechnology)
            .append("(" + rilRadioTechnologyToString(mRilVoiceRadioTechnology) + ")")
            .append(", mRilDataRadioTechnology=").append(mRilDataRadioTechnology)
            .append("(" + rilRadioTechnologyToString(mRilDataRadioTechnology) + ")")
            .append(", mCssIndicator=").append(mCssIndicator ? "supported" : "unsupported")
            .append(", mNetworkId=").append(mNetworkId)
            .append(", mSystemId=").append(mSystemId)
            .append(", mCdmaRoamingIndicator=").append(mCdmaRoamingIndicator)
            .append(", mCdmaDefaultRoamingIndicator=").append(mCdmaDefaultRoamingIndicator)
            .append(", mIsEmergencyOnly=").append(mIsEmergencyOnly)
            .append(", mIsUsingCarrierAggregation=").append(mIsUsingCarrierAggregation)
            .append(", mLteEarfcnRsrpBoost=").append(mLteEarfcnRsrpBoost)
            .append(", mNetworkRegistrationStates=").append(mNetworkRegistrationStates)
            .append(", mNrFrequencyRange=").append(mNrFrequencyRange)
            .append("}").toString();
    }

    private void setNullState(int state) {
        if (DBG) Rlog.d(LOG_TAG, "[ServiceState] setNullState=" + state);
        mVoiceRegState = state;
        mDataRegState = state;
        mChannelNumber = -1;
        mCellBandwidths = new int[0];
        mVoiceOperatorAlphaLong = null;
        mVoiceOperatorAlphaShort = null;
        mVoiceOperatorNumeric = null;
        mDataOperatorAlphaLong = null;
        mDataOperatorAlphaShort = null;
        mDataOperatorNumeric = null;
        mIsManualNetworkSelection = false;
        mRilVoiceRadioTechnology = 0;
        mRilDataRadioTechnology = 0;
        mCssIndicator = false;
        mNetworkId = -1;
        mSystemId = -1;
        mCdmaRoamingIndicator = -1;
        mCdmaDefaultRoamingIndicator = -1;
        mCdmaEriIconIndex = -1;
        mCdmaEriIconMode = -1;
        mIsEmergencyOnly = false;
        mIsUsingCarrierAggregation = false;
        mLteEarfcnRsrpBoost = 0;
        mNetworkRegistrationStates = new ArrayList<>();
        mNrFrequencyRange = FREQUENCY_RANGE_UNKNOWN;
    }

    public void setStateOutOfService() {
        setNullState(STATE_OUT_OF_SERVICE);
    }

    public void setStateOff() {
        setNullState(STATE_POWER_OFF);
    }

    public void setState(int state) {
        setVoiceRegState(state);
        if (DBG) Rlog.e(LOG_TAG, "[ServiceState] setState deprecated use setVoiceRegState()");
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setVoiceRegState(int state) {
        mVoiceRegState = state;
        if (DBG) Rlog.d(LOG_TAG, "[ServiceState] setVoiceRegState=" + mVoiceRegState);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setDataRegState(int state) {
        mDataRegState = state;
        if (VDBG) Rlog.d(LOG_TAG, "[ServiceState] setDataRegState=" + mDataRegState);
    }

    /** @hide */
    @TestApi
    public void setCellBandwidths(int[] bandwidths) {
        mCellBandwidths = bandwidths;
    }

    /** @hide */
    @TestApi
    public void setChannelNumber(int channelNumber) {
        mChannelNumber = channelNumber;
    }

    public void setRoaming(boolean roaming) {
        setVoiceRoaming(roaming);
        setDataRoaming(roaming);
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setVoiceRoaming(boolean roaming) {
        setVoiceRoamingType(roaming ? ROAMING_TYPE_UNKNOWN : ROAMING_TYPE_NOT_ROAMING);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setVoiceRoamingType(@RoamingType int type) {
        NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState == null) {
            regState = new NetworkRegistrationState(
                    NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    ServiceState.ROAMING_TYPE_NOT_ROAMING, TelephonyManager.NETWORK_TYPE_UNKNOWN, 0,
                    false, null, null);
            addNetworkRegistrationState(regState);
        }
        regState.setRoamingType(type);
    }

    /** @hide */
    @UnsupportedAppUsage
    public void setDataRoaming(boolean dataRoaming) {
        setDataRoamingType(dataRoaming ? ROAMING_TYPE_UNKNOWN : ROAMING_TYPE_NOT_ROAMING);
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setDataRoamingType(@RoamingType int type) {
        NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState == null) {
            regState = new NetworkRegistrationState(
                    NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    ServiceState.ROAMING_TYPE_NOT_ROAMING, TelephonyManager.NETWORK_TYPE_UNKNOWN, 0,
                    false, null, null);
            addNetworkRegistrationState(regState);
        }
        regState.setRoamingType(type);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setEmergencyOnly(boolean emergencyOnly) {
        mIsEmergencyOnly = emergencyOnly;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCdmaRoamingIndicator(int roaming) {
        this.mCdmaRoamingIndicator = roaming;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCdmaDefaultRoamingIndicator (int roaming) {
        this.mCdmaDefaultRoamingIndicator = roaming;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCdmaEriIconIndex(int index) {
        this.mCdmaEriIconIndex = index;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCdmaEriIconMode(int mode) {
        this.mCdmaEriIconMode = mode;
    }

    public void setOperatorName(String longName, String shortName, String numeric) {
        mVoiceOperatorAlphaLong = longName;
        mVoiceOperatorAlphaShort = shortName;
        mVoiceOperatorNumeric = numeric;
        mDataOperatorAlphaLong = longName;
        mDataOperatorAlphaShort = shortName;
        mDataOperatorNumeric = numeric;
    }

    /** @hide */
    public void setVoiceOperatorName(String longName, String shortName, String numeric) {
        mVoiceOperatorAlphaLong = longName;
        mVoiceOperatorAlphaShort = shortName;
        mVoiceOperatorNumeric = numeric;
    }

    /** @hide */
    public void setDataOperatorName(String longName, String shortName, String numeric) {
        mDataOperatorAlphaLong = longName;
        mDataOperatorAlphaShort = shortName;
        mDataOperatorNumeric = numeric;
    }

    /**
     * In CDMA, mOperatorAlphaLong can be set from the ERI text.
     * This is done from the GsmCdmaPhone and not from the ServiceStateTracker.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setOperatorAlphaLong(String longName) {
        mVoiceOperatorAlphaLong = longName;
        mDataOperatorAlphaLong = longName;
    }

    /** @hide */
    public void setVoiceOperatorAlphaLong(String longName) {
        mVoiceOperatorAlphaLong = longName;
    }

    /** @hide */
    public void setDataOperatorAlphaLong(String longName) {
        mDataOperatorAlphaLong = longName;
    }

    public void setIsManualSelection(boolean isManual) {
        mIsManualNetworkSelection = isManual;
    }

    /**
     * Test whether two objects hold the same data values or both are null.
     *
     * @param a first obj
     * @param b second obj
     * @return true if two objects equal or both are null
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static boolean equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /**
     * Set ServiceState based on intent notifier map.
     *
     * @param m intent notifier map
     * @hide
     */
    @UnsupportedAppUsage
    private void setFromNotifierBundle(Bundle m) {
        ServiceState ssFromBundle = m.getParcelable(Intent.EXTRA_SERVICE_STATE);
        if (ssFromBundle != null) {
            copyFrom(ssFromBundle);
        }
    }

    /**
     * Set intent notifier Bundle based on service state.
     *
     * @param m intent notifier Bundle
     * @hide
     */
    @UnsupportedAppUsage
    public void fillInNotifierBundle(Bundle m) {
        m.putParcelable(Intent.EXTRA_SERVICE_STATE, this);
        // serviceState already consists of below entries.
        // for backward compatibility, we continue fill in below entries.
        m.putInt("voiceRegState", mVoiceRegState);
        m.putInt("dataRegState", mDataRegState);
        m.putInt("dataRoamingType", getDataRoamingType());
        m.putInt("voiceRoamingType", getVoiceRoamingType());
        m.putString("operator-alpha-long", mVoiceOperatorAlphaLong);
        m.putString("operator-alpha-short", mVoiceOperatorAlphaShort);
        m.putString("operator-numeric", mVoiceOperatorNumeric);
        m.putString("data-operator-alpha-long", mDataOperatorAlphaLong);
        m.putString("data-operator-alpha-short", mDataOperatorAlphaShort);
        m.putString("data-operator-numeric", mDataOperatorNumeric);
        m.putBoolean("manual", mIsManualNetworkSelection);
        m.putInt("radioTechnology", mRilVoiceRadioTechnology);
        m.putInt("dataRadioTechnology", mRilDataRadioTechnology);
        m.putBoolean("cssIndicator", mCssIndicator);
        m.putInt("networkId", mNetworkId);
        m.putInt("systemId", mSystemId);
        m.putInt("cdmaRoamingIndicator", mCdmaRoamingIndicator);
        m.putInt("cdmaDefaultRoamingIndicator", mCdmaDefaultRoamingIndicator);
        m.putBoolean("emergencyOnly", mIsEmergencyOnly);
        m.putBoolean("isDataRoamingFromRegistration", getDataRoamingFromRegistration());
        m.putBoolean("isUsingCarrierAggregation", mIsUsingCarrierAggregation);
        m.putInt("LteEarfcnRsrpBoost", mLteEarfcnRsrpBoost);
        m.putInt("ChannelNumber", mChannelNumber);
        m.putIntArray("CellBandwidths", mCellBandwidths);
        m.putInt("mNrFrequencyRange", mNrFrequencyRange);
    }

    /** @hide */
    @TestApi
    public void setRilVoiceRadioTechnology(@RilRadioTechnology int rt) {
        if (rt == RIL_RADIO_TECHNOLOGY_LTE_CA) {
            rt = RIL_RADIO_TECHNOLOGY_LTE;
        }

        this.mRilVoiceRadioTechnology = rt;

        // sync to network registration state
        NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState == null) {
            regState = new NetworkRegistrationState(
                    NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    ServiceState.ROAMING_TYPE_NOT_ROAMING, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    0, false, null, null);
            addNetworkRegistrationState(regState);
        }
        regState.setAccessNetworkTechnology(
                rilRadioTechnologyToNetworkType(mRilVoiceRadioTechnology));
    }

    /** @hide */
    @TestApi
    public void setRilDataRadioTechnology(@RilRadioTechnology int rt) {
        if (rt == RIL_RADIO_TECHNOLOGY_LTE_CA) {
            rt = RIL_RADIO_TECHNOLOGY_LTE;
            this.mIsUsingCarrierAggregation = true;
        } else {
            this.mIsUsingCarrierAggregation = false;
        }
        this.mRilDataRadioTechnology = rt;
        if (VDBG) Rlog.d(LOG_TAG, "[ServiceState] setRilDataRadioTechnology=" +
                mRilDataRadioTechnology);

        // sync to network registration state
        NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        if (regState == null) {
            regState = new NetworkRegistrationState(
                    NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    ServiceState.ROAMING_TYPE_NOT_ROAMING, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    0, false, null, null);
            addNetworkRegistrationState(regState);
        }
        regState.setAccessNetworkTechnology(
                rilRadioTechnologyToNetworkType(mRilDataRadioTechnology));
    }

    /** @hide */
    public boolean isUsingCarrierAggregation() {
        return mIsUsingCarrierAggregation;
    }

    /** @hide */
    public void setIsUsingCarrierAggregation(boolean ca) {
        mIsUsingCarrierAggregation = ca;
    }

    /**
     * @return the frequency range of 5G NR.
     * @hide
     */
    public @FrequencyRange int getNrFrequencyRange() {
        return mNrFrequencyRange;
    }

    /**
     * Get the NR 5G status of the mobile data network.
     * @return the NR 5G status.
     * @hide
     */
    public @NRStatus int getNrStatus() {
        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState == null) return NetworkRegistrationState.NR_STATUS_NONE;
        return regState.getNrStatus();
    }

    /**
     * @param nrFrequencyRange the frequency range of 5G NR.
     * @hide
     */
    public void setNrFrequencyRange(@FrequencyRange int nrFrequencyRange) {
        mNrFrequencyRange = nrFrequencyRange;
    }

    /** @hide */
    public int getLteEarfcnRsrpBoost() {
        return mLteEarfcnRsrpBoost;
    }

    /** @hide */
    public void setLteEarfcnRsrpBoost(int LteEarfcnRsrpBoost) {
        mLteEarfcnRsrpBoost = LteEarfcnRsrpBoost;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setCssIndicator(int css) {
        this.mCssIndicator = (css != 0);
    }

    /** @hide */
    @TestApi
    public void setCdmaSystemAndNetworkId(int systemId, int networkId) {
        this.mSystemId = systemId;
        this.mNetworkId = networkId;
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getRilVoiceRadioTechnology() {
        return this.mRilVoiceRadioTechnology;
    }
    /** @hide */
    @UnsupportedAppUsage
    public int getRilDataRadioTechnology() {
        return this.mRilDataRadioTechnology;
    }
    /**
     * @hide
     * @Deprecated to be removed Q3 2013 use {@link #getRilDataRadioTechnology} or
     * {@link #getRilVoiceRadioTechnology}
     */
    @UnsupportedAppUsage
    public int getRadioTechnology() {
        Rlog.e(LOG_TAG, "ServiceState.getRadioTechnology() DEPRECATED will be removed *******");
        return getRilDataRadioTechnology();
    }

    /** @hide */
    public static int rilRadioTechnologyToNetworkType(@RilRadioTechnology int rat) {
        switch(rat) {
            case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS:
                return TelephonyManager.NETWORK_TYPE_GPRS;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE:
                return TelephonyManager.NETWORK_TYPE_EDGE;
            case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS:
                return TelephonyManager.NETWORK_TYPE_UMTS;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA:
                return TelephonyManager.NETWORK_TYPE_HSDPA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA:
                return TelephonyManager.NETWORK_TYPE_HSUPA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA:
                return TelephonyManager.NETWORK_TYPE_HSPA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95A:
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95B:
                return TelephonyManager.NETWORK_TYPE_CDMA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
                return TelephonyManager.NETWORK_TYPE_1xRTT;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
                return TelephonyManager.NETWORK_TYPE_EVDO_0;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
                return TelephonyManager.NETWORK_TYPE_EVDO_A;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
                return TelephonyManager.NETWORK_TYPE_EVDO_B;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
                return TelephonyManager.NETWORK_TYPE_EHRPD;
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                return TelephonyManager.NETWORK_TYPE_LTE;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP:
                return TelephonyManager.NETWORK_TYPE_HSPAP;
            case ServiceState.RIL_RADIO_TECHNOLOGY_GSM:
                return TelephonyManager.NETWORK_TYPE_GSM;
            case ServiceState.RIL_RADIO_TECHNOLOGY_TD_SCDMA:
                return TelephonyManager.NETWORK_TYPE_TD_SCDMA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN:
                return TelephonyManager.NETWORK_TYPE_IWLAN;
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA:
                return TelephonyManager.NETWORK_TYPE_LTE_CA;
            case ServiceState.RIL_RADIO_TECHNOLOGY_NR:
                return TelephonyManager.NETWORK_TYPE_NR;
            default:
                return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    /** @hide */
    public static int rilRadioTechnologyToAccessNetworkType(@RilRadioTechnology int rt) {
        switch(rt) {
            case RIL_RADIO_TECHNOLOGY_GPRS:
            case RIL_RADIO_TECHNOLOGY_EDGE:
            case RIL_RADIO_TECHNOLOGY_GSM:
                return AccessNetworkType.GERAN;
            case RIL_RADIO_TECHNOLOGY_UMTS:
            case RIL_RADIO_TECHNOLOGY_HSDPA:
            case RIL_RADIO_TECHNOLOGY_HSPAP:
            case RIL_RADIO_TECHNOLOGY_HSUPA:
            case RIL_RADIO_TECHNOLOGY_HSPA:
            case RIL_RADIO_TECHNOLOGY_TD_SCDMA:
                return AccessNetworkType.UTRAN;
            case RIL_RADIO_TECHNOLOGY_IS95A:
            case RIL_RADIO_TECHNOLOGY_IS95B:
            case RIL_RADIO_TECHNOLOGY_1xRTT:
            case RIL_RADIO_TECHNOLOGY_EVDO_0:
            case RIL_RADIO_TECHNOLOGY_EVDO_A:
            case RIL_RADIO_TECHNOLOGY_EVDO_B:
            case RIL_RADIO_TECHNOLOGY_EHRPD:
                return AccessNetworkType.CDMA2000;
            case RIL_RADIO_TECHNOLOGY_LTE:
            case RIL_RADIO_TECHNOLOGY_LTE_CA:
                return AccessNetworkType.EUTRAN;
            case RIL_RADIO_TECHNOLOGY_IWLAN:
                return AccessNetworkType.IWLAN;
            case RIL_RADIO_TECHNOLOGY_UNKNOWN:
            default:
                return AccessNetworkType.UNKNOWN;
        }
    }

    /** @hide */
    public static int networkTypeToRilRadioTechnology(int networkType) {
        switch(networkType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return ServiceState.RIL_RADIO_TECHNOLOGY_GPRS;
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return ServiceState.RIL_RADIO_TECHNOLOGY_EDGE;
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return ServiceState.RIL_RADIO_TECHNOLOGY_UMTS;
            case TelephonyManager.NETWORK_TYPE_HSDPA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA;
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA;
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_HSPA;
            case TelephonyManager.NETWORK_TYPE_CDMA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_IS95A;
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT;
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
                return ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0;
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
                return ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A;
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B;
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD;
            case TelephonyManager.NETWORK_TYPE_LTE:
                return ServiceState.RIL_RADIO_TECHNOLOGY_LTE;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP;
            case TelephonyManager.NETWORK_TYPE_GSM:
                return ServiceState.RIL_RADIO_TECHNOLOGY_GSM;
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_TD_SCDMA;
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
            case TelephonyManager.NETWORK_TYPE_LTE_CA:
                return ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA;
            default:
                return ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public @TelephonyManager.NetworkType int getDataNetworkType() {
        final NetworkRegistrationState iwlanRegState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        if (iwlanRegState != null
                && iwlanRegState.getRegState() == NetworkRegistrationState.REG_STATE_HOME) {
            // If the device is on IWLAN, return IWLAN as the network type. This is to simulate the
            // behavior of legacy mode device. In the future caller should use
            // getNetworkRegistrationState() to retrieve the actual data network type on cellular
            // or on IWLAN.
            return iwlanRegState.getAccessNetworkTechnology();
        }

        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState != null) {
            return regState.getAccessNetworkTechnology();
        }
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public @TelephonyManager.NetworkType int getVoiceNetworkType() {
        final NetworkRegistrationState regState = getNetworkRegistrationState(
                NetworkRegistrationState.DOMAIN_CS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regState != null) {
            return regState.getAccessNetworkTechnology();
        }
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getCssIndicator() {
        return this.mCssIndicator ? 1 : 0;
    }

    /**
     * Get the CDMA NID (Network Identification Number), a number uniquely identifying a network
     * within a wireless system. (Defined in 3GPP2 C.S0023 3.4.8)
     * @return The CDMA NID or {@link #UNKNOWN_ID} if not available.
     */
    public int getCdmaNetworkId() {
        return this.mNetworkId;
    }

    /**
     * Get the CDMA SID (System Identification Number), a number uniquely identifying a wireless
     * system. (Defined in 3GPP2 C.S0023 3.4.8)
     * @return The CDMA SID or {@link #UNKNOWN_ID} if not available.
     */
    public int getCdmaSystemId() {
        return this.mSystemId;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isGsm(int radioTechnology) {
        return radioTechnology == RIL_RADIO_TECHNOLOGY_GPRS
                || radioTechnology == RIL_RADIO_TECHNOLOGY_EDGE
                || radioTechnology == RIL_RADIO_TECHNOLOGY_UMTS
                || radioTechnology == RIL_RADIO_TECHNOLOGY_HSDPA
                || radioTechnology == RIL_RADIO_TECHNOLOGY_HSUPA
                || radioTechnology == RIL_RADIO_TECHNOLOGY_HSPA
                || radioTechnology == RIL_RADIO_TECHNOLOGY_LTE
                || radioTechnology == RIL_RADIO_TECHNOLOGY_HSPAP
                || radioTechnology == RIL_RADIO_TECHNOLOGY_GSM
                || radioTechnology == RIL_RADIO_TECHNOLOGY_TD_SCDMA
                || radioTechnology == RIL_RADIO_TECHNOLOGY_IWLAN
                || radioTechnology == RIL_RADIO_TECHNOLOGY_LTE_CA;

    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isCdma(int radioTechnology) {
        return radioTechnology == RIL_RADIO_TECHNOLOGY_IS95A
                || radioTechnology == RIL_RADIO_TECHNOLOGY_IS95B
                || radioTechnology == RIL_RADIO_TECHNOLOGY_1xRTT
                || radioTechnology == RIL_RADIO_TECHNOLOGY_EVDO_0
                || radioTechnology == RIL_RADIO_TECHNOLOGY_EVDO_A
                || radioTechnology == RIL_RADIO_TECHNOLOGY_EVDO_B
                || radioTechnology == RIL_RADIO_TECHNOLOGY_EHRPD;
    }

    /** @hide */
    public static boolean isLte(int radioTechnology) {
        return radioTechnology == RIL_RADIO_TECHNOLOGY_LTE ||
                radioTechnology == RIL_RADIO_TECHNOLOGY_LTE_CA;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static boolean bearerBitmapHasCdma(int networkTypeBitmask) {
        return (RIL_RADIO_CDMA_TECHNOLOGY_BITMASK
                & convertNetworkTypeBitmaskToBearerBitmask(networkTypeBitmask)) != 0;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean bitmaskHasTech(int bearerBitmask, int radioTech) {
        if (bearerBitmask == 0) {
            return true;
        } else if (radioTech >= 1) {
            return ((bearerBitmask & (1 << (radioTech - 1))) != 0);
        }
        return false;
    }

    /** @hide */
    public static int getBitmaskForTech(int radioTech) {
        if (radioTech >= 1) {
            return (1 << (radioTech - 1));
        }
        return 0;
    }

    /** @hide */
    public static int getBitmaskFromString(String bearerList) {
        String[] bearers = bearerList.split("\\|");
        int bearerBitmask = 0;
        for (String bearer : bearers) {
            int bearerInt = 0;
            try {
                bearerInt = Integer.parseInt(bearer.trim());
            } catch (NumberFormatException nfe) {
                return 0;
            }

            if (bearerInt == 0) {
                return 0;
            }

            bearerBitmask |= getBitmaskForTech(bearerInt);
        }
        return bearerBitmask;
    }

    /** @hide */
    public static int convertNetworkTypeBitmaskToBearerBitmask(int networkTypeBitmask) {
        if (networkTypeBitmask == 0) {
            return 0;
        }
        int bearerBitmask = 0;
        for (int bearerInt = 0; bearerInt < NEXT_RIL_RADIO_TECHNOLOGY; bearerInt++) {
            if (bitmaskHasTech(networkTypeBitmask, rilRadioTechnologyToNetworkType(bearerInt))) {
                bearerBitmask |= getBitmaskForTech(bearerInt);
            }
        }
        return bearerBitmask;
    }

    /** @hide */
    public static int convertBearerBitmaskToNetworkTypeBitmask(int bearerBitmask) {
        if (bearerBitmask == 0) {
            return 0;
        }
        int networkTypeBitmask = 0;
        for (int bearerInt = 0; bearerInt < NEXT_RIL_RADIO_TECHNOLOGY; bearerInt++) {
            if (bitmaskHasTech(bearerBitmask, bearerInt)) {
                networkTypeBitmask |= getBitmaskForTech(rilRadioTechnologyToNetworkType(bearerInt));
            }
        }
        return networkTypeBitmask;
    }

    /**
     * Returns a merged ServiceState consisting of the base SS with voice settings from the
     * voice SS. The voice SS is only used if it is IN_SERVICE (otherwise the base SS is returned).
     * @hide
     * */
    @UnsupportedAppUsage
    public static ServiceState mergeServiceStates(ServiceState baseSs, ServiceState voiceSs) {
        if (voiceSs.mVoiceRegState != STATE_IN_SERVICE) {
            return baseSs;
        }

        ServiceState newSs = new ServiceState(baseSs);

        // voice overrides
        newSs.mVoiceRegState = voiceSs.mVoiceRegState;
        newSs.mIsEmergencyOnly = false; // only get here if voice is IN_SERVICE

        return newSs;
    }

    /**
     * Get all of the available network registration states.
     *
     * @return List of {@link NetworkRegistrationState}
     * @hide
     */
    @NonNull
    @SystemApi
    public List<NetworkRegistrationState> getNetworkRegistrationStates() {
        synchronized (mNetworkRegistrationStates) {
            return new ArrayList<>(mNetworkRegistrationStates);
        }
    }

    /**
     * Get the network registration states for the transport type.
     *
     * @param transportType The transport type
     * @return List of {@link NetworkRegistrationState}
     * @hide
     *
     * @deprecated Use {@link #getNetworkRegistrationStatesForTransportType(int)}
     */
    @NonNull
    @Deprecated
    @SystemApi
    public List<NetworkRegistrationState> getNetworkRegistrationStates(int transportType) {
        return getNetworkRegistrationStatesForTransportType(transportType);
    }

    /**
     * Get the network registration states for the transport type.
     *
     * @param transportType The transport type
     * @return List of {@link NetworkRegistrationState}
     * @hide
     */
    @NonNull
    @SystemApi
    public List<NetworkRegistrationState> getNetworkRegistrationStatesForTransportType(
            @TransportType int transportType) {
        List<NetworkRegistrationState> list = new ArrayList<>();

        synchronized (mNetworkRegistrationStates) {
            for (NetworkRegistrationState networkRegistrationState : mNetworkRegistrationStates) {
                if (networkRegistrationState.getTransportType() == transportType) {
                    list.add(networkRegistrationState);
                }
            }
        }

        return list;
    }

    /**
     * Get the network registration states for the network domain.
     *
     * @param domain The network {@link NetworkRegistrationState.Domain domain}
     * @return List of {@link NetworkRegistrationState}
     * @hide
     */
    @NonNull
    @SystemApi
    public List<NetworkRegistrationState> getNetworkRegistrationStatesForDomain(
            @Domain int domain) {
        List<NetworkRegistrationState> list = new ArrayList<>();

        synchronized (mNetworkRegistrationStates) {
            for (NetworkRegistrationState networkRegistrationState : mNetworkRegistrationStates) {
                if (networkRegistrationState.getDomain() == domain) {
                    list.add(networkRegistrationState);
                }
            }
        }

        return list;
    }

    /**
     * Get the network registration state for the transport type and network domain.
     *
     * @param domain The network {@link NetworkRegistrationState.Domain domain}
     * @param transportType The transport type
     * @return The matching {@link NetworkRegistrationState}
     * @hide
     *
     * @deprecated Use {@link #getNetworkRegistrationState(int, int)}
     */
    @Nullable
    @Deprecated
    @SystemApi
    public NetworkRegistrationState getNetworkRegistrationStates(@Domain int domain,
                                                                 @TransportType int transportType) {
        return getNetworkRegistrationState(domain, transportType);
    }

    /**
     * Get the network registration state for the transport type and network domain.
     *
     * @param domain The network {@link NetworkRegistrationState.Domain domain}
     * @param transportType The transport type
     * @return The matching {@link NetworkRegistrationState}
     * @hide
     *
     */
    @Nullable
    @SystemApi
    public NetworkRegistrationState getNetworkRegistrationState(@Domain int domain,
                                                                @TransportType int transportType) {
        synchronized (mNetworkRegistrationStates) {
            for (NetworkRegistrationState networkRegistrationState : mNetworkRegistrationStates) {
                if (networkRegistrationState.getTransportType() == transportType
                        && networkRegistrationState.getDomain() == domain) {
                    return networkRegistrationState;
                }
            }
        }

        return null;
    }

    /**
     * @hide
     */
    public void addNetworkRegistrationState(NetworkRegistrationState regState) {
        if (regState == null) return;

        synchronized (mNetworkRegistrationStates) {
            for (int i = 0; i < mNetworkRegistrationStates.size(); i++) {
                NetworkRegistrationState curRegState = mNetworkRegistrationStates.get(i);
                if (curRegState.getTransportType() == regState.getTransportType()
                        && curRegState.getDomain() == regState.getDomain()) {
                    mNetworkRegistrationStates.remove(i);
                    break;
                }
            }

            mNetworkRegistrationStates.add(regState);
        }
    }

    /**
     * @hide
     */
    public static final int getBetterNRFrequencyRange(int range1, int range2) {
        return FREQUENCY_RANGE_ORDER.indexOf(range1) > FREQUENCY_RANGE_ORDER.indexOf(range2)
                ? range1
                : range2;
    }

    /**
     * Returns a copy of self with location-identifying information removed.
     * Always clears the NetworkRegistrationState's CellIdentity fields, but if removeCoarseLocation
     * is true, clears other info as well.
     * @hide
     */
    public ServiceState sanitizeLocationInfo(boolean removeCoarseLocation) {
        ServiceState state = new ServiceState(this);
        if (state.mNetworkRegistrationStates != null) {
            state.mNetworkRegistrationStates = state.mNetworkRegistrationStates.stream()
                    .map(NetworkRegistrationState::sanitizeLocationInfo)
                    .collect(Collectors.toList());
        }
        if (!removeCoarseLocation) return state;

        state.mDataOperatorAlphaLong = null;
        state.mDataOperatorAlphaShort = null;
        state.mDataOperatorNumeric = null;
        state.mVoiceOperatorAlphaLong = null;
        state.mVoiceOperatorAlphaShort = null;
        state.mVoiceOperatorNumeric = null;

        return state;
    }
}
