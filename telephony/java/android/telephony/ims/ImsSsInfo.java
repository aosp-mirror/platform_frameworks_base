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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the result to the update operation for the supplementary service configuration.
 *
 * Also supports IMS specific Incoming Communication Barring (ICB) as well as Anonymous
 * Communication Rejection (ACR), as per 3GPP 24.611.
 *
 * @see Builder
 * @hide
 */
@SystemApi
public final class ImsSsInfo implements Parcelable {

    /**@hide*/
    @IntDef(value = {
            NOT_REGISTERED,
            DISABLED,
            ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceStatus {}

    /**
     * For the status of service registration or activation/deactivation.
     */
    public static final int NOT_REGISTERED = (-1);
    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    /**
     * Provision status of service.
     * @hide
     */
    @IntDef(value = {
            SERVICE_PROVISIONING_UNKNOWN,
            SERVICE_NOT_PROVISIONED,
            SERVICE_PROVISIONED
    }, prefix = "SERVICE_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceProvisionStatus {}

    /**
     * Unknown provision status for the service.
     */
    public static final int SERVICE_PROVISIONING_UNKNOWN = (-1);

    /**
     * Service is not provisioned.
     */
    public static final int SERVICE_NOT_PROVISIONED = 0;

    /**
     * Service is provisioned.
     */
    public static final int SERVICE_PROVISIONED = 1;

    /**@hide*/
    @IntDef(value = {
            CLIR_OUTGOING_DEFAULT,
            CLIR_OUTGOING_INVOCATION,
            CLIR_OUTGOING_SUPPRESSION
    }, prefix = "CLIR_OUTGOING_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClirOutgoingState {}

    /**
     * Calling line identification restriction (CLIR) is set to the default according to the
     * subscription of the CLIR service.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_OUTGOING_DEFAULT = 0;
    /**
     * Activate Calling line identification restriction for outgoing calls.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_OUTGOING_INVOCATION = 1;
    /**
     * Deactivate Calling line identification restriction for outgoing calls.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_OUTGOING_SUPPRESSION = 2;

    /**
     * Calling line identification restriction is currently not provisioned.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_STATUS_NOT_PROVISIONED = 0;
    /**
     * Calling line identification restriction is currently provisioned in permanent mode.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_STATUS_PROVISIONED_PERMANENT = 1;
    /**
     * Calling line identification restriction is currently unknown, e.g. no network, etc.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_STATUS_UNKNOWN = 2;
    /**
     * Calling line identification restriction temporary mode, temporarily restricted.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_STATUS_TEMPORARILY_RESTRICTED = 3;
    /**
     * Calling line identification restriction temporary mode, temporarily allowed.
     *
     * See TS 27.007, section 7.7 for more information.
     */
    public static final int CLIR_STATUS_TEMPORARILY_ALLOWED = 4;

    /**@hide*/
    @IntDef(value = {
            CLIR_STATUS_NOT_PROVISIONED,
            CLIR_STATUS_PROVISIONED_PERMANENT,
            CLIR_STATUS_UNKNOWN,
            CLIR_STATUS_TEMPORARILY_RESTRICTED,
            CLIR_STATUS_TEMPORARILY_ALLOWED
    }, prefix = "CLIR_STATUS_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClirInterrogationStatus {}

    // 0: disabled, 1: enabled
    /** @hide */
    @UnsupportedAppUsage
    public int mStatus;
    /** @hide */
    @UnsupportedAppUsage
    public String mIcbNum;
    /** @hide */
    public int mProvisionStatus = SERVICE_PROVISIONING_UNKNOWN;
    private int mClirInterrogationStatus = CLIR_STATUS_UNKNOWN;
    private int mClirOutgoingState = CLIR_OUTGOING_DEFAULT;

    /**@hide*/
    @UnsupportedAppUsage
    public ImsSsInfo() {
    }

    /**
     * Builds {@link ImsSsInfo} instances, which may include optional parameters.
     */
    public static final class Builder {

        private final ImsSsInfo mImsSsInfo;

        public Builder(@ServiceStatus int status) {
            mImsSsInfo = new ImsSsInfo();
            mImsSsInfo.mStatus = status;
        }

        /**
         * Set the ICB number for IMS call barring.
         * @param number The number in E.164 international format.
         */
        public @NonNull Builder setIncomingCommunicationBarringNumber(@NonNull String number) {
            mImsSsInfo.mIcbNum = number;
            return this;
        }

        /**
         * Set the provisioning status for a Supplementary Service interrogation response.
         */
        public @NonNull Builder setProvisionStatus(@ServiceProvisionStatus int provisionStatus) {
            mImsSsInfo.mProvisionStatus = provisionStatus;
            return this;
        }

        /**
         * Set the Calling Line Identification Restriction (CLIR) status for a supplementary service
         * interrogation response.
         */
        public @NonNull Builder setClirInterrogationStatus(@ClirInterrogationStatus int status) {
            mImsSsInfo.mClirInterrogationStatus = status;
            return this;
        }

        /**
         * Set the Calling line identification Restriction (CLIR) state for outgoing calls.
         */
        public @NonNull Builder setClirOutgoingState(@ClirOutgoingState int state) {
            mImsSsInfo.mClirOutgoingState = state;
            return this;
        }

        /**
         * @return a built {@link ImsSsInfo} containing optional the parameters that were set.
         */
        public @NonNull ImsSsInfo build() {
            return mImsSsInfo;
        }
    }

    /**
     *
     * @param status The status of the service registration of activation/deactiviation.
     * @param icbNum The Incoming barring number.
     * @deprecated use {@link ImsSsInfo.Builder} instead.
     */
    @Deprecated
    public ImsSsInfo(@ServiceStatus int status, @Nullable String icbNum) {
        mStatus = status;
        mIcbNum = icbNum;
    }

    private ImsSsInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mStatus);
        out.writeString(mIcbNum);
        out.writeInt(mProvisionStatus);
        out.writeInt(mClirInterrogationStatus);
        out.writeInt(mClirOutgoingState);
    }

    @Override
    public String toString() {
        return super.toString() + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled")
                + ", ProvisionStatus: " + provisionStatusToString(mProvisionStatus);
    }

    private static String provisionStatusToString(int pStatus) {
        switch (pStatus) {
            case SERVICE_NOT_PROVISIONED:
                return "Service not provisioned";
             case SERVICE_PROVISIONED:
                return "Service provisioned";
             default:
                return "Service provisioning unknown";
        }
    }

    private void readFromParcel(Parcel in) {
        mStatus = in.readInt();
        mIcbNum = in.readString();
        mProvisionStatus = in.readInt();
        mClirInterrogationStatus = in.readInt();
        mClirOutgoingState = in.readInt();
    }

    public static final @android.annotation.NonNull Creator<ImsSsInfo> CREATOR =
            new Creator<ImsSsInfo>() {
        @Override
        public ImsSsInfo createFromParcel(Parcel in) {
            return new ImsSsInfo(in);
        }

        @Override
        public ImsSsInfo[] newArray(int size) {
            return new ImsSsInfo[size];
        }
    };

    /**
     * @return Supplementary Service Configuration status.
     */
    public @ServiceStatus int getStatus() {
        return mStatus;
    }

    /** @deprecated Use {@link #getIncomingCommunicationBarringNumber()} instead.*/
    @Deprecated
    public String getIcbNum() {
        return mIcbNum;
    }

    /**
     * @return The Incoming Communication Barring (ICB) number.
     */
    public @Nullable String getIncomingCommunicationBarringNumber() {
        return mIcbNum;
    }

    /**
     * @return Supplementary Service Provision status.
     */
    public @ServiceProvisionStatus int getProvisionStatus() {
        return mProvisionStatus;
    }

    /**
     * @return the Calling Line Identification Restriction State for outgoing calls with respect to
     * this subscription. Will be {@link #CLIR_OUTGOING_DEFAULT} if not applicable to this SS info.
     */
    public @ClirOutgoingState int getClirOutgoingState() {
        return mClirOutgoingState;
    }

    /**
     * @return the calling line identification restriction provisioning status upon interrogation of
     * the service for this subscription. Will be {@link #CLIR_STATUS_UNKNOWN} if not applicable to
     * this SS info.
     */
    public @ClirInterrogationStatus int getClirInterrogationStatus() {
        return mClirInterrogationStatus;
    }
}
