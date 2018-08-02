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

import android.annotation.UnsupportedAppUsage;
import com.android.ims.internal.uce.common.CapInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/** @hide  */
public class OptionsCapInfo implements Parcelable {

    private String mSdp = "";  //  SDP message body. It is client responsibility.
    private CapInfo mCapInfo;

    public static OptionsCapInfo getOptionsCapInfoInstance() {
        return new OptionsCapInfo();
    }

    @UnsupportedAppUsage
    public String getSdp() {
        return mSdp;
    }

    @UnsupportedAppUsage
    public void setSdp(String sdp) {
        this.mSdp = sdp;
    }

    /**
     * Constructor for the OptionsCapInfo class.
     */
    @UnsupportedAppUsage
    public OptionsCapInfo() {
        mCapInfo = new CapInfo();
    };

    @UnsupportedAppUsage
    public CapInfo getCapInfo() {
        return mCapInfo;
    }
    /**
     * Sets the CapInfo
     */
    @UnsupportedAppUsage
    public void setCapInfo(CapInfo capInfo) {
        this.mCapInfo = capInfo;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mSdp);
        dest.writeParcelable(mCapInfo, flags);
    }

    public static final Parcelable.Creator<OptionsCapInfo> CREATOR =
                                new Parcelable.Creator<OptionsCapInfo>() {

        public OptionsCapInfo createFromParcel(Parcel source) {
            return new OptionsCapInfo(source);
        }

        public OptionsCapInfo[] newArray(int size) {
            return new OptionsCapInfo[size];
        }
    };

    private OptionsCapInfo(Parcel source) {
        readFromParcel(source);
    }

    public void readFromParcel(Parcel source) {
        mSdp = source.readString();
        mCapInfo = source.readParcelable(CapInfo.class.getClassLoader());
    }
}