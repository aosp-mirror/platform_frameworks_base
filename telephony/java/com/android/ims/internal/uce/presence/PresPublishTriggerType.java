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

import android.os.Parcel;
import android.os.Parcelable;

/** @hide  */
public class PresPublishTriggerType implements Parcelable {

    /** Publish Trigger Indication Definitions
     *  @hide
     */

    /** ETag expired. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_ETAG_EXPIRED = 0;
    /** Move to LTE with VoPS disabled. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_DISABLED = 1;
    /** Move to LTE with VoPS enabled. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_LTE_VOPS_ENABLED = 2;
    /** Move to eHRPD. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_EHRPD = 3;
    /** Move to HSPA+. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_HSPAPLUS = 4;
    /** Move to 3G. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_3G = 5;
    /** Move to 2G. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_2G = 6;
    /** Move to WLAN */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_WLAN = 7;
    /** Move to IWLAN */
    public static final int UCE_PRES_PUBLISH_TRIGGER_MOVE_TO_IWLAN = 8;
    /** Trigger is unknown. */
    public static final int UCE_PRES_PUBLISH_TRIGGER_UNKNOWN = 9;




    private int mPublishTriggerType = UCE_PRES_PUBLISH_TRIGGER_UNKNOWN;


    /**
     * Gets the publish trigger types.
     * @hide
     */
    public int getPublishTrigeerType() {
        return mPublishTriggerType;
    }

    /**
     * Sets the publish trigger type.
     * @hide
     */
    public void setPublishTrigeerType(int nPublishTriggerType) {
        this.mPublishTriggerType = nPublishTriggerType;
    }


    /**
     * Constructor for the PresPublishTriggerType class.
     * @hide
     */
    public PresPublishTriggerType(){};

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPublishTriggerType);
    }

    /** @hide */
    public static final Parcelable.Creator<PresPublishTriggerType> CREATOR =
                               new Parcelable.Creator<PresPublishTriggerType>() {

        public PresPublishTriggerType createFromParcel(Parcel source) {

            return new PresPublishTriggerType(source);
        }

        public PresPublishTriggerType[] newArray(int size) {

            return new PresPublishTriggerType[size];
        }
    };

    /** @hide */
    private PresPublishTriggerType(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mPublishTriggerType = source.readInt();
    }
}