/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * Data connection real time information
 *
 * TODO: How to handle multiple subscriptions?
 * @hide
 */
public class DataConnectionRealTimeInfo implements Parcelable {
    private long mTime;             // Time the info was collected since boot in nanos;

    public static final int DC_POWER_STATE_LOW       = 1;
    public static final int DC_POWER_STATE_MEDIUM    = 2;
    public static final int DC_POWER_STATE_HIGH      = 3;
    public static final int DC_POWER_STATE_UNKNOWN   = Integer.MAX_VALUE;

    private int mDcPowerState;      // DC_POWER_STATE_[LOW | MEDIUM | HIGH | UNKNOWN]

    /**
     * Constructor
     *
     * @hide
     */
    public DataConnectionRealTimeInfo(long time, int dcPowerState) {
        mTime = time;
        mDcPowerState = dcPowerState;
    }

    /**
     * Constructor
     *
     * @hide
     */
    public DataConnectionRealTimeInfo() {
        mTime = Long.MAX_VALUE;
        mDcPowerState = DC_POWER_STATE_UNKNOWN;
    }

    /**
     * Construct a PreciseCallState object from the given parcel.
     */
    private DataConnectionRealTimeInfo(Parcel in) {
        mTime = in.readLong();
        mDcPowerState = in.readInt();
    }

    /**
     * @return time the information was collected or Long.MAX_VALUE if unknown
     */
    public long getTime() {
        return mTime;
    }

    /**
     * @return DC_POWER_STATE_[LOW | MEDIUM | HIGH | UNKNOWN]
     */
    public int getDcPowerState() {
        return mDcPowerState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mTime);
        out.writeInt(mDcPowerState);
    }

    public static final Parcelable.Creator<DataConnectionRealTimeInfo> CREATOR
            = new Parcelable.Creator<DataConnectionRealTimeInfo>() {

        @Override
        public DataConnectionRealTimeInfo createFromParcel(Parcel in) {
            return new DataConnectionRealTimeInfo(in);
        }

        @Override
        public DataConnectionRealTimeInfo[] newArray(int size) {
            return new DataConnectionRealTimeInfo[size];
        }
    };

    @Override
    public int hashCode() {
        final long prime = 17;
        long result = 1;
        result = (prime * result) + mTime;
        result += (prime * result) + mDcPowerState;
        return (int)result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DataConnectionRealTimeInfo other = (DataConnectionRealTimeInfo) obj;
        return (mTime == other.mTime)
                && (mDcPowerState == other.mDcPowerState);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("mTime=").append(mTime);
        sb.append(" mDcPowerState=").append(mDcPowerState);

        return sb.toString();
    }
}
