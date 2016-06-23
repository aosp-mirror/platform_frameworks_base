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

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.RILConstants;

/**
 * Object to indicate the phone radio type and access technology.
 *
 * @hide
 */
public class RadioAccessFamily implements Parcelable {

    // Radio Access Family
    public static final int RAF_UNKNOWN = (1 <<  ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
    public static final int RAF_GPRS = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_GPRS);
    public static final int RAF_EDGE = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_EDGE);
    public static final int RAF_UMTS = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
    public static final int RAF_IS95A = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_IS95A);
    public static final int RAF_IS95B = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_IS95B);
    public static final int RAF_1xRTT = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT);
    public static final int RAF_EVDO_0 = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0);
    public static final int RAF_EVDO_A = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A);
    public static final int RAF_HSDPA = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA);
    public static final int RAF_HSUPA = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA);
    public static final int RAF_HSPA = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_HSPA);
    public static final int RAF_EVDO_B = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B);
    public static final int RAF_EHRPD = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD);
    public static final int RAF_LTE = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
    public static final int RAF_HSPAP = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP);
    public static final int RAF_GSM = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_GSM);
    public static final int RAF_TD_SCDMA = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_TD_SCDMA);
    public static final int RAF_LTE_CA = (1 << ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA);

    // Grouping of RAFs
    private static final int GSM = RAF_GSM | RAF_GPRS | RAF_EDGE;
    private static final int HS = RAF_HSUPA | RAF_HSDPA | RAF_HSPA | RAF_HSPAP;
    private static final int CDMA = RAF_IS95A | RAF_IS95B | RAF_1xRTT;
    private static final int EVDO = RAF_EVDO_0 | RAF_EVDO_A | RAF_EVDO_B | RAF_EHRPD;
    private static final int WCDMA = HS | RAF_UMTS;
    private static final int LTE = RAF_LTE | RAF_LTE_CA;

    /* Phone ID of phone */
    private int mPhoneId;

    /* Radio Access Family */
    private int mRadioAccessFamily;

    /**
     * Constructor.
     *
     * @param phoneId the phone ID
     * @param radioAccessFamily the phone radio access family defined
     *        in RadioAccessFamily. It's a bit mask value to represent
     *        the support type.
     */
    public RadioAccessFamily(int phoneId, int radioAccessFamily) {
        mPhoneId = phoneId;
        mRadioAccessFamily = radioAccessFamily;
    }

    /**
     * Get phone ID.
     *
     * @return phone ID
     */
    public int getPhoneId() {
        return mPhoneId;
    }

    /**
     * get radio access family.
     *
     * @return radio access family
     */
    public int getRadioAccessFamily() {
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
    public static final Creator<RadioAccessFamily> CREATOR =
            new Creator<RadioAccessFamily>() {

        @Override
        public RadioAccessFamily createFromParcel(Parcel in) {
            int phoneId = in.readInt();
            int radioAccessFamily = in.readInt();

            return new RadioAccessFamily(phoneId, radioAccessFamily);
        }

        @Override
        public RadioAccessFamily[] newArray(int size) {
            return new RadioAccessFamily[size];
        }
    };

    public static int getRafFromNetworkType(int type) {
        int raf;

        switch (type) {
            case RILConstants.NETWORK_MODE_WCDMA_PREF:
                raf = GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_GSM_ONLY:
                raf = GSM;
                break;
            case RILConstants.NETWORK_MODE_WCDMA_ONLY:
                raf = WCDMA;
                break;
            case RILConstants.NETWORK_MODE_GSM_UMTS:
                raf = GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_CDMA:
                raf = CDMA | EVDO;
                break;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
                raf = LTE | CDMA | EVDO;
                break;
            case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
                raf = LTE | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                raf = LTE | CDMA | EVDO | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_ONLY:
                raf = LTE;
                break;
            case RILConstants.NETWORK_MODE_LTE_WCDMA:
                raf = LTE | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_CDMA_NO_EVDO:
                raf = CDMA;
                break;
            case RILConstants.NETWORK_MODE_EVDO_NO_CDMA:
                raf = EVDO;
                break;
            case RILConstants.NETWORK_MODE_GLOBAL:
                raf = GSM | WCDMA | CDMA | EVDO;
                break;
            case RILConstants.NETWORK_MODE_TDSCDMA_ONLY:
                raf = RAF_TD_SCDMA;
                break;
            case RILConstants.NETWORK_MODE_TDSCDMA_WCDMA:
                raf = RAF_TD_SCDMA | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA:
                raf = LTE | RAF_TD_SCDMA;
                break;
            case RILConstants.NETWORK_MODE_TDSCDMA_GSM:
                raf = RAF_TD_SCDMA | GSM;
                break;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM:
                raf = LTE | RAF_TD_SCDMA | GSM;
                break;
            case RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA:
                raf = RAF_TD_SCDMA | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA:
                raf = LTE | RAF_TD_SCDMA | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA:
                raf = LTE | RAF_TD_SCDMA | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                raf = RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA:
                raf = LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA;
                break;
            default:
                raf = RAF_UNKNOWN;
                break;
        }

        return raf;
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

        return raf;
    }

    public static int getNetworkTypeFromRaf(int raf) {
        int type;

        raf = getAdjustedRaf(raf);

        switch (raf) {
            case (GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_WCDMA_PREF;
                break;
            case GSM:
                type = RILConstants.NETWORK_MODE_GSM_ONLY;
                break;
            case WCDMA:
                type = RILConstants.NETWORK_MODE_WCDMA_ONLY;
                break;
            case (CDMA | EVDO):
                type = RILConstants.NETWORK_MODE_CDMA;
                break;
            case (LTE | CDMA | EVDO):
                type = RILConstants.NETWORK_MODE_LTE_CDMA_EVDO;
                break;
            case (LTE | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_GSM_WCDMA;
                break;
            case (LTE | CDMA | EVDO | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA;
                break;
            case LTE:
                type = RILConstants.NETWORK_MODE_LTE_ONLY;
                break;
            case (LTE | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_WCDMA;
                break;
            case CDMA:
                type = RILConstants.NETWORK_MODE_CDMA_NO_EVDO;
                break;
            case EVDO:
                type = RILConstants.NETWORK_MODE_EVDO_NO_CDMA;
                break;
            case (GSM | WCDMA | CDMA | EVDO):
                type = RILConstants.NETWORK_MODE_GLOBAL;
                break;
            case RAF_TD_SCDMA:
                type = RILConstants.NETWORK_MODE_TDSCDMA_ONLY;
                break;
            case (RAF_TD_SCDMA | WCDMA):
                type = RILConstants.NETWORK_MODE_TDSCDMA_WCDMA;
                break;
            case (LTE | RAF_TD_SCDMA):
                type = RILConstants.NETWORK_MODE_LTE_TDSCDMA;
                break;
            case (RAF_TD_SCDMA | GSM):
                type = RILConstants.NETWORK_MODE_TDSCDMA_GSM;
                break;
            case (LTE | RAF_TD_SCDMA | GSM):
                type = RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM;
                break;
            case (RAF_TD_SCDMA | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_TDSCDMA_GSM_WCDMA;
                break;
            case (LTE | RAF_TD_SCDMA | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_TDSCDMA_WCDMA;
                break;
            case (LTE | RAF_TD_SCDMA | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_TDSCDMA_GSM_WCDMA;
                break;
            case (RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
                break;
            case (LTE | RAF_TD_SCDMA | CDMA | EVDO | GSM | WCDMA):
                type = RILConstants.NETWORK_MODE_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA;
                break;
            default:
                type = RILConstants.PREFERRED_NETWORK_MODE ;
                break;
        }

        return type;
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
}
