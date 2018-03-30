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

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides STK Call Control Supplementary Service information.
 *
 * {@hide}
 */
@SystemApi
public final class ImsSsData implements Parcelable {

    // Supplementary Service Type
    // Call Forwarding
    public static final int SS_CFU = 0;
    public static final int SS_CF_BUSY = 1;
    public static final int SS_CF_NO_REPLY = 2;
    public static final int SS_CF_NOT_REACHABLE = 3;
    public static final int SS_CF_ALL = 4;
    public static final int SS_CF_ALL_CONDITIONAL = 5;
    public static final int SS_CFUT = 6;
    // Called Line Presentation
    public static final int SS_CLIP = 7;
    public static final int SS_CLIR = 8;
    public static final int SS_COLP = 9;
    public static final int SS_COLR = 10;
    // Calling Name Presentation
    public static final int SS_CNAP = 11;
    // Call Waiting
    public static final int SS_WAIT = 12;
    // Call Barring
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

    //Supplementary Service Request Types
    public static final int SS_ACTIVATION = 0;
    public static final int SS_DEACTIVATION = 1;
    public static final int SS_INTERROGATION = 2;
    public static final int SS_REGISTRATION = 3;
    public static final int SS_ERASURE = 4;

    // Supplementary Service Teleservice Type
    public static final int SS_ALL_TELE_AND_BEARER_SERVICES = 0;
    public static final int SS_ALL_TELESEVICES = 1;
    public static final int SS_TELEPHONY = 2;
    public static final int SS_ALL_DATA_TELESERVICES = 3;
    public static final int SS_SMS_SERVICES = 4;
    public static final int SS_ALL_TELESERVICES_EXCEPT_SMS = 5;

    // Service Class of Supplementary Service
    // See 27.007 +CCFC or +CLCK
    /** @hide */
    public static final int SERVICE_CLASS_NONE = 0; // no user input
    /** @hide */
    public static final int SERVICE_CLASS_VOICE = 1;
    /** @hide */
    public static final int SERVICE_CLASS_DATA = (1 << 1);
    /** @hide */
    public static final int SERVICE_CLASS_FAX = (1 << 2);
    /** @hide */
    public static final int SERVICE_CLASS_SMS = (1 << 3);
    /** @hide */
    public static final int SERVICE_CLASS_DATA_SYNC = (1 << 4);
    /** @hide */
    public static final int SERVICE_CLASS_DATA_ASYNC = (1 << 5);
    /** @hide */
    public static final int SERVICE_CLASS_PACKET = (1 << 6);
    /** @hide */
    public static final int SERVICE_CLASS_PAD = (1 << 7);

    /**
     * Result code used if the operation was successful. See {@link #result}.
     * @hide
     */
    public static final int RESULT_SUCCESS = 0;

    /** @hide */
    @IntDef(flag = true, prefix = { "SS_" }, value = {
            SS_CFU,
            SS_CF_BUSY,
            SS_CF_NO_REPLY,
            SS_CF_NOT_REACHABLE,
            SS_CF_ALL,
            SS_CF_ALL_CONDITIONAL,
            SS_CFUT,
            SS_CLIP,
            SS_CLIR,
            SS_COLP,
            SS_COLR,
            SS_CNAP,
            SS_WAIT,
            SS_BAOC,
            SS_BAOIC,
            SS_BAOIC_EXC_HOME,
            SS_BAIC,
            SS_BAIC_ROAMING,
            SS_ALL_BARRING,
            SS_OUTGOING_BARRING,
            SS_INCOMING_BARRING,
            SS_INCOMING_BARRING_DN,
            SS_INCOMING_BARRING_ANONYMOUS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceType{}

    /** @hide */
    @IntDef(flag = true, prefix = { "SERVICE_CLASS" }, value = {
            SERVICE_CLASS_NONE,
            SERVICE_CLASS_VOICE,
            SERVICE_CLASS_DATA,
            SERVICE_CLASS_FAX,
            SERVICE_CLASS_SMS,
            SERVICE_CLASS_DATA_SYNC,
            SERVICE_CLASS_DATA_ASYNC,
            SERVICE_CLASS_PACKET,
            SERVICE_CLASS_PAD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceClass{}

    /**
     * The Service type of this Supplementary service. Valid values include:
     *     SS_CFU,
     *     SS_CF_BUSY,
     *     SS_CF_NO_REPLY,
     *     SS_CF_NOT_REACHABLE,
     *     SS_CF_ALL,
     *     SS_CF_ALL_CONDITIONAL,
     *     SS_CFUT,
     *     SS_CLIP,
     *     SS_CLIR,
     *     SS_COLP,
     *     SS_COLR,
     *     SS_CNAP,
     *     SS_WAIT,
     *     SS_BAOC,
     *     SS_BAOIC,
     *     SS_BAOIC_EXC_HOME,
     *     SS_BAIC,
     *     SS_BAIC_ROAMING,
     *     SS_ALL_BARRING,
     *     SS_OUTGOING_BARRING,
     *     SS_INCOMING_BARRING,
     *     SS_INCOMING_BARRING_DN,
     *     SS_INCOMING_BARRING_ANONYMOUS
     *
     * @hide
     */
    // TODO: Make final, do not modify this field directly!
    public int serviceType;

    /**
     * Supplementary Service request Type. Valid values are:
     *     SS_ACTIVATION,
     *     SS_DEACTIVATION,
     *     SS_INTERROGATION,
     *     SS_REGISTRATION,
     *     SS_ERASURE
     *
     * @hide
     */
    // TODO: Make final, do not modify this field directly!
    public int requestType;

    /**
     * Supplementary Service teleservice type:
     *     SS_TELESERVICE_ALL_TELE_AND_BEARER,
     *     SS_TELESERVICE_ALL_TELESEVICES,
     *     SS_TELESERVICE_TELEPHONY,
     *     SS_TELESERVICE_ALL_DATA,
     *     SS_TELESERVICE_SMS,
     *     SS_TELESERVICE_ALL_TELESERVICES_EXCEPT_SMS
     *
     * @hide
     */
    // TODO: Make this param final! Do not try to modify this param directly.
    public int teleserviceType;

    /**
     * Supplementary Service service class. Valid values are:
     *     SERVICE_CLASS_NONE,
     *     SERVICE_CLASS_VOICE,
     *     SERVICE_CLASS_DATA,
     *     SERVICE_CLASS_FAX,
     *     SERVICE_CLASS_SMS,
     *     SERVICE_CLASS_DATA_SYNC,
     *     SERVICE_CLASS_DATA_ASYNC,
     *     SERVICE_CLASS_PACKET,
     *     SERVICE_CLASS_PAD
     *
     * @hide
     */
    // TODO: Make this param final! Do not try to modify this param directly.
    public int serviceClass;

    /**
     * Result of Supplementary Service operation. Valid values are:
     *     RESULT_SUCCESS if the result is success, or
     *     ImsReasonInfo code if the result is a failure.
     *
     * @hide
     */
    // TODO: Make this param final! Do not try to modify this param directly.
    public final int result;

    private int[] mSsInfo;
    private ImsCallForwardInfo[] mCfInfo;
    private ImsSsInfo[] mImsSsInfo;

    /**
     * Generate IMS Supplementary Service information.
     * @param serviceType The Supplementary Service type. Valid entries:
     *     SS_CFU,
     *     SS_CF_BUSY,
     *     SS_CF_NO_REPLY,
     *     SS_CF_NOT_REACHABLE,
     *     SS_CF_ALL,
     *     SS_CF_ALL_CONDITIONAL,
     *     SS_CFUT,
     *     SS_CLIP,
     *     SS_CLIR,
     *     SS_COLP,
     *     SS_COLR,
     *     SS_CNAP,
     *     SS_WAIT,
     *     SS_BAOC,
     *     SS_BAOIC,
     *     SS_BAOIC_EXC_HOME,
     *     SS_BAIC,
     *     SS_BAIC_ROAMING,
     *     SS_ALL_BARRING,
     *     SS_OUTGOING_BARRING,
     *     SS_INCOMING_BARRING,
     *     SS_INCOMING_BARRING_DN,
     *     SS_INCOMING_BARRING_ANONYMOUS
     * @param requestType Supplementary Service request Type. Valid values are:
     *     SS_ACTIVATION,
     *     SS_DEACTIVATION,
     *     SS_INTERROGATION,
     *     SS_REGISTRATION,
     *     SS_ERASURE
     * @param teleserviceType Supplementary Service teleservice type:
     *     SS_TELESERVICE_ALL_TELE_AND_BEARER,
     *     SS_TELESERVICE_ALL_TELESEVICES,
     *     SS_TELESERVICE_TELEPHONY,
     *     SS_TELESERVICE_ALL_DATA,
     *     SS_TELESERVICE_SMS,
     *     SS_TELESERVICE_ALL_TELESERVICES_EXCEPT_SMS
     * @param serviceClass Supplementary Service service class. See See 27.007 +CCFC or +CLCK.
     * @param result Result of Supplementary Service operation. Valid values are 0 if the result is
     *               success, or ImsReasonInfo code if the result is a failure.
     */
    public ImsSsData(@ServiceType int serviceType, int requestType, int teleserviceType,
            @ServiceClass int serviceClass, int result) {
        this.serviceType = serviceType;
        this.requestType = requestType;
        this.teleserviceType = teleserviceType;
        this.serviceClass = serviceClass;
        this.result = result;
    }

    private ImsSsData(Parcel in) {
        serviceType = in.readInt();
        requestType = in.readInt();
        teleserviceType = in.readInt();
        serviceClass = in.readInt();
        result = in.readInt();
        mSsInfo = in.createIntArray();
        mCfInfo = (ImsCallForwardInfo[])in.readParcelableArray(this.getClass().getClassLoader());
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
        out.writeIntArray(mSsInfo);
        out.writeParcelableArray(mCfInfo, 0);
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
        return (serviceType == SS_INTERROGATION);
    }

    /** @hide */
    public void setSuppServiceInfo(int[] ssInfo) {
        mSsInfo = ssInfo;
    }

    /** @hide */
    public void setImsSpecificSuppServiceInfo(ImsSsInfo[] imsSsInfo) {
        mImsSsInfo = imsSsInfo;
    }

    /** @hide */
    public void setCallForwardingInfo(ImsCallForwardInfo[] cfInfo) {
        mCfInfo = cfInfo;
    }

    /**
     * This field will be null for RequestType SS_INTERROGATION
     * and ServiceType SS_CF_*, SS_INCOMING_BARRING_DN,
     * SS_INCOMING_BARRING_ANONYMOUS.
     *
     * @hide
     */
    public int[] getSuppServiceInfo() {
        return mSsInfo;
    }

    /**
     * Valid only for ServiceTypes
     *  - SS_INCOMING_BARRING_DN and
     *  - ServiceType SS_INCOMING_BARRING_ANONYMOUS.
     *  Will be null otherwise.
     * @hide
     */
    public ImsSsInfo[] getImsSpecificSuppServiceInfo() {
        return mImsSsInfo;
    }

    /**
     * Valid only for supplementary services
     * - ServiceType SS_CF_* and
     * - RequestType SS_INTERROGATION.
     * Will be null otherwise.
     * @hide
     **/
    public ImsCallForwardInfo[] getCallForwardInfo() {
        return mCfInfo;
    }

    public String toString() {
        return "[ImsSsData] " + "ServiceType: " + serviceType
            + " RequestType: " + requestType
            + " TeleserviceType: " + teleserviceType
            + " ServiceClass: " + serviceClass
            + " Result: " + result;
    }
}
