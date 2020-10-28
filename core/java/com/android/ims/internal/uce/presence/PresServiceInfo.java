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

/** @hide  */
public class PresServiceInfo implements Parcelable {

    /** Presence Service Information
     *  @hide
     */

    /** No media capability. */
    public static final int UCE_PRES_MEDIA_CAP_NONE = 0;
    /** Full duplex audio only. */
    public static final int UCE_PRES_MEDIA_CAP_FULL_AUDIO_ONLY = 1;
    /** Full duplex audio and video. */
    public static final int UCE_PRES_MEDIA_CAP_FULL_AUDIO_AND_VIDEO = 2;
    /** Media cap is unknown. */
    public static final int UCE_PRES_MEDIA_CAP_UNKNOWN = 3;


    private int mMediaCap = UCE_PRES_MEDIA_CAP_NONE;
    private String mServiceID = "";
    private String mServiceDesc = "";
    private String mServiceVer = "";

    /**
     * Gets the media type.
     * @hide
     */
    @UnsupportedAppUsage
    public int getMediaType() {
        return mMediaCap;
    }

    /**
     * Sets the media type.
     * @hide
     */
    public void setMediaType(int nMediaCap) {
        this.mMediaCap = nMediaCap;
    }

    /**
     * Gets the service ID.
     * @hide
     */
    @UnsupportedAppUsage
    public String getServiceId() {
        return mServiceID;
    }

    /**
     * Sets the service ID.
     * @hide
     */
    public void setServiceId(String serviceID) {
        this.mServiceID = serviceID;
    }
    /**
     * Gets the service description.
     * @hide
     */
    @UnsupportedAppUsage
    public String getServiceDesc() {
        return mServiceDesc;
    }

    /**
     * Sets the service description.
     * @hide
     */
    public void setServiceDesc(String serviceDesc) {
        this.mServiceDesc = serviceDesc;
    }

    /**
     * Gets the service version.
     * @hide
     */
    @UnsupportedAppUsage
    public String getServiceVer() {
        return mServiceVer;
    }

    /**
     * Sets the service version.
     * @hide
     */
    public void setServiceVer(String serviceVer) {
        this.mServiceVer = serviceVer;
    }

    /**
     * Constructor for the PresServiceInfo class.
     * @hide
     */
    public PresServiceInfo() {};


    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mServiceID);
        dest.writeString(mServiceDesc);
        dest.writeString(mServiceVer);
        dest.writeInt(mMediaCap);
    }

    /** @hide */
    public static final Parcelable.Creator<PresServiceInfo> CREATOR =
                                new Parcelable.Creator<PresServiceInfo>() {

        public PresServiceInfo createFromParcel(Parcel source) {
            return new PresServiceInfo(source);
        }

        public PresServiceInfo[] newArray(int size) {
            return new PresServiceInfo[size];
        }
    };

    /** @hide */
    private PresServiceInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mServiceID = source.readString();
        mServiceDesc = source.readString();
        mServiceVer = source.readString();
        mMediaCap = source.readInt();
    }
}