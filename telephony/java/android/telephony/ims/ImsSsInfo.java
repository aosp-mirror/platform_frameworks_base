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

    // 0: disabled, 1: enabled
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter!
    public int mStatus;
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter!
    public String mIcbNum;

    /**@hide*/
    // TODO: Remove! Do not use this constructor, instead use public version.
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
    }

    @Override
    public String toString() {
        return super.toString() + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled");
    }

    private void readFromParcel(Parcel in) {
        mStatus = in.readInt();
        mIcbNum = in.readString();
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
}
