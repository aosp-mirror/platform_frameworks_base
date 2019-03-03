/*
* Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;
import android.hardware.radio.V1_0.RadioTechnology;
import android.hardware.radio.V1_4.CellInfo.Info;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.RILConstants;


/**
 * Object to indicate the phone radio type and access technology.
 *
 * @hide
 */
public class RadioAccessFamily implements Parcelable {

    /**
     * TODO: get rid of RAF definition in RadioAccessFamily and
     * use {@link TelephonyManager.NetworkTypeBitMask}
     * TODO: public definition {@link TelephonyManager.NetworkTypeBitMask} is long.
     * TODO: Convert from int to long everywhere including HAL definitions.
     */
    // 2G
    public static final int RAF_UNKNOWN = (int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN;
    public static final int RAF_GSM = (int) TelephonyManager.NETWORK_TYPE_BITMASK_GSM;
    public static final int RAF_GPRS = (int) TelephonyManager.NETWORK_TYPE_BITMASK_GPRS;
    public static final int RAF_EDGE = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EDGE;
    public static final int RAF_IS95A = (int) TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
    public static final int RAF_IS95B = (int) TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
    public static final int RAF_1xRTT = (int) TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT;
    // 3G
    public static final int RAF_EVDO_0 = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_0;
    public static final int RAF_EVDO_A = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A;
    public static final int RAF_EVDO_B = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_B;
    public static final int RAF_EHRPD = (int) TelephonyManager.NETWORK_TYPE_BITMASK_EHRPD;
    public static final int RAF_HSUPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA;
    public static final int RAF_HSDPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA;
    public static final int RAF_HSPA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPA;
    public static final int RAF_HSPAP = (int) TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP;
    public static final int RAF_UMTS = (int) TelephonyManager.NETWORK_TYPE_BITMASK_UMTS;
    public static final int RAF_TD_SCDMA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA;
    // 4G
    public static final int RAF_LTE = (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE;
    public static final int RAF_LTE_CA = (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;

    // 5G
    public static final int RAF_NR = (int) TelephonyManager.NETWORK_TYPE_BITMASK_NR;

    // Grouping of RAFs
    // 2G
    private static final int GSM = RAF_GSM | RAF_GPRS | RAF_EDGE;
    private static final int CDMA = RAF_IS95A | RAF_IS95B | RAF_1xRTT;
    // 3G
    private static final int EVDO = RAF_EVDO_0 | RAF_EVDO_A | RAF_EVDO_B | RAF_EHRPD;
    private static final int HS = RAF_HSUPA | RAF_HSDPA | RAF_HSPA | RAF_HSPAP;
    private static final int WCDMA = HS | RAF_UMTS;
    // 4G
    private static final int LTE = RAF_LTE | RAF_LTE_CA;

    // 5G
    private static final int NR = RAF_NR;

    /* Phone ID of phone */
    private int mPhoneId;

    /* Radio Access Family */
    private int mRadioAccessFamily;

    /**
     * Constructor.
     *
     * @param phoneId the phone ID
     * @param radioAccessFamily the phone radio access family bitmask based on
     * {@link TelephonyManager.NetworkTypeBitMask}. It's a bit mask value to represent the support
     *                          type.
     */
    @UnsupportedAppUsage
    public RadioAccessFamily(int phoneId, int radioAccessFamily) {
        mPhoneId = phoneId;
        mRadioAccessFamily = radioAccessFamily;
    }

    /**
     * Get phone ID.
     *
     * @return phone ID
     */
    @UnsupportedAppUsage
    public int getPhoneId() {
        return mPhoneId;
    }

    /**
     * get radio access family.
     *
     * @return radio access family
     */
    @UnsupportedAppUsage
    public @TelephonyManager.NetworkTypeBitMask int getRadioAccessFamily() {
        return mRadioAccessFamily;
    }

    @Override
    public String toString() {
        String ret = "{ mPhoneId = " + mPhoneId
                + ", mRadioAccessFamily = " + mRadioAccessFamily
                + "}";
        return ret;
    }

    /**
     * Implement the Parcelable interface.
     *
     * @return describe content
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     *
     * @param outParcel The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(Parcel outParcel, int flags) {
        outParcel.writeInt(mPhoneId);
        outParcel.writeInt(mRadioAccessFamily);
    }

    /**
     * Implement the Parcelable interface.
     */
    public static final @android.annotation.NonNull Creator<android.telephony.RadioAccessFamily> CREATOR =
            new Creator<android.telephony.RadioAccessFamily>() {

        @Override
        public android.telephony.RadioAccessFamily createFromParcel(Parcel in) {
            int phoneId = in.readInt();
            int radioAccessFamily = in.readInt();

            return new android.telephony.RadioAccessFamily(phoneId, radioAccessFamily);
        }

        @Override
        public android.telephony.RadioAccessFamily[] newArray(int size) {
            return new android.telephony.RadioAccessFamily[size];
        }
    };

    @UnsupportedAppUsage
    public static int getRafFromNetworkType(int type) {
        switch (type) {
            case RILConstants.NETWORK_MODE_WCDMA_PREF:
                return GSM | WCDMA;
            case RILConstants.NETWORK_MODE_GSM_ONLY:
                return GSM;
            case RILConstants.NETWORK_MODE_WCDMA_ONLY:
                return WCDMA;
            case RILConstants.NETWORK_MODE_GSM_UMTS:
                return GSM | WCDMA;
            case RILConstants.NETWORK_MODE_CDMA:
                return CDMA | EVDO;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
                return LTE | CDMA | EVDO;
            case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
                return LTE | GSM | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                return LTE | CDMA | EVDO | GSM | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_ONLY:
                return LTE;
            case RILConstants.NETWORK_MODE_LTE_WCDMA:
                return LTE | WCDMA;
            case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
                return CDMA;
            case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
                return EVDO;
            case RILConstants.NETWORK_MODE_GLOBAL:
                return GSM | WCDMA | CDMA | EVDO;
            case RILConstants.NETWORK_MODE_TDSCDMA_ONLY:
                return RAF_TD_SCDMA;
            case RILConstants.NETWORK_MODE_TDSCDMA_WCDMA:
                return RAF_TD_SCDMA | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA:
                return LTE | RAF_TD_SCDMA;
            case RILConstants.NETWORK_MODE_TDSCDMA_GSM:
                return RAF_TD_SCDMA | GSM;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
                return LTE | RAF_TD_SCDMA | GSM;
            case RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
                return RAF_TD_SCDMA | GSM | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
                return LTE | RAF_TD_SCDMA | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
                return LTE | RAF_TD_SCDMA | GSM | WCDMA;
            case RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                return LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_ONLY):
                return NR;
            case (RILConstants.NETWORK_MODE_NR_LTE):
                return NR | LTE;
            case (RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO):
                return NR | LTE | CDMA | EVDO;
            case (RILConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA):
                return NR | LTE | GSM | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA):
                return NR | LTE | CDMA | EVDO | GSM | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_WCDMA):
                return NR | LTE | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA):
                return NR | LTE | RAF_TD_SCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM):
                return NR | LTE | RAF_TD_SCDMA | GSM;
            case (RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | GSM | WCDMA;
            case (RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA):
                return NR | LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
            default:
                return RAF_UNKNOWN;
        }
    }

    /**
     * if the raf includes ANY bit set for a group
     * adjust it to contain ALL the bits for that group
     */
    private static int getAdjustedRaf(int raf) {
        raf = ((GSM & raf) > 0) ? (GSM | raf) : raf;
        raf = ((WCDMA & raf) > 0) ? (WCDMA | raf) : raf;
        raf = ((CDMA & raf) > 0) ? (CDMA | raf) : raf;
        raf = ((EVDO & raf) > 0) ? (EVDO | raf) : raf;
        raf = ((LTE & raf) > 0) ? (LTE | raf) : raf;
        raf = ((NR & raf) > 0) ? (NR | raf) : raf;

        return raf;
    }

    /**
     * Returns the highest capability of the RadioAccessFamily (4G > 3G > 2G).
     * @param raf The RadioAccessFamily that we wish to filter
     * @return The highest radio capability
     */
    public static int getHighestRafCapability(int raf) {
        if ((LTE & raf) > 0) {
            return TelephonyManager.NETWORK_CLASS_4_G;
        }
        if ((EVDO|HS|WCDMA & raf) > 0) {
            return TelephonyManager.NETWORK_CLASS_3_G;
        }
        if((GSM|CDMA & raf) > 0) {
            return TelephonyManager.NETWORK_CLASS_2_G;
        }
        return TelephonyManager.NETWORK_CLASS_UNKNOWN;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static int getNetworkTypeFromRaf(int raf) {
        raf = getAdjustedRaf(raf);

        switch (raf) {
            case (GSM | WCDMA):
                return RILConstants.NETWORK_MODE_WCDMA_PREF;
            case GSM:
                return RILConstants.NETWORK_MODE_GSM_ONLY;
            case WCDMA:
                return RILConstants.NETWORK_MODE_WCDMA_ONLY;
            case (CDMA | EVDO):
                return RILConstants.NETWORK_MODE_CDMA;
            case (LTE | CDMA | EVDO):
                return RILConstants.NETWORK_MODE_LTE_CDMA_EVDO;
            case (LTE | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_GSM_WCDMA;
            case (LTE | CDMA | EVDO | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA;
            case LTE:
                return RILConstants.NETWORK_MODE_LTE_ONLY;
            case (LTE | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_WCDMA;
            case CDMA:
                return RILConstants.NETWORK_MODE_CDMA_NO_EVDO;
            case EVDO:
                return RILConstants.NETWORK_MODE_EVDO_NO_CDMA;
            case (GSM | WCDMA | CDMA | EVDO):
                return RILConstants.NETWORK_MODE_GLOBAL;
            case RAF_TD_SCDMA:
                return RILConstants.NETWORK_MODE_TDSCDMA_ONLY;
            case (RAF_TD_SCDMA | WCDMA):
                return RILConstants.NETWORK_MODE_TDSCDMA_WCDMA;
            case (LTE | RAF_TD_SCDMA):
                return RILConstants.NETWORK_MODE_LTE_TDSCDMA;
            case (RAF_TD_SCDMA | GSM):
                return RILConstants.NETWORK_MODE_TDSCDMA_GSM;
            case (LTE | RAF_TD_SCDMA | GSM):
                return RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM;
            case (RAF_TD_SCDMA | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA;
            case (LTE | RAF_TD_SCDMA | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA;
            case (LTE | RAF_TD_SCDMA | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA;
            case (RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            case (LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            case (NR):
                return RILConstants.NETWORK_MODE_NR_ONLY;
            case (NR | LTE):
                return RILConstants.NETWORK_MODE_NR_LTE;
            case (NR | LTE | CDMA | EVDO):
                return RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO;
            case (NR | LTE | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_GSM_WCDMA;
            case (NR | LTE | CDMA | EVDO | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_CDMA_EVDO_GSM_WCDMA;
            case (NR | LTE | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA;
            case (NR | LTE | RAF_TD_SCDMA | GSM):
                return RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM;
            case (NR | LTE | RAF_TD_SCDMA | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_GSM_WCDMA;
            case (NR | LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                return RILConstants.NETWORK_MODE_NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
            default:
                return RILConstants.PREFERRED_NETWORK_MODE;
        }
    }

    public static int singleRafTypeFromString(String rafString) {
        switch (rafString) {
            case "GPRS":    return RAF_GPRS;
            case "EDGE":    return RAF_EDGE;
            case "UMTS":    return RAF_UMTS;
            case "IS95A":   return RAF_IS95A;
            case "IS95B":   return RAF_IS95B;
            case "1XRTT":   return RAF_1xRTT;
            case "EVDO_0":  return RAF_EVDO_0;
            case "EVDO_A":  return RAF_EVDO_A;
            case "HSDPA":   return RAF_HSDPA;
            case "HSUPA":   return RAF_HSUPA;
            case "HSPA":    return RAF_HSPA;
            case "EVDO_B":  return RAF_EVDO_B;
            case "EHRPD":   return RAF_EHRPD;
            case "LTE":     return RAF_LTE;
            case "HSPAP":   return RAF_HSPAP;
            case "GSM":     return RAF_GSM;
            case "TD_SCDMA":return RAF_TD_SCDMA;
            case "HS":      return HS;
            case "CDMA":    return CDMA;
            case "EVDO":    return EVDO;
            case "WCDMA":   return WCDMA;
            case "LTE_CA":  return RAF_LTE_CA;
            case "NR":      return RAF_NR;
            default:        return RAF_UNKNOWN;
        }
    }

    public static int rafTypeFromString(String rafList) {
        rafList = rafList.toUpperCase();
        String[] rafs = rafList.split("\\|");
        int result = 0;
        for(String raf : rafs) {
            int rafType = singleRafTypeFromString(raf.trim());
            if (rafType == RAF_UNKNOWN) return rafType;
            result |= rafType;
        }
        return result;
    }

    /**
     * convert RAF from {@link android.hardware.radio.V1_0.RadioAccessFamily} to
     * {@link TelephonyManager.NetworkTypeBitMask}, the bitmask represented by
     * {@link TelephonyManager.NetworkType}.
     *
     * @param raf {@link android.hardware.radio.V1_0.RadioAccessFamily}
     * @return {@link TelephonyManager.NetworkTypeBitMask}
     */
    public static int convertToNetworkTypeBitMask(int raf) {
        int networkTypeRaf = 0;

        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.GSM) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_GSM;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.GPRS) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_GPRS;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.EDGE) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_EDGE;
        }
        // convert both IS95A/IS95B to CDMA as network mode doesn't support CDMA
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.IS95A) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.IS95B) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_CDMA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.ONE_X_RTT) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.EVDO_0) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_0;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.EVDO_A) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_A;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.EVDO_B) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_EVDO_B;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.EHRPD) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_EHRPD;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.HSUPA) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.HSDPA) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.HSPA) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_HSPA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.HSPAP) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.UMTS) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_UMTS;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.TD_SCDMA) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.LTE) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_LTE;
        }
        if ((raf & android.hardware.radio.V1_0.RadioAccessFamily.LTE_CA) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA;
        }
        if ((raf & android.hardware.radio.V1_4.RadioAccessFamily.NR) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_NR;
        }
        // TODO: need hal definition
        if ((raf & (1 << ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN)) != 0) {
            networkTypeRaf |= TelephonyManager.NETWORK_TYPE_BITMASK_IWLAN;
        }

        return (networkTypeRaf == 0) ? TelephonyManager.NETWORK_TYPE_UNKNOWN : networkTypeRaf;
    }
}
