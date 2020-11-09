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

package com.android.ims.internal.uce.common;

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;


/** Simple object wrapper for a long type.
 *  @hide */
public class UceLong implements Parcelable {

    private long mUceLong;
    private int mClientId = 1001;

    /**
     * Constructor for the UceLong class.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public UceLong() {
    };

    /**
     * Gets the long value.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getUceLong() {
        return mUceLong;
    }

    /**
     * Sets the long value.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setUceLong(long uceLong) {
        this.mUceLong = uceLong;
    }

    /** Get the client ID as integer value.
     *  @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getClientId() {
        return mClientId;
    }

    /**
     * Set the client ID as integer value.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setClientId(int nClientId) {
        this.mClientId = nClientId;
    }


    /**
     * Gets the instance of a UceLong class.
     * @hide
     */
    public static UceLong getUceLongInstance() {
        return new UceLong();
    }

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcel(dest);

    }

    /** @hide */
    private void writeToParcel(Parcel out) {
        out.writeLong(mUceLong);
        out.writeInt(mClientId);
    }

    /** @hide */
    public static final Parcelable.Creator<UceLong> CREATOR =
                                    new Parcelable.Creator<UceLong>() {

        public UceLong createFromParcel(Parcel source) {
            return new UceLong(source);
        }

        public UceLong[] newArray(int size) {
            return new UceLong[size];
        }
    };

    /** @hide */
    private UceLong(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mUceLong = source.readLong();
        mClientId = source.readInt();
    }
}
