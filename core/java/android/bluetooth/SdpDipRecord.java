/*
* Copyright (C) 2015 Samsung System LSI
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
package android.bluetooth;

import java.util.Arrays;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data representation of a Object Push Profile Server side SDP record.
 */
/** @hide */
public class SdpDipRecord implements Parcelable {
    private final int mSpecificationId;
    private final int mVendorId;
    private final int mVendorIdSource;
    private final int mProductId;
    private final int mVersion;
    private final boolean mPrimaryRecord;

    public SdpDipRecord(int specificationId,
            int vendorId, int vendorIdSource,
            int productId, int version,
            boolean primaryRecord) {
        super();
        this.mSpecificationId = specificationId;
        this.mVendorId = vendorId;
        this.mVendorIdSource = vendorIdSource;
        this.mProductId = productId;
        this.mVersion = version;
        this.mPrimaryRecord = primaryRecord;
    }

    public SdpDipRecord(Parcel in) {
        this.mSpecificationId = in.readInt();
        this.mVendorId = in.readInt();
        this.mVendorIdSource = in.readInt();
        this.mProductId = in.readInt();
        this.mVersion = in.readInt();
        this.mPrimaryRecord = in.readBoolean();
    }

    public int getSpecificationId() {
        return mSpecificationId;
    }

    public int getVendorId() {
        return mVendorId;
    }

    public int getVendorIdSource() {
        return mVendorIdSource;
    }

    public int getProductId() {
        return mProductId;
    }

    public int getVersion() {
        return mVersion;
    }

    public boolean getPrimaryRecord() {
        return mPrimaryRecord;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSpecificationId);
        dest.writeInt(mVendorId);
        dest.writeInt(mVendorIdSource);
        dest.writeInt(mProductId);
        dest.writeInt(mVersion);
        dest.writeBoolean(mPrimaryRecord);
    }

    @Override
    public int describeContents() {
        /* No special objects */
        return 0;
    }

    public static  final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public SdpDipRecord createFromParcel(Parcel in) {
            return new SdpDipRecord(in);
        }
        public SdpDipRecord[] newArray(int size) {
            return new SdpDipRecord[size];
        }
    };
}
