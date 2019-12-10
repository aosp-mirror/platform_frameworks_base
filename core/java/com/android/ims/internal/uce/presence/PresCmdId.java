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
public class PresCmdId implements Parcelable {

    /** Presence Command Status ID
     *  @hide */


    /** Command ID corresponding to function GetVersion(). */
    public static final int UCE_PRES_CMD_GET_VERSION = 0;
    /** Command ID corresponding to function Publish(). */
    public static final int UCE_PRES_CMD_PUBLISHMYCAP = 1;
    /** Command ID corresponding to function GetContactCap(). */
    public static final int UCE_PRES_CMD_GETCONTACTCAP = 2;
    /** Command ID corresponding to function GetContactListCap(). */
    public static final int UCE_PRES_CMD_GETCONTACTLISTCAP = 3;
    /** Command ID corresponding to function SetNewFeatureTag(). */
    public static final int UCE_PRES_CMD_SETNEWFEATURETAG = 4;
    /** Command ID corresponding to API ReenableService(). */
    public static final int UCE_PRES_CMD_REENABLE_SERVICE = 5;
    /** Command ID is unknown. */
    public static final int UCE_PRES_CMD_UNKNOWN = 6;


    private int mCmdId = UCE_PRES_CMD_UNKNOWN;


    /**
     * Gets the command ID.
     * @hide
     */
    public int getCmdId() {
        return mCmdId;
    }

    /**
     * Sets the command ID.
     * @hide
     */
    @UnsupportedAppUsage
    public void setCmdId(int nCmdId) {
        this.mCmdId = nCmdId;
    }


   /**
    * Constructor for the PresCmdId class.
    * @hide
    */
    @UnsupportedAppUsage
    public PresCmdId(){};


    /** @hide */
    public int describeContents() {

        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCmdId);
    }

    /** @hide */
    public static final Parcelable.Creator<PresCmdId> CREATOR =
                                  new Parcelable.Creator<PresCmdId>() {

        public PresCmdId createFromParcel(Parcel source) {

            return new PresCmdId(source);
        }

        public PresCmdId[] newArray(int size) {

            return new PresCmdId[size];
        }
    };

    /** @hide */
    private PresCmdId(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mCmdId = source.readInt();
    }
}