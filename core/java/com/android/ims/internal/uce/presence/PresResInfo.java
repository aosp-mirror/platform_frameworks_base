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

/** @hide  */
public class PresResInfo implements Parcelable {

    private String mResUri = "";
    private String mDisplayName = "";
    private PresResInstanceInfo mInstanceInfo;

    /**
     * Gets the Presence service resource instance information.
     * @hide
     */
    public PresResInstanceInfo getInstanceInfo() {
        return mInstanceInfo;
    }

    /**
     * Sets the Presence service resource instance information.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setInstanceInfo(PresResInstanceInfo instanceInfo) {
        this.mInstanceInfo = instanceInfo;
    }

    /**
     * Gets the resource URI.
     * @hide
     */
    public String getResUri() {
        return mResUri;
    }

    /**
     * Sets the resource URI.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setResUri(String resUri) {
        this.mResUri = resUri;
    }

    /**
     * Gets the display name.
     * @hide
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Sets the display name.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setDisplayName(String displayName) {
        this.mDisplayName = displayName;
    }


   /**
    * Constructor for the PresResInstanceInfo class.
    * @hide
    */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public PresResInfo() {
        mInstanceInfo = new PresResInstanceInfo();
    };

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mResUri);
        dest.writeString(mDisplayName);
        dest.writeParcelable(mInstanceInfo, flags);
    }

    /** @hide */
    public static final Parcelable.Creator<PresResInfo> CREATOR =
                                     new Parcelable.Creator<PresResInfo>() {
        public PresResInfo createFromParcel(Parcel source) {
            return new PresResInfo(source);
        }

        public PresResInfo[] newArray(int size) {
            return new PresResInfo[size];
        }
    };

    /** @hide */
    private PresResInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mResUri = source.readString();
        mDisplayName = source.readString();
        mInstanceInfo = source.readParcelable(PresResInstanceInfo.class.getClassLoader(), com.android.ims.internal.uce.presence.PresResInstanceInfo.class);
    }
}