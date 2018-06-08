/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Provides the call forward information for the supplementary service configuration.
 *
 * @hide
 */
@SystemApi
public final class ImsCallForwardInfo implements Parcelable {
    // Refer to ImsUtInterface#CDIV_CF_XXX
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public int mCondition;
    // 0: disabled, 1: enabled
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public int mStatus;
    // 0x91: International, 0x81: Unknown
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public int mToA;
    // Service class
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public int mServiceClass;
    // Number (it will not include the "sip" or "tel" URI scheme)
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public String mNumber;
    // No reply timer for CF
    /** @hide */
    // TODO: Make private, do not modify this field directly, use getter.
    public int mTimeSeconds;

    /** @hide */
    // TODO: Will be removed in the future, use public constructor instead.
    public ImsCallForwardInfo() {
    }

    /**
     * IMS Call Forward Information.
     */
    public ImsCallForwardInfo(int condition, int status, int toA, int serviceClass, String number,
            int replyTimerSec) {
        mCondition = condition;
        mStatus = status;
        mToA = toA;
        mServiceClass = serviceClass;
        mNumber = number;
        mTimeSeconds = replyTimerSec;
    }

    /** @hide */
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
        out.writeInt(mServiceClass);
    }

    @Override
    public String toString() {
        return super.toString() + ", Condition: " + mCondition
            + ", Status: " + ((mStatus == 0) ? "disabled" : "enabled")
            + ", ToA: " + mToA
            + ", Service Class: " + mServiceClass
            + ", Number=" + mNumber
            + ", Time (seconds): " + mTimeSeconds;
    }

    private void readFromParcel(Parcel in) {
        mCondition = in.readInt();
        mStatus = in.readInt();
        mToA = in.readInt();
        mNumber = in.readString();
        mTimeSeconds = in.readInt();
        mServiceClass = in.readInt();
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

    public int getCondition() {
        return mCondition;
    }

    public int getStatus() {
        return mStatus;
    }

    public int getToA() {
        return mToA;
    }

    public int getServiceClass() {
        return mServiceClass;
    }

    public String getNumber() {
        return mNumber;
    }

    public int getTimeSeconds() {
        return mTimeSeconds;
    }
}
