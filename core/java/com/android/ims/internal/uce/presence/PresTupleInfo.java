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
public class PresTupleInfo implements Parcelable {

    private String mFeatureTag = "";
    private String mContactUri = "";
    private String mTimestamp = "";


    /**
     * Gets the feature tag.
     * @hide
     */
    public String getFeatureTag() {
        return mFeatureTag;
    }

    /**
     * Sets the feature tag.
     * @hide
     */
    @UnsupportedAppUsage
    public void setFeatureTag(String featureTag) {
        this.mFeatureTag = featureTag;
    }

    /**
     * Gets the contact URI.
     * @hide
     */
    public String getContactUri() {
        return mContactUri;
    }
    /**
     * Sets the contact URI.
     * @hide
     */
    @UnsupportedAppUsage
    public void setContactUri(String contactUri) {
        this.mContactUri = contactUri;
    }

    /**
     * Gets the timestamp.
     * @hide
     */
    public String getTimestamp() {
        return mTimestamp;
    }

    /**
     * Sets the timestamp.
     * @hide
     */
    @UnsupportedAppUsage
    public void setTimestamp(String timestamp) {
        this.mTimestamp = timestamp;
    }

    /**
     * Constructor for the PresTupleInfo class.
     * @hide
     */
    @UnsupportedAppUsage
    public PresTupleInfo(){};

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mFeatureTag);
        dest.writeString(mContactUri);
        dest.writeString(mTimestamp);
    }

    /** @hide */
    public static final Parcelable.Creator<PresTupleInfo> CREATOR =
                                      new Parcelable.Creator<PresTupleInfo>() {

        public PresTupleInfo createFromParcel(Parcel source) {
            return new PresTupleInfo(source);
        }

        public PresTupleInfo[] newArray(int size) {
            return new PresTupleInfo[size];
        }
    };

    /** @hide */
    private PresTupleInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mFeatureTag = source.readString();
        mContactUri = source.readString();
        mTimestamp = source.readString();
    }
}