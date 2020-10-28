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

import java.util.Arrays;

/** @hide  */
public class PresResInstanceInfo implements Parcelable{

    /**
     * UCE resource instance state definitions.
     * @hide
     */

    /** Active state. */
    public static final int UCE_PRES_RES_INSTANCE_STATE_ACTIVE = 0;
    /** Pending state. */
    public static final int UCE_PRES_RES_INSTANCE_STATE_PENDING = 1;
    /** Terminated state. */
    public static final int UCE_PRES_RES_INSTANCE_STATE_TERMINATED = 2;
    /** Unknown state. */
    public static final int UCE_PRES_RES_INSTANCE_STATE_UNKNOWN = 3;
    /** Unknown instance. */
    public static final int UCE_PRES_RES_INSTANCE_UNKNOWN = 4;


    private int mResInstanceState;
    private String mId = "";
    private String mReason = "";
    private String mPresentityUri = "";
    private PresTupleInfo mTupleInfoArray[];


    /**
     * Gets the resource instance state.
     * @hide
     */
    public int getResInstanceState() {
        return mResInstanceState;
    }

    /**
     * Sets the resource instance state.
     * @hide
     */
    @UnsupportedAppUsage
    public void setResInstanceState(int nResInstanceState) {
        this.mResInstanceState = nResInstanceState;
    }

    /**
     * Gets the resource ID.
     * @hide
     */
    public String getResId() {
        return mId;
    }

    /**
     * Sets the resource ID.
     * @hide
     */
    @UnsupportedAppUsage
    public void setResId(String resourceId) {
        this.mId = resourceId;
    }

    /**
     * Gets the reason phrase associated with the SIP response
     * code.
     * @hide
     */
    public String getReason() {
        return mReason;
    }

    /**
     * Sets the reason phrase associated with the SIP response
     * code.
     * @hide
     */
    @UnsupportedAppUsage
    public void setReason(String reason) {
        this.mReason = reason;
    }

    /**
     * Gets the entity URI.
     * @hide
     */
    public String getPresentityUri() {
        return mPresentityUri;
    }

    /**
     * Sets the entity URI.
     * @hide
     */
    @UnsupportedAppUsage
    public void setPresentityUri(String presentityUri) {
        this.mPresentityUri = presentityUri;
    }

    /**
     * Gets the tuple information.
     * @hide
     */
    public PresTupleInfo[] getTupleInfo() {
        return mTupleInfoArray;
    }

    /**
     * Sets the tuple information.
     * @hide
     */
    @UnsupportedAppUsage
    public void setTupleInfo(PresTupleInfo[] tupleInfo) {
        this.mTupleInfoArray = new PresTupleInfo[tupleInfo.length];
        this.mTupleInfoArray = tupleInfo;
    }


   /**
    * Constructor for the PresResInstanceInfo class.
    * @hide
    */
    @UnsupportedAppUsage
    public PresResInstanceInfo(){

    };

    /** @hide */
    public int describeContents() {

        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mReason);
        dest.writeInt(mResInstanceState);
        dest.writeString(mPresentityUri);
        dest.writeParcelableArray(mTupleInfoArray, flags);
    }

    /** @hide */
    public static final Parcelable.Creator<PresResInstanceInfo> CREATOR =
                      new Parcelable.Creator<PresResInstanceInfo>() {

        public PresResInstanceInfo createFromParcel(Parcel source) {

            return new PresResInstanceInfo(source);
        }

        public PresResInstanceInfo[] newArray(int size) {

            return new PresResInstanceInfo[size];
        }
    };

    /** @hide */
    private PresResInstanceInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mId = source.readString();
        mReason = source.readString();
        mResInstanceState = source.readInt();
        mPresentityUri = source.readString();
        Parcelable[] tempParcelableArray = source.readParcelableArray(
                                    PresTupleInfo.class.getClassLoader());
        mTupleInfoArray = new PresTupleInfo[] {};
        if(tempParcelableArray != null) {
            mTupleInfoArray = Arrays.copyOf(tempParcelableArray, tempParcelableArray.length,
                                            PresTupleInfo[].class);
        }

    }
}