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

package com.android.ims.internal.uce.presence;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ims.internal.uce.common.StatusCode;


/** @hide  */
public class PresCmdStatus implements Parcelable{

    private PresCmdId mCmdId = new PresCmdId();
    private StatusCode mStatus = new StatusCode();
    private int mUserData;
    private int mRequestId;

    /**
     * Gets the Presence command ID.
     * @hide
     */
    public PresCmdId getCmdId() {
        return mCmdId;
    }

    /**
     * Sets the command ID.
     * @hide
     */
    @UnsupportedAppUsage
    public void setCmdId(PresCmdId cmdId) {
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
     * Sets the user data.
     * @hide
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public void setStatus(StatusCode status) {
        this.mStatus = status;
    }

    /**
     * Gets the request ID.
     * @hide
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Sets the request ID.
     * @hide
     */
    @UnsupportedAppUsage
    public void setRequestId(int requestId) {
        this.mRequestId = requestId;
    }

    /**
     * Constructor for the PresCmdStatus class.
     * @hide
     */
    @UnsupportedAppUsage
    public PresCmdStatus() {
        mStatus = new StatusCode();
    };

    /** @hide */
    public int describeContents() {

        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserData);
        dest.writeInt(mRequestId);
        dest.writeParcelable(mCmdId, flags);
        dest.writeParcelable(mStatus, flags);
    }

    /** @hide */
    public static final Parcelable.Creator<PresCmdStatus> CREATOR =
                                new Parcelable.Creator<PresCmdStatus>() {

        public PresCmdStatus createFromParcel(Parcel source) {

            return new PresCmdStatus(source);
        }

        public PresCmdStatus[] newArray(int size) {

            return new PresCmdStatus[size];
        }
    };

    /** @hide */
    private PresCmdStatus(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mUserData = source.readInt();
        mRequestId = source.readInt();
        mCmdId = source.readParcelable(PresCmdId.class.getClassLoader());
        mStatus = source.readParcelable(StatusCode.class.getClassLoader());
    }

}