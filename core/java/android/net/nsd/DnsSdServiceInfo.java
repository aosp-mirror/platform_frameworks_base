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

package android.net.nsd;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * Defines a service based on DNS service discovery
 * {@hide}
 */
public class DnsSdServiceInfo implements NetworkServiceInfo, Parcelable {

    private String mServiceName;

    private String mRegistrationType;

    private DnsSdTxtRecord mTxtRecord;

    private String mHostname;

    private int mPort;

    DnsSdServiceInfo() {
    }

    DnsSdServiceInfo(String sn, String rt, DnsSdTxtRecord tr) {
        mServiceName = sn;
        mRegistrationType = rt;
        mTxtRecord = tr;
    }

    @Override
    /** @hide */
    public String getServiceName() {
        return mServiceName;
    }

    @Override
    /** @hide */
    public void setServiceName(String s) {
        mServiceName = s;
    }

    @Override
    /** @hide */
    public String getServiceType() {
        return mRegistrationType;
    }

    @Override
    /** @hide */
    public void setServiceType(String s) {
        mRegistrationType = s;
    }

    public DnsSdTxtRecord getTxtRecord() {
        return mTxtRecord;
    }

    public void setTxtRecord(DnsSdTxtRecord t) {
        mTxtRecord = new DnsSdTxtRecord(t);
    }

    public String getHostName() {
        return mHostname;
    }

    public void setHostName(String s) {
        mHostname = s;
    }

    public int getPort() {
        return mPort;
    }

    public void setPort(int p) {
        mPort = p;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("name: ").append(mServiceName).
            append("type: ").append(mRegistrationType).
            append("txtRecord: ").append(mTxtRecord);
        return sb.toString();
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mServiceName);
        dest.writeString(mRegistrationType);
        dest.writeParcelable(mTxtRecord, flags);
        dest.writeString(mHostname);
        dest.writeInt(mPort);
    }

    /** Implement the Parcelable interface */
    public static final Creator<DnsSdServiceInfo> CREATOR =
        new Creator<DnsSdServiceInfo>() {
            public DnsSdServiceInfo createFromParcel(Parcel in) {
                DnsSdServiceInfo info = new DnsSdServiceInfo();
                info.mServiceName = in.readString();
                info.mRegistrationType = in.readString();
                info.mTxtRecord = in.readParcelable(null);
                info.mHostname = in.readString();
                info.mPort = in.readInt();
                return info;
            }

            public DnsSdServiceInfo[] newArray(int size) {
                return new DnsSdServiceInfo[size];
            }
        };

}
