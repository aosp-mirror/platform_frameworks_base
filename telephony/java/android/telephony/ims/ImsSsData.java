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
 * limitations under the License
 */
package android.telephony.ims;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Provided STK Call Control Suplementary Service information
 *
 * {@hide}
 */
@SystemApi
public final class ImsSsData implements Parcelable {

    //ServiceType
    public static final int SS_CFU = 0;
    public static final int SS_CF_BUSY = 1;
    public static final int SS_CF_NO_REPLY = 2;
    public static final int SS_CF_NOT_REACHABLE = 3;
    public static final int SS_CF_ALL = 4;
    public static final int SS_CF_ALL_CONDITIONAL = 5;
    public static final int SS_CFUT = 6;
    public static final int SS_CLIP = 7;
    public static final int SS_CLIR = 8;
    public static final int SS_COLP = 9;
    public static final int SS_COLR = 10;
    public static final int SS_CNAP = 11;
    public static final int SS_WAIT = 12;
    public static final int SS_BAOC = 13;
    public static final int SS_BAOIC = 14;
    public static final int SS_BAOIC_EXC_HOME = 15;
    public static final int SS_BAIC = 16;
    public static final int SS_BAIC_ROAMING = 17;
    public static final int SS_ALL_BARRING = 18;
    public static final int SS_OUTGOING_BARRING = 19;
    public static final int SS_INCOMING_BARRING = 20;
    public static final int SS_INCOMING_BARRING_DN = 21;
    public static final int SS_INCOMING_BARRING_ANONYMOUS = 22;

    //SSRequestType
    public static final int SS_ACTIVATION = 0;
    public static final int SS_DEACTIVATION = 1;
    public static final int SS_INTERROGATION = 2;
    public static final int SS_REGISTRATION = 3;
    public static final int SS_ERASURE = 4;

    //TeleserviceType
    public static final int SS_ALL_TELE_AND_BEARER_SERVICES = 0;
    public static final int SS_ALL_TELESEVICES = 1;
    public static final int SS_TELEPHONY = 2;
    public static final int SS_ALL_DATA_TELESERVICES = 3;
    public static final int SS_SMS_SERVICES = 4;
    public static final int SS_ALL_TELESERVICES_EXCEPT_SMS = 5;

    // Refer to ServiceType
    /** @hide */
    public int serviceType;
    // Refere to SSRequestType
    /** @hide */
    public int requestType;
    // Refer to TeleserviceType
    /** @hide */
    public int teleserviceType;
    // Service Class
    /** @hide */
    public int serviceClass;
    // Error information
    /** @hide */
    public int result;

    /** @hide */
    public int[] ssInfo; /* Valid for all supplementary services.
                             This field will be empty for RequestType SS_INTERROGATION
                             and ServiceType SS_CF_*, SS_INCOMING_BARRING_DN,
                             SS_INCOMING_BARRING_ANONYMOUS.*/

    /** @hide */
    public ImsCallForwardInfo[] cfInfo; /* Valid only for supplementary services
                                            ServiceType SS_CF_* and RequestType SS_INTERROGATION */

    /** @hide */
    public ImsSsInfo[] imsSsInfo;   /* Valid only for ServiceType SS_INCOMING_BARRING_DN and
                                        ServiceType SS_INCOMING_BARRING_ANONYMOUS */

    public ImsSsData() {}

    private ImsSsData(Parcel in) {
        readFromParcel(in);
    }

    public static final Creator<ImsSsData> CREATOR = new Creator<ImsSsData>() {
        @Override
        public ImsSsData createFromParcel(Parcel in) {
            return new ImsSsData(in);
        }

        @Override
        public ImsSsData[] newArray(int size) {
            return new ImsSsData[size];
        }
    };

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(serviceType);
        out.writeInt(requestType);
        out.writeInt(teleserviceType);
        out.writeInt(serviceClass);
        out.writeInt(result);
        out.writeIntArray(ssInfo);
        out.writeParcelableArray(cfInfo, 0);
    }

    private void readFromParcel(Parcel in) {
        serviceType = in.readInt();
        requestType = in.readInt();
        teleserviceType = in.readInt();
        serviceClass = in.readInt();
        result = in.readInt();
        ssInfo = in.createIntArray();
        cfInfo = (ImsCallForwardInfo[])in.readParcelableArray(this.getClass().getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Old method, kept for compatibility. See {@link #isTypeCf()}
     * @hide
     */
    public boolean isTypeCF() {
        return (serviceType == SS_CFU || serviceType == SS_CF_BUSY ||
              serviceType == SS_CF_NO_REPLY || serviceType == SS_CF_NOT_REACHABLE ||
              serviceType == SS_CF_ALL || serviceType == SS_CF_ALL_CONDITIONAL);
    }

    public boolean isTypeCf() {
        return isTypeCF();
    }

    public boolean isTypeUnConditional() {
        return (serviceType == SS_CFU || serviceType == SS_CF_ALL);
    }

    /**
     * Old method, kept for compatibility. See {@link #isTypeCf()}
     * @hide
     */
    public boolean isTypeCW() {
        return (serviceType == SS_WAIT);
    }

    public boolean isTypeCw() {
        return isTypeCW();
    }

    public boolean isTypeClip() {
        return (serviceType == SS_CLIP);
    }

    public boolean isTypeColr() {
        return (serviceType == SS_COLR);
    }

    public boolean isTypeColp() {
        return (serviceType == SS_COLP);
    }

    public boolean isTypeClir() {
        return (serviceType == SS_CLIR);
    }

    public boolean isTypeIcb() {
        return (serviceType == SS_INCOMING_BARRING_DN ||
                serviceType == SS_INCOMING_BARRING_ANONYMOUS);
    }

    public boolean isTypeBarring() {
        return (serviceType == SS_BAOC || serviceType == SS_BAOIC ||
              serviceType == SS_BAOIC_EXC_HOME || serviceType == SS_BAIC ||
              serviceType == SS_BAIC_ROAMING || serviceType == SS_ALL_BARRING ||
              serviceType == SS_OUTGOING_BARRING || serviceType == SS_INCOMING_BARRING);
    }

    public boolean isTypeInterrogation() {
        return (requestType == SS_INTERROGATION);
    }

    public String toString() {
        return "[ImsSsData] " + "ServiceType: " + serviceType
            + " RequestType: " + requestType
            + " TeleserviceType: " + teleserviceType
            + " ServiceClass: " + serviceClass
            + " Result: " + result;
    }
}
