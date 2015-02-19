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
        final int GSM = RAF_GSM | RAF_GPRS | RAF_EDGE;
        final int HS = RAF_HSUPA | RAF_HSDPA | RAF_HSPA | RAF_HSPAP;
        final int CDMA = RAF_IS95A | RAF_IS95B | RAF_1xRTT;
        final int EVDO = RAF_EVDO_0 | RAF_EVDO_A | RAF_EVDO_B;
        final int WCDMA = HS | RAF_UMTS;

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
                raf = CDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO:
                raf = RAF_LTE | CDMA | EVDO;
                break;
            case RILConstants.NETWORK_MODE_LTE_GSM_WCDMA:
                raf = RAF_LTE | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA:
                raf = RAF_LTE | CDMA | EVDO | GSM | WCDMA;
                break;
            case RILConstants.NETWORK_MODE_LTE_ONLY:
                raf = RAF_LTE;
                break;
            case RILConstants.NETWORK_MODE_LTE_WCDMA:
                raf = RAF_LTE | WCDMA;
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
            default:
                raf = RAF_UNKNOWN;
                break;
        }
        return raf;
    }
}

