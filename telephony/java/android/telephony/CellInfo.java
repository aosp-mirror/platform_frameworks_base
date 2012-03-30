/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * Represent one snapshot observation of one cell info
 * which contains the time of observation.
 *
 * @hide Pending API review
 */
public final class CellInfo implements Parcelable {
    // Type to distinguish where time stamp gets recorded.
    public static final int CELL_INFO_TIMESTAMP_TYPE_UNKNOWN = 0;
    public static final int CELL_INFO_TIMESTAMP_TYPE_ANTENNA = 1;
    public static final int CELL_INFO_TIMESTAMP_TYPE_MODEM = 2;
    public static final int CELL_INFO_TIMESTAMP_TYPE_OEM_RIL = 3;
    public static final int CELL_INFO_TIMESTAMP_TYPE_JAVA_RIL = 4;

    // Observation time stamped as type in nanoseconds since boot
    private final long mTimeStamp;
    // Where time stamp gets recorded.
    // Value of CELL_INFO_TIMESTAMP_TYPE_XXXX
    private final int mTimeStampType;

    private final boolean mRegistered;

    private final SignalStrength mStrength;
    private final long mTimingAdvance;

    private final int mCellIdentityType;
    private final CellIdentity mCellIdentity;

    /**
     * Public constructor
     * @param timeStampType is one of CELL_INFO_TIMESTAMP_TYPE_XXXX
     * @param timeStamp is observation time in nanoseconds since boot
     * @param timingAdv is observed timing advance
     * @param registered is true when register to this cellIdentity
     * @param strength is observed signal strength
     * @param cellIdentity is observed mobile cell
     */
    public CellInfo(int timeStampType, long timeStamp, long timingAdv,
            boolean registered, SignalStrength strength,
            CellIdentity cellIdentity) {

        if (timeStampType < CELL_INFO_TIMESTAMP_TYPE_UNKNOWN ||
                timeStampType > CELL_INFO_TIMESTAMP_TYPE_JAVA_RIL) {
            mTimeStampType = CELL_INFO_TIMESTAMP_TYPE_UNKNOWN;
        } else {
            mTimeStampType = timeStampType;
        }

        mRegistered = registered;
        mTimeStamp = timeStamp;
        mTimingAdvance = timingAdv;
        mStrength = new SignalStrength(strength);

        mCellIdentityType = cellIdentity.getCellIdType();
        // TODO: make defense copy
        mCellIdentity = cellIdentity;
    }

    public CellInfo(CellInfo ci) {
        this.mTimeStampType = ci.mTimeStampType;
        this.mRegistered = ci.mRegistered;
        this.mTimeStamp = ci.mTimeStamp;
        this.mTimingAdvance = ci.mTimingAdvance;
        this.mCellIdentityType = ci.mCellIdentityType;
        this.mStrength = new SignalStrength(ci.mStrength);
        switch(mCellIdentityType) {
            case CellIdentity.CELLID_TYPE_GSM:
                mCellIdentity = new GsmCellIdentity((GsmCellIdentity)ci.mCellIdentity);
                break;
            default:
                mCellIdentity = null;
        }
    }

    private CellInfo(Parcel in) {
        mTimeStampType = in.readInt();
        mRegistered = (in.readInt() == 1) ? true : false;
        mTimeStamp = in.readLong();
        mTimingAdvance = in.readLong();
        mCellIdentityType = in.readInt();
        mStrength = SignalStrength.CREATOR.createFromParcel(in);
        switch(mCellIdentityType) {
            case CellIdentity.CELLID_TYPE_GSM:
                mCellIdentity = GsmCellIdentity.CREATOR.createFromParcel(in);
                break;
            default:
                mCellIdentity = null;
        }
    }

    /**
     * @return the observation time in nanoseconds since boot
     */
    public long getTimeStamp() {
        return mTimeStamp;
    }

    /**
     * @return Where time stamp gets recorded.
     * one of CELL_INFO_TIMESTAMP_TYPE_XXXX
     */
    public int getTimeStampType() {
        return mTimeStampType;
    }

    /**
     * @return true when register to this cellIdentity
     */
    public boolean isRegistered() {
        return mRegistered;
    }

    /**
     * @return observed timing advance
     */
    public long getTimingAdvance() {
        return mTimingAdvance;
    }

    /**
     * @return observed signal strength
     */
    public SignalStrength getSignalStrength() {
        // make a defense copy
        return new SignalStrength(mStrength);
    }

    /**
     * @return observed cell identity
     */
    public CellIdentity getCellIdentity() {
        // TODO: make a defense copy
        return mCellIdentity;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("TimeStampType: ");
        switch(mTimeStampType) {
            case 1:
                sb.append("antenna");
                break;
            case 2:
                sb.append("modem");
                break;
            case 3:
                sb.append("oem_ril");
                break;
            case 4:
                sb.append("java_ril");
                break;
            default:
                sb.append("unknown");
        }
        sb.append(", TimeStamp: ").append(mTimeStamp).append(" ns");
        sb.append(", Registered: ").append(mRegistered ? "YES" : "NO");
        sb.append(", TimingAdvance: ").append(mTimingAdvance);
        sb.append(", Strength : " + mStrength);
        sb.append(", Cell Iden: " + mCellIdentity);

        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTimeStampType);
        dest.writeInt(mRegistered ? 1 : 0);
        dest.writeLong(mTimeStamp);
        dest.writeLong(mTimingAdvance);
        dest.writeInt(mCellIdentityType);
        mStrength.writeToParcel(dest, flags);
        mCellIdentity.writeToParcel(dest, flags);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<CellInfo> CREATOR =
            new Creator<CellInfo>() {
        @Override
        public CellInfo createFromParcel(Parcel in) {
            return new CellInfo(in);
        }

        @Override
        public CellInfo[] newArray(int size) {
            return new CellInfo[size];
        }
    };
}
