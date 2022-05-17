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
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ims.internal.uce.common.CapInfo;


/** @hide */
public class PresCapInfo implements Parcelable {

    private CapInfo mCapInfo;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private String mContactUri = "";

    /**
     * Gets the UCE capability information.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CapInfo getCapInfo() {
        return mCapInfo;
    }

    /** Sets the UCE Capability information.
     *  @hide
     */
    public void setCapInfo(CapInfo capInfo) {
        this.mCapInfo = capInfo;
    }


    /**
     *  Gets the contact URI.
     *  @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String getContactUri() {
        return mContactUri;
    }

    /**
     *  Sets the contact URI.
     *  @hide
     */
    public void setContactUri(String contactUri) {
        this.mContactUri = contactUri;
    }

    /**
     * Constructor for the PresCapInfo class.
     * @hide
     */
    public PresCapInfo() {
        mCapInfo = new CapInfo();
    };

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mContactUri);
        dest.writeParcelable(mCapInfo, flags);
    }

    /** @hide */
    public static final Parcelable.Creator<PresCapInfo> CREATOR =
                                    new Parcelable.Creator<PresCapInfo>() {

        public PresCapInfo createFromParcel(Parcel source) {
            return new PresCapInfo(source);
        }

        public PresCapInfo[] newArray(int size) {
            return new PresCapInfo[size];
        }
    };

    /** @hide */
    private PresCapInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mContactUri = source.readString();
        mCapInfo = source.readParcelable(CapInfo.class.getClassLoader(), com.android.ims.internal.uce.common.CapInfo.class);
    }
}
