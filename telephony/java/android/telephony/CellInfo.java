/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * Immutable cell information from a point in time.
 */
public abstract class CellInfo implements Parcelable {

    // Type fields for parceling
    /** @hide */
    protected static final int TYPE_GSM = 1;
    /** @hide */
    protected static final int TYPE_CDMA = 2;
    /** @hide */
    protected static final int TYPE_LTE = 3;
    /** @hide */
    protected static final int TYPE_WCDMA = 4;

    // Type to distinguish where time stamp gets recorded.

    /** @hide */
    public static final int TIMESTAMP_TYPE_UNKNOWN = 0;
    /** @hide */
    public static final int TIMESTAMP_TYPE_ANTENNA = 1;
    /** @hide */
    public static final int TIMESTAMP_TYPE_MODEM = 2;
    /** @hide */
    public static final int TIMESTAMP_TYPE_OEM_RIL = 3;
    /** @hide */
    public static final int TIMESTAMP_TYPE_JAVA_RIL = 4;

    // True if device is mRegistered to the mobile network
    private boolean mRegistered;

    // Observation time stamped as type in nanoseconds since boot
    private long mTimeStamp;

    // Where time stamp gets recorded.
    // Value of TIMESTAMP_TYPE_XXXX
    private int mTimeStampType;

    /** @hide */
    protected CellInfo() {
        this.mRegistered = false;
        this.mTimeStampType = TIMESTAMP_TYPE_UNKNOWN;
        this.mTimeStamp = Long.MAX_VALUE;
    }

    /** @hide */
    protected CellInfo(CellInfo ci) {
        this.mRegistered = ci.mRegistered;
        this.mTimeStampType = ci.mTimeStampType;
        this.mTimeStamp = ci.mTimeStamp;
    }

    /** True if this cell is registered to the mobile network */
    public boolean isRegistered() {
        return mRegistered;
    }
    /** @hide */
    public void setRegistered(boolean registered) {
        mRegistered = registered;
    }

    /** Approximate time of this cell information in nanos since boot */
    public long getTimeStamp() {
        return mTimeStamp;
    }
    /** @hide */
    public void setTimeStamp(long timeStamp) {
        mTimeStamp = timeStamp;
    }

    /**
     * Where time stamp gets recorded.
     * @return one of TIMESTAMP_TYPE_XXXX
     *
     * @hide
     */
    public int getTimeStampType() {
        return mTimeStampType;
    }
    /** @hide */
    public void setTimeStampType(int timeStampType) {
        if (timeStampType < TIMESTAMP_TYPE_UNKNOWN || timeStampType > TIMESTAMP_TYPE_JAVA_RIL) {
            mTimeStampType = TIMESTAMP_TYPE_UNKNOWN;
        } else {
            mTimeStampType = timeStampType;
        }
    }

    @Override
    public int hashCode() {
        int primeNum = 31;
        return ((mRegistered ? 0 : 1) * primeNum) + ((int)(mTimeStamp / 1000) * primeNum)
                + (mTimeStampType * primeNum);
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if (this == other) {
            return true;
        }
        try {
            CellInfo o = (CellInfo) other;
            return mRegistered == o.mRegistered
                    && mTimeStamp == o.mTimeStamp && mTimeStampType == o.mTimeStampType;
        } catch (ClassCastException e) {
            return false;
        }
    }

    private static String timeStampTypeToString(int type) {
        switch (type) {
            case 1:
                return "antenna";
            case 2:
                return "modem";
            case 3:
                return "oem_ril";
            case 4:
                return "java_ril";
            default:
                return "unknown";
        }
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String timeStampType;

        sb.append("mRegistered=").append(mRegistered ? "YES" : "NO");
        timeStampType = timeStampTypeToString(mTimeStampType);
        sb.append(" mTimeStampType=").append(timeStampType);
        sb.append(" mTimeStamp=").append(mTimeStamp).append("ns");

        return sb.toString();
    }

    /**
     * Implement the Parcelable interface
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public abstract void writeToParcel(Parcel dest, int flags);

    /**
     * Used by child classes for parceling.
     *
     * @hide
     */
    protected void writeToParcel(Parcel dest, int flags, int type) {
        dest.writeInt(type);
        dest.writeInt(mRegistered ? 1 : 0);
        dest.writeInt(mTimeStampType);
        dest.writeLong(mTimeStamp);
    }

    /**
     * Used by child classes for parceling
     *
     * @hide
     */
    protected CellInfo(Parcel in) {
        mRegistered = (in.readInt() == 1) ? true : false;
        mTimeStampType = in.readInt();
        mTimeStamp = in.readLong();
    }

    /** Implement the Parcelable interface */
    public static final Creator<CellInfo> CREATOR = new Creator<CellInfo>() {
        @Override
        public CellInfo createFromParcel(Parcel in) {
                int type = in.readInt();
                switch (type) {
                    case TYPE_GSM: return CellInfoGsm.createFromParcelBody(in);
                    case TYPE_CDMA: return CellInfoCdma.createFromParcelBody(in);
                    case TYPE_LTE: return CellInfoLte.createFromParcelBody(in);
                    case TYPE_WCDMA: return CellInfoWcdma.createFromParcelBody(in);
                    default: throw new RuntimeException("Bad CellInfo Parcel");
                }
        }

        @Override
        public CellInfo[] newArray(int size) {
            return new CellInfo[size];
        }
    };
}
