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

/**
 * Data representation of a Object Push Profile Server side SDP record.
 */

/** @hide */
public class SdpOppOpsRecord implements Parcelable {

    private final String mServiceName;
    private final int mRfcommChannel;
    private final int mL2capPsm;
    private final int mProfileVersion;
    private final byte[] mFormatsList;

    public SdpOppOpsRecord(String serviceName, int rfcommChannel,
            int l2capPsm, int version, byte[] formatsList) {
        super();
        mServiceName = serviceName;
        mRfcommChannel = rfcommChannel;
        mL2capPsm = l2capPsm;
        mProfileVersion = version;
        mFormatsList = formatsList;
    }

    public String getServiceName() {
        return mServiceName;
    }

    public int getRfcommChannel() {
        return mRfcommChannel;
    }

    public int getL2capPsm() {
        return mL2capPsm;
    }

    public int getProfileVersion() {
        return mProfileVersion;
    }

    public byte[] getFormatsList() {
        return mFormatsList;
    }

    @Override
    public int describeContents() {
        /* No special objects */
        return 0;
    }

    public SdpOppOpsRecord(Parcel in) {
        mRfcommChannel = in.readInt();
        mL2capPsm = in.readInt();
        mProfileVersion = in.readInt();
        mServiceName = in.readString();
        int arrayLength = in.readInt();
        if (arrayLength > 0) {
            byte[] bytes = new byte[arrayLength];
            in.readByteArray(bytes);
            mFormatsList = bytes;
        } else {
            mFormatsList = null;
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRfcommChannel);
        dest.writeInt(mL2capPsm);
        dest.writeInt(mProfileVersion);
        dest.writeString(mServiceName);
        if (mFormatsList != null && mFormatsList.length > 0) {
            dest.writeInt(mFormatsList.length);
            dest.writeByteArray(mFormatsList);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Bluetooth OPP Server SDP Record:\n");
        sb.append("  RFCOMM Chan Number: ").append(mRfcommChannel);
        sb.append("\n  L2CAP PSM: ").append(mL2capPsm);
        sb.append("\n  Profile version: ").append(mProfileVersion);
        sb.append("\n  Service Name: ").append(mServiceName);
        sb.append("\n  Formats List: ").append(Arrays.toString(mFormatsList));
        return sb.toString();
    }

    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public SdpOppOpsRecord createFromParcel(Parcel in) {
            return new SdpOppOpsRecord(in);
        }

        public SdpOppOpsRecord[] newArray(int size) {
            return new SdpOppOpsRecord[size];
        }
    };

}
