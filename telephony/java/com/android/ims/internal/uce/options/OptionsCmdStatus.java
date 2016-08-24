/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.options;

import com.android.ims.internal.uce.common.StatusCode;
import com.android.ims.internal.uce.common.CapInfo;

import android.os.Parcel;
import android.os.Parcelable;

/** @hide  */
public class OptionsCmdStatus implements Parcelable {

    private OptionsCmdId mCmdId;
    private StatusCode mStatus;
    private int mUserData;
    private CapInfo mCapInfo;

    /**
     * Gets the UCE command ID.
     * @hide
     */
    public OptionsCmdId getCmdId() {
        return mCmdId;
    }
    /**
     * Sets the command ID.
     * @hide
     */
    public void setCmdId(OptionsCmdId cmdId) {
        this.mCmdId = cmdId;
    }

    /**
     * Gets the user data.
     * @hide
     */
    public int getUserData() {
        return mUserData;
    }

    /**
       Sets the user data.
       @hide  */
    public void setUserData(int userData) {
        this.mUserData = userData;
    }

    /**
     * Gets the status code.
     * @hide
     */
    public StatusCode getStatus() {
        return mStatus;
    }

    /**
     * Sets the status code.
     * @hide
     */
    public void setStatus(StatusCode status) {
        this.mStatus = status;
    }

    /**
     * Constructor for the OptionsCmdStatus class.
     * @hide
     */
    public OptionsCmdStatus() {
        mStatus = new StatusCode();
        mCapInfo = new CapInfo();
        mCmdId = new OptionsCmdId();
        mUserData = 0;
    };

    /** @hide */
    public CapInfo getCapInfo() {
        return mCapInfo;
    }

    /**
     * Sets the CapInfo
     * @hide
     */
    public void setCapInfo(CapInfo capInfo) {
        this.mCapInfo = capInfo;
    }

    /**
     * Gets the instance of the OptionsCmdStatus class.
     * @hide
     */
    public static OptionsCmdStatus getOptionsCmdStatusInstance() {
        return new OptionsCmdStatus();
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserData);
        dest.writeParcelable(mCmdId, flags);
        dest.writeParcelable(mStatus, flags);
        dest.writeParcelable(mCapInfo, flags);
    }

    /** @hide */
    public static final Parcelable.Creator<OptionsCmdStatus> CREATOR =
                   new Parcelable.Creator<OptionsCmdStatus>() {
        public OptionsCmdStatus createFromParcel(Parcel source) {
            return new OptionsCmdStatus(source);
        }
        public OptionsCmdStatus[] newArray(int size) {
            return new OptionsCmdStatus[size];
        }
    };

    /** @hide */
    private OptionsCmdStatus(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mUserData = source.readInt();
        mCmdId = source.readParcelable(OptionsCmdId.class.getClassLoader());
        mStatus = source.readParcelable(StatusCode.class.getClassLoader());
        mCapInfo = source.readParcelable(CapInfo.class.getClassLoader());
    }
}