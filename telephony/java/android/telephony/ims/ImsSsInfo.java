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
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Provides the result to the update operation for the supplementary service configuration.
 *
 * @hide
 */
@SystemApi
public final class ImsSsInfo implements Parcelable {
    /**
     * For the status of service registration or activation/deactivation.
     */
    public static final int NOT_REGISTERED = (-1);
    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    /**
     * Provision status of service
     */
    /** @hide */
    @IntDef({
            SERVICE_PROVISIONING_UNKNOWN,
            SERVICE_NOT_PROVISIONED,
            SERVICE_PROVISIONED
    })
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

    // 0: disabled, 1: enabled
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter!
    @UnsupportedAppUsage
    public int mStatus;
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter!
    @UnsupportedAppUsage
    public String mIcbNum;
    /** @hide */
    public int mProvisionStatus = SERVICE_PROVISIONING_UNKNOWN;

    /**@hide*/
    // TODO: Remove! Do not use this constructor, instead use public version.
    @UnsupportedAppUsage
    public ImsSsInfo() {
    }

    /**
     *
     * @param status The status of the service registration of activation/deactiviation. Valid
     *    entries include:
     *    {@link #NOT_REGISTERED},
     *    {@link #DISABLED},
     *    {@link #ENABLED}
     * @param icbNum The Incoming barring number.
     */
    public ImsSsInfo(int status, String icbNum) {
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
    }

    public static final Creator<ImsSsInfo> CREATOR =
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
     * @return Supplementary Service Configuration status. Valid Values are:
     *     {@link #NOT_REGISTERED},
     *     {@link #DISABLED},
     *     {@link #ENABLED}
     */
    public int getStatus() {
        return mStatus;
    }

    public String getIcbNum() {
        return mIcbNum;
    }

    /**
     * @return Supplementary Service Provision status. Valid Values are:
     *     {@link #SERVICE_PROVISIONING_UNKNOWN},
     *     {@link #SERVICE_NOT_PROVISIONED},
     *     {@link #SERVICE_PROVISIONED}
     */
    @ServiceProvisionStatus
    public int getProvisionStatus() {
        return mProvisionStatus;
    }
}
