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
public class SdpMasRecord implements Parcelable {
    private final int mMasInstanceId;
    private final int mL2capPsm;
    private final int mRfcommChannelNumber;
    private final int mProfileVersion;
    private final int mSupportedFeatures;
    private final int mSupportedMessageTypes;
    private final String mServiceName;

    /** Message type */
    public static final class MessageType {
        public static final int EMAIL = 0x01;
        public static final int SMS_GSM = 0x02;
        public static final int SMS_CDMA = 0x04;
        public static final int MMS = 0x08;
    }

    public SdpMasRecord(int masInstanceId,
            int l2capPsm,
            int rfcommChannelNumber,
            int profileVersion,
            int supportedFeatures,
            int supportedMessageTypes,
            String serviceName) {
        mMasInstanceId = masInstanceId;
        mL2capPsm = l2capPsm;
        mRfcommChannelNumber = rfcommChannelNumber;
        mProfileVersion = profileVersion;
        mSupportedFeatures = supportedFeatures;
        mSupportedMessageTypes = supportedMessageTypes;
        mServiceName = serviceName;
    }

    public SdpMasRecord(Parcel in) {
        mMasInstanceId = in.readInt();
        mL2capPsm = in.readInt();
        mRfcommChannelNumber = in.readInt();
        mProfileVersion = in.readInt();
        mSupportedFeatures = in.readInt();
        mSupportedMessageTypes = in.readInt();
        mServiceName = in.readString();
    }

    @Override
    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    public int getMasInstanceId() {
        return mMasInstanceId;
    }

    public int getL2capPsm() {
        return mL2capPsm;
    }

    public int getRfcommCannelNumber() {
        return mRfcommChannelNumber;
    }

    public int getProfileVersion() {
        return mProfileVersion;
    }

    public int getSupportedFeatures() {
        return mSupportedFeatures;
    }

    public int getSupportedMessageTypes() {
        return mSupportedMessageTypes;
    }

    public boolean msgSupported(int msg) {
        return (mSupportedMessageTypes & msg) != 0;
    }

    public String getServiceName() {
        return mServiceName;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMasInstanceId);
        dest.writeInt(mL2capPsm);
        dest.writeInt(mRfcommChannelNumber);
        dest.writeInt(mProfileVersion);
        dest.writeInt(mSupportedFeatures);
        dest.writeInt(mSupportedMessageTypes);
        dest.writeString(mServiceName);
    }

    @Override
    public String toString() {
        String ret = "Bluetooth MAS SDP Record:\n";

        if (mMasInstanceId != -1) {
            ret += "Mas Instance Id: " + mMasInstanceId + "\n";
        }
        if (mRfcommChannelNumber != -1) {
            ret += "RFCOMM Chan Number: " + mRfcommChannelNumber + "\n";
        }
        if (mL2capPsm != -1) {
            ret += "L2CAP PSM: " + mL2capPsm + "\n";
        }
        if (mServiceName != null) {
            ret += "Service Name: " + mServiceName + "\n";
        }
        if (mProfileVersion != -1) {
            ret += "Profile version: " + mProfileVersion + "\n";
        }
        if (mSupportedMessageTypes != -1) {
            ret += "Supported msg types: " + mSupportedMessageTypes + "\n";
        }
        if (mSupportedFeatures != -1) {
            ret += "Supported features: " + mSupportedFeatures + "\n";
        }
        return ret;
    }

    public static final Parcelable.Creator CREATOR = new Parcelable.Creator() {
        public SdpMasRecord createFromParcel(Parcel in) {
            return new SdpMasRecord(in);
        }

        public SdpRecord[] newArray(int size) {
            return new SdpRecord[size];
        }
    };
}
