/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A data class representing a set of options to configure batching sessions.
 * @hide
 */
public class FusedBatchOptions implements Parcelable {
    private volatile long mPeriodInNS = 0;
    private volatile int mSourcesToUse = 0;
    private volatile int mFlags = 0;

    // the default value is set to request fixes at no cost
    private volatile double mMaxPowerAllocationInMW = 0;

    /*
     * Getters and setters for properties needed to hold the options.
     */
    public void setMaxPowerAllocationInMW(double value) {
        mMaxPowerAllocationInMW = value;
    }

    public double getMaxPowerAllocationInMW() {
        return mMaxPowerAllocationInMW;
    }

    public void setPeriodInNS(long value) {
        mPeriodInNS = value;
    }

    public long getPeriodInNS() {
        return mPeriodInNS;
    }

    public void setSourceToUse(int source) {
        mSourcesToUse |= source;
    }

    public void resetSourceToUse(int source) {
        mSourcesToUse &= ~source;
    }

    public boolean isSourceToUseSet(int source) {
        return (mSourcesToUse & source) != 0;
    }

    public int getSourcesToUse() {
        return mSourcesToUse;
    }

    public void setFlag(int flag) {
        mFlags |= flag;
    }

    public void resetFlag(int flag) {
        mFlags &= ~flag;
    }

    public boolean isFlagSet(int flag) {
        return (mFlags & flag) != 0;
    }

    public int getFlags() {
        return mFlags;
    }

    /**
     * Definition of enum flag sets needed by this class.
     * Such values need to be kept in sync with the ones in fused_location.h
     */
    public static final class SourceTechnologies {
        public static int GNSS = 1<<0;
        public static int WIFI = 1<<1;
        public static int SENSORS = 1<<2;
        public static int CELL = 1<<3;
        public static int BLUETOOTH = 1<<4;
    }

    public static final class BatchFlags {
        // follow the definitions to the letter in fused_location.h
        public static int WAKEUP_ON_FIFO_FULL = 0x0000001;
        public static int CALLBACK_ON_LOCATION_FIX =0x0000002;
    }

    /*
     * Method definitions to support Parcelable operations.
     */
    public static final Parcelable.Creator<FusedBatchOptions> CREATOR =
            new Parcelable.Creator<FusedBatchOptions>() {
        @Override
        public FusedBatchOptions createFromParcel(Parcel parcel) {
            FusedBatchOptions options = new FusedBatchOptions();
            options.setMaxPowerAllocationInMW(parcel.readDouble());
            options.setPeriodInNS(parcel.readLong());
            options.setSourceToUse(parcel.readInt());
            options.setFlag(parcel.readInt());
            return options;
        }

        @Override
        public FusedBatchOptions[] newArray(int size) {
            return new FusedBatchOptions[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeDouble(mMaxPowerAllocationInMW);
        parcel.writeLong(mPeriodInNS);
        parcel.writeInt(mSourcesToUse);
        parcel.writeInt(mFlags);
    }
}
