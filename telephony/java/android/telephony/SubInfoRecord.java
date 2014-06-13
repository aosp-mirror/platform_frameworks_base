/*
* Copyright (C) 2011-2014 MediaTek Inc.
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;

/**
 *  A parcelable holder class of byte[] for ISms aidl implementation
 *  @hide
 */

public class SubInfoRecord implements Parcelable {

    public long mSubId;
    public String mIccId;
    public int mSlotId;
    public String mDisplayName;
    public int mNameSource;
    public int mColor;
    public String mNumber;
    public int mDispalyNumberFormat;
    public int mDataRoaming;
    public int[] mSimIconRes;

    public SubInfoRecord() {
        this.mSubId = -1;
        this.mIccId = "";
        this.mSlotId = -1;
        this.mDisplayName = "";
        this.mNameSource = 0;
        this.mColor = 0;
        this.mNumber = "";
        this.mDispalyNumberFormat = 0;
        this.mDataRoaming = 0;
        this.mSimIconRes = new int[2];
    }


    public SubInfoRecord(long subId, String iccId, int slotId, String displayname, int nameSource,
            int mColor, String mNumber, int displayFormat, int roaming, int[] iconRes) {
        this.mSubId = subId;
        this.mIccId = iccId;
        this.mSlotId = slotId;
        this.mDisplayName = displayname;
        this.mNameSource = nameSource;
        this.mColor = mColor;
        this.mNumber = mNumber;
        this.mDispalyNumberFormat = displayFormat;
        this.mDataRoaming = roaming;
        this.mSimIconRes = iconRes;
    }

    public static final Parcelable.Creator<SubInfoRecord> CREATOR = new Parcelable.Creator<SubInfoRecord>() {
        public SubInfoRecord createFromParcel(Parcel source) {
            long mSubId = source.readLong();
            String mIccId = source.readString();
            int mSlotId = source.readInt();
            String mDisplayName = source.readString();
            int mNameSource = source.readInt();
            int mColor = source.readInt();
            String mNumber = source.readString();
            int mDispalyNumberFormat = source.readInt();
            int mDataRoaming = source.readInt();
            int[] iconRes = new int[2];
            source.readIntArray(iconRes);

            return new SubInfoRecord(mSubId, mIccId, mSlotId, mDisplayName, mNameSource, mColor, mNumber,
                mDispalyNumberFormat, mDataRoaming, iconRes);
        }

        public SubInfoRecord[] newArray(int size) {
            return new SubInfoRecord[size];
        }
    };

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mSubId);
        dest.writeString(mIccId);
        dest.writeInt(mSlotId);
        dest.writeString(mDisplayName);
        dest.writeInt(mNameSource);
        dest.writeInt(mColor);
        dest.writeString(mNumber);
        dest.writeInt(mDispalyNumberFormat);
        dest.writeInt(mDataRoaming);
        dest.writeIntArray(mSimIconRes);
    }

    public int describeContents() {
        return 0;
    }

}
