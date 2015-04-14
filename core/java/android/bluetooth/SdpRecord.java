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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/** @hide */
public class SdpRecord implements Parcelable{

    private final byte[] mRawData;
    private final int mRawSize;

    @Override
    public String toString() {
        return "BluetoothSdpRecord [rawData=" + Arrays.toString(mRawData)
                + ", rawSize=" + mRawSize + "]";
    }

    public SdpRecord(int size_record, byte[] record){
        this.mRawData = record;
        this.mRawSize = size_record;
    }

    public SdpRecord(Parcel in){
        this.mRawSize = in.readInt();
        this.mRawData = new byte[mRawSize];
        in.readByteArray(this.mRawData);

    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.mRawSize);
        dest.writeByteArray(this.mRawData);


    }
    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public SdpRecord createFromParcel(Parcel in) {
            return new SdpRecord(in);
        }

        public SdpRecord[] newArray(int size) {
            return new SdpRecord[size];
        }
    };

    public byte[] getRawData() {
        return mRawData;
    }

    public int getRawSize() {
        return mRawSize;
    }
}
