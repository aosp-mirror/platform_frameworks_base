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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides STK Call Control Supplementary Service information.
 *
 * {@hide}
 */
@SystemApi
public final class ImsSsData implements Parcelable {

    private static final String TAG = ImsSsData.class.getCanonicalName();

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


    /**@hide*/
    @IntDef(flag = true, prefix = {"SS_"}, value = {
            SS_ACTIVATION,
            SS_DEACTIVATION,
            SS_INTERROGATION,
            SS_REGISTRATION,
            SS_ERASURE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestType{}

    //Supplementary Service Request Types
    public static final int SS_ACTIVATION = 0;
    public static final int SS_DEACTIVATION = 1;
    public static final int SS_INTERROGATION = 2;
    public static final int SS_REGISTRATION = 3;
    public static final int SS_ERASURE = 4;

    /**@hide*/
    @IntDef(flag = true, prefix = {"SS_"}, value = {
            SS_ALL_TELE_AND_BEARER_SERVICES,
            SS_ALL_TELESEVICES,
            SS_TELEPHONY,
            SS_ALL_DATA_TELESERVICES,
            SS_SMS_SERVICES,
            SS_ALL_TELESERVICES_EXCEPT_SMS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TeleserviceType{}

    // Supplementary Service Teleservice Type
    public static final int SS_ALL_TELE_AND_BEARER_SERVICES = 0;
    public static final int SS_ALL_TELESEVICES = 1;
    public static final int SS_TELEPHONY = 2;
    public static final int SS_ALL_DATA_TELESERVICES = 3;
    public static final int SS_SMS_SERVICES = 4;
    public static final int SS_ALL_TELESERVICES_EXCEPT_SMS = 5;

    /**
     * No call forwarding service class defined.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_NONE = 0;

    /**
     * Service class flag for voice telephony.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_VOICE = 1;

    /**
     * Service class flag for all data bearers (including
     * {@link #SERVICE_CLASS_DATA_CIRCUIT_SYNC,
     * {@link #SERVICE_CLASS_DATA_CIRCUIT_ASYNC}, {@link #SERVICE_CLASS_PACKET_ACCESS},
     * {@link #SERVICE_CLASS_PAD}}) if supported by the carrier.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_DATA = (1 << 1);
    /**
     * Service class flag for fax services.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_FAX = (1 << 2);
    /**
     * Service class flag for SMS services.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_SMS = (1 << 3);
    /**
     * Service class flag for the synchronous bearer service.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_DATA_CIRCUIT_SYNC = (1 << 4);

    /**
     * Service class flag for the asynchronous bearer service.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_DATA_CIRCUIT_ASYNC = (1 << 5);

    /**
     * Service class flag for the packet access bearer service.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_DATA_PACKET_ACCESS = (1 << 6);

    /**
     * Service class flag for the Packet Assembly/Disassembly bearer service.
     *
     * See TS 27.007 7.11 (+CCFC) and 7.4 (CLCK)
     */
    public static final int SERVICE_CLASS_DATA_PAD = (1 << 7);

    /**@hide*/
    @IntDef(flag = true, prefix = {"SERVICE_CLASS_"}, value = {
            SERVICE_CLASS_NONE,
            SERVICE_CLASS_VOICE,
            SERVICE_CLASS_DATA,
            SERVICE_CLASS_FAX,
            SERVICE_CLASS_SMS,
            SERVICE_CLASS_DATA_CIRCUIT_SYNC,
            SERVICE_CLASS_DATA_CIRCUIT_ASYNC,
            SERVICE_CLASS_DATA_PACKET_ACCESS,
            SERVICE_CLASS_DATA_PAD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceClassFlags{}

    /**
     * Result code used if the operation was successful. See {@link #getResult()}.
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

    /**
     * The Service type of this Supplementary service.
     * @hide
     */
    public final @ServiceType int serviceType;

    /**
     * Supplementary Service request Type:
     *     {@link #SS_ACTIVATION),
     *     {@link #SS_DEACTIVATION},
     *     {@link #SS_INTERROGATION},
     *     {@link #SS_REGISTRATION},
     *     {@link #SS_ERASURE}
     * @hide
     */
    public final @RequestType int requestType;

    /**
     * Supplementary Service teleservice type:
     *     {@link #SS_ALL_TELE_AND_BEARER_SERVICES},
     *     {@link #SS_ALL_TELESEVICES},
     *     {@link #SS_TELEPHONY},
     *     {@link #SS_ALL_DATA_TELESERVICES},
     *     {@link #SS_SMS_SERVICES},
     *     {@link #SS_ALL_TELESERVICES_EXCEPT_SMS}
     *
     * @hide
     */
    public final @TeleserviceType int teleserviceType;

    /**
     * Supplementary Service service class.
     *
     * @hide
     */
    public final @ServiceClassFlags int serviceClass;

    /**
     * Result of Supplementary Service operation. Valid values are:
     *     {@link #RESULT_SUCCESS} if the result is success, or
     *     ImsReasonInfo code if the result is a failure.
     *
     * @hide
     */
    public final int result;

    private int[] mSsInfo;
    private ImsCallForwardInfo[] mCfInfo;
    private ImsSsInfo[] mImsSsInfo;

    /**
     * Builder for optional ImsSsData parameters.
     */
    public static class Builder {
        private ImsSsData mImsSsData;

        /**
         * Generate IMS Supplementary Service information.
         * @param serviceType The Supplementary Service type.
         * @param requestType Supplementary Service request Type:
         *     {@link #SS_ACTIVATION},
         *     {@link #SS_DEACTIVATION},
         *     {@link #SS_INTERROGATION},
         *     {@link #SS_REGISTRATION},
         *     {@link #SS_ERASURE}
         * @param teleserviceType Supplementary Service teleservice type:
         *     {@link #SS_ALL_TELE_AND_BEARER_SERVICES},
         *     {@link #SS_ALL_TELESEVICES},
         *     {@link #SS_TELEPHONY},
         *     {@link #SS_ALL_DATA_TELESERVICES},
         *     {@link #SS_SMS_SERVICES},
         *     {@link #SS_ALL_TELESERVICES_EXCEPT_SMS}
         * @param serviceClass Supplementary Service service class. See See 27.007 +CCFC or +CLCK.
         * @param result Result of Supplementary Service operation. Valid values are 0 if the result
         *               is success, or {@link ImsReasonInfo} code if the result is a failure.
         * @return this Builder instance for further constructing.
         * @see #build()
         */
        public Builder(@ServiceType int serviceType, int requestType, int teleserviceType,
                @ServiceClassFlags int serviceClass, int result) {
            mImsSsData = new ImsSsData(serviceType, requestType, teleserviceType, serviceClass,
                    result);
        }

        /**
         * Set the array of {@link ImsSsInfo}s that are associated with this supplementary service
         * data.
         */
        public @NonNull Builder setSuppServiceInfo(@NonNull ImsSsInfo[] imsSsInfos) {
            mImsSsData.mImsSsInfo = imsSsInfos;
            return this;
        }

        /**
         * Set the array of {@link ImsCallForwardInfo}s that are associated with this supplementary
         * service data.
         */
        public @NonNull Builder setCallForwardingInfo(
                @NonNull ImsCallForwardInfo[] imsCallForwardInfos) {
            mImsSsData.mCfInfo = imsCallForwardInfos;
            return this;
        }

        /**
         * @return an {@link ImsSsData} containing optional parameters.
         */
        public @NonNull ImsSsData build() {
            return mImsSsData;
        }
    }

    /**
     * Generate IMS Supplementary Service information.
     * @param serviceType The Supplementary Service type.
     * @param requestType Supplementary Service request Type. Valid values are:
     *     {@link #SS_ACTIVATION},
     *     {@link #SS_DEACTIVATION},
     *     {@link #SS_INTERROGATION},
     *     {@link #SS_REGISTRATION},
     *     {@link #SS_ERASURE}
     * @param teleserviceType Supplementary Service teleservice type:
     *     {@link #SS_ALL_TELE_AND_BEARER_SERVICES},
     *     {@link #SS_ALL_TELESEVICES},
     *     {@link #SS_TELEPHONY},
     *     {@link #SS_ALL_DATA_TELESERVICES},
     *     {@link #SS_SMS_SERVICES},
     *     {@link #SS_ALL_TELESERVICES_EXCEPT_SMS}
     * @param serviceClass Supplementary Service service class. See See 27.007 +CCFC or +CLCK.
     * @param result Result of Supplementary Service operation. Valid values are 0 if the result is
     *               success, or ImsReasonInfo code if the result is a failure.
     */
    public ImsSsData(@ServiceType int serviceType, int requestType, int teleserviceType,
            @ServiceClassFlags int serviceClass, int result) {
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
        mImsSsInfo = (ImsSsInfo[])in.readParcelableArray(this.getClass().getClassLoader());
    }

    public static final @android.annotation.NonNull Creator<ImsSsData> CREATOR = new Creator<ImsSsData>() {
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
        out.writeInt(getServiceType());
        out.writeInt(getRequestType());
        out.writeInt(getTeleserviceType());
        out.writeInt(getServiceClass());
        out.writeInt(getResult());
        out.writeIntArray(mSsInfo);
        out.writeParcelableArray(mCfInfo, 0);
        out.writeParcelableArray(mImsSsInfo, 0);
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
        return (getServiceType() == SS_CFU || getServiceType() == SS_CF_BUSY
                || getServiceType() == SS_CF_NO_REPLY || getServiceType() == SS_CF_NOT_REACHABLE
                || getServiceType() == SS_CF_ALL || getServiceType() == SS_CF_ALL_CONDITIONAL);
    }

    public boolean isTypeCf() {
        return isTypeCF();
    }

    public boolean isTypeUnConditional() {
        return (getServiceType() == SS_CFU || getServiceType() == SS_CF_ALL);
    }

    /**
     * Old method, kept for compatibility. See {@link #isTypeCf()}
     * @hide
     */
    public boolean isTypeCW() {
        return (getServiceType() == SS_WAIT);
    }

    public boolean isTypeCw() {
        return isTypeCW();
    }

    public boolean isTypeClip() {
        return (getServiceType() == SS_CLIP);
    }

    public boolean isTypeColr() {
        return (getServiceType() == SS_COLR);
    }

    public boolean isTypeColp() {
        return (getServiceType() == SS_COLP);
    }

    public boolean isTypeClir() {
        return (getServiceType() == SS_CLIR);
    }

    public boolean isTypeIcb() {
        return (getServiceType() == SS_INCOMING_BARRING_DN
                || getServiceType() == SS_INCOMING_BARRING_ANONYMOUS);
    }

    public boolean isTypeBarring() {
        return (getServiceType() == SS_BAOC || getServiceType() == SS_BAOIC
                || getServiceType() == SS_BAOIC_EXC_HOME || getServiceType() == SS_BAIC
                || getServiceType() == SS_BAIC_ROAMING || getServiceType() == SS_ALL_BARRING
                || getServiceType() == SS_OUTGOING_BARRING
                || getServiceType() == SS_INCOMING_BARRING);
    }

    public boolean isTypeInterrogation() {
        return (getServiceType() == SS_INTERROGATION);
    }

    /**
     * Supplementary Service request Type.
     */
    public @RequestType int getRequestType() {
        return requestType;
    }

    /**
     * The Service type of this Supplementary service.
     */
    public @ServiceType int getServiceType() {
        return serviceType;
    }

    /**
     * Supplementary Service teleservice type.
     */
    public @TeleserviceType int getTeleserviceType() {
        return teleserviceType;
    }

    /**
     * Supplementary Service service class.
     */
    public @ServiceClassFlags int getServiceClass() {
        return serviceClass;
    }

    /**
     * Result of Supplementary Service operation. Valid values are:
     *     {@link #RESULT_SUCCESS} if the result is success, or
     *     {@link ImsReasonInfo.UtReason} code if the result is a failure.
     */
    public @ImsReasonInfo.UtReason int getResult() {
        return result;
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
     * This is a compatibility function to transform the public API to a form that can be processed
     * by telephony.
     *
     * @hide
     */
    //TODO: Refactor Telephony to use well defined classes instead of an int[] to process SS.
    public int[] getSuppServiceInfoCompat() {
        if (mSsInfo != null) {
            // Something has set the ssInfo using hidden APIs, so for compatibility just return that
            // structure directly.
            return mSsInfo;
        }


        int[] result = new int[2];
        if (mImsSsInfo == null || mImsSsInfo.length == 0) {
            Rlog.e(TAG, "getSuppServiceInfoCompat: Could not parse mImsSsInfo, returning empty "
                    + "int[]");
            return result;
        }

        // Convert ImsSsInfo into a form that telephony can read (as per 3GPP 27.007)
        // CLIR (section 7.7)
        if (isTypeClir()) {
            // Assume there will only be one ImsSsInfo.
            // contains {"n","m"} parameters
            result[0] = mImsSsInfo[0].getClirOutgoingState();
            result[1] = mImsSsInfo[0].getClirInterrogationStatus();
            return result;
        }
        // COLR 7.31
        if (isTypeColr()) {
            result[0] = mImsSsInfo[0].getProvisionStatus();
        }
        // Facility Lock CLCK 7.4 (for call barring), CLIP 7.6, COLP 7.8, as well as any
        // other result, just return the status for the "n" parameter and provisioning status for
        // "m" as the default.
        result[0] = mImsSsInfo[0].getStatus();
        result[1] = mImsSsInfo[0].getProvisionStatus();
        return result;
    }

    /**
     * @return an array of {@link ImsSsInfo}s associated with this supplementary service data.
     */
    public @NonNull ImsSsInfo[] getSuppServiceInfo() {
        return mImsSsInfo;
    }

    /**
     * @return an array of {@link ImsCallForwardInfo}s associated with this supplementary service
     * data.
     **/
    public ImsCallForwardInfo[] getCallForwardInfo() {
        return mCfInfo;
    }

    public String toString() {
        return "[ImsSsData] " + "ServiceType: " + getServiceType()
            + " RequestType: " + getRequestType()
            + " TeleserviceType: " + getTeleserviceType()
            + " ServiceClass: " + getServiceClass()
            + " Result: " + getResult();
    }
}
