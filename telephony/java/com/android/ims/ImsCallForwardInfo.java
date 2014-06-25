/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Provides the call forward information for the supplementary service configuration.
 *
 * @hide
 */
public class ImsCallForwardInfo implements Parcelable {
    // Refer to ImsUtInterface#CDIV_CF_XXX
    public int mCondition;
    // 0: disabled, 1: enabled
    public int mStatus;
    // 0x91: International, 0x81: Unknown
    public int mToA;
    // Number (it will not include the "sip" or "tel" URI scheme)
    public String mNumber;
    // No reply timer for CF
    public int mTimeSeconds;

    public ImsCallForwardInfo() {
    }

    public ImsCallForwardInfo(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCondition);
        out.writeInt(mStatus);
        out.writeInt(mToA);
        out.writeString(mNumber);
        out.writeInt(mTimeSeconds);
    }

    @Override
    public String toString() {
        return super.toString() + ", Condition: " + mCondition
            + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled")
            + ", ToA: " + mToA + ", Number=" + mNumber
            + ", Time (seconds): " + mTimeSeconds;
    }

    private void readFromParcel(Parcel in) {
        mCondition = in.readInt();
        mStatus = in.readInt();
        mToA = in.readInt();
        mNumber = in.readString();
        mTimeSeconds = in.readInt();
    }

    public static final Creator<ImsCallForwardInfo> CREATOR =
            new Creator<ImsCallForwardInfo>() {
        @Override
        public ImsCallForwardInfo createFromParcel(Parcel in) {
            return new ImsCallForwardInfo(in);
        }

        @Override
        public ImsCallForwardInfo[] newArray(int size) {
            return new ImsCallForwardInfo[size];
        }
    };
}
