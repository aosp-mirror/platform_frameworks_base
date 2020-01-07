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


import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class OptionsCmdId implements Parcelable {

    /** UCE CD command ID types  */

    /** Command ID corresponding to API GetMyInfo(). */
    public static final int UCE_OPTIONS_CMD_GETMYCDINFO = 0;
    /** Command ID corresponding to API SetMyInfo(). */
    public static final int UCE_OPTIONS_CMD_SETMYCDINFO = 1;
    /** Command ID corresponding to API GetContactCap(). */
    public static final int UCE_OPTIONS_CMD_GETCONTACTCAP = 2;
    /** Command ID corresponding to API GetContactListCap(). */
    public static final int UCE_OPTIONS_CMD_GETCONTACTLISTCAP = 3;
    /** Command ID corresponding to API ResponseIncomingOptions(). */
    public static final int UCE_OPTIONS_CMD_RESPONSEINCOMINGOPTIONS = 4;
    /** Command ID corresponding to API GetVersion(). */
    public static final int UCE_OPTIONS_CMD_GET_VERSION = 5;
    /** Default Command ID as Unknown. */
    public static final int UCE_OPTIONS_CMD_UNKNOWN = 6;


    private int mCmdId = UCE_OPTIONS_CMD_UNKNOWN;

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
     * Constructor for the OptionsCDCmdId class.
     * @hide
     */
    @UnsupportedAppUsage
    public OptionsCmdId(){};

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCmdId);
    }

    /** @hide */
    public static final Parcelable.Creator<OptionsCmdId> CREATOR =
                                  new Parcelable.Creator<OptionsCmdId>() {
        public OptionsCmdId createFromParcel(Parcel source) {
            return new OptionsCmdId(source);
        }

        public OptionsCmdId[] newArray(int size) {
            return new OptionsCmdId[size];
        }
    };

    /** @hide */
    private OptionsCmdId(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mCmdId = source.readInt();
    }
}
