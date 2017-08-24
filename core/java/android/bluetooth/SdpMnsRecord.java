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

/** @hide */
public class SdpMnsRecord implements Parcelable {
    private final int mL2capPsm;
    private final int mRfcommChannelNumber;
    private final int mSupportedFeatures;
    private final int mProfileVersion;
    private final String mServiceName;

    public SdpMnsRecord(int l2capPsm,
            int rfcommChannelNumber,
            int profileVersion,
            int supportedFeatures,
            String serviceName) {
        mL2capPsm = l2capPsm;
        mRfcommChannelNumber = rfcommChannelNumber;
        mSupportedFeatures = supportedFeatures;
        mServiceName = serviceName;
        mProfileVersion = profileVersion;
    }

    public SdpMnsRecord(Parcel in) {
        mRfcommChannelNumber = in.readInt();
        mL2capPsm = in.readInt();
        mServiceName = in.readString();
        mSupportedFeatures = in.readInt();
        mProfileVersion = in.readInt();
    }

    @Override
    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }


    public int getL2capPsm() {
        return mL2capPsm;
    }

    public int getRfcommChannelNumber() {
        return mRfcommChannelNumber;
    }

    public int getSupportedFeatures() {
        return mSupportedFeatures;
    }

    public String getServiceName() {
        return mServiceName;
    }

    public int getProfileVersion() {
        return mProfileVersion;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRfcommChannelNumber);
        dest.writeInt(mL2capPsm);
        dest.writeString(mServiceName);
        dest.writeInt(mSupportedFeatures);
        dest.writeInt(mProfileVersion);
    }

    public String toString() {
        String ret = "Bluetooth MNS SDP Record:\n";

        if (mRfcommChannelNumber != -1) {
            ret += "RFCOMM Chan Number: " + mRfcommChannelNumber + "\n";
        }
        if (mL2capPsm != -1) {
            ret += "L2CAP PSM: " + mL2capPsm + "\n";
        }
        if (mServiceName != null) {
            ret += "Service Name: " + mServiceName + "\n";
        }
        if (mSupportedFeatures != -1) {
            ret += "Supported features: " + mSupportedFeatures + "\n";
        }
        if (mProfileVersion != -1) {
            ret += "Profile_version: " + mProfileVersion + "\n";
        }
        return ret;
    }

    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public SdpMnsRecord createFromParcel(Parcel in) {
            return new SdpMnsRecord(in);
        }

        public SdpMnsRecord[] newArray(int size) {
            return new SdpMnsRecord[size];
        }
    };
}
