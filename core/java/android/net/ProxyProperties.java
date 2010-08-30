/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;


import android.os.Parcel;
import android.os.Parcelable;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A container class for the http proxy info
 * @hide
 */
public class ProxyProperties implements Parcelable {

    private InetAddress mProxy;
    private int mPort;
    private String mExclusionList;

    public ProxyProperties() {
    }

    public synchronized InetAddress getAddress() {
        return mProxy;
    }
    public synchronized void setAddress(InetAddress proxy) {
        mProxy = proxy;
    }

    public synchronized int getPort() {
        return mPort;
    }
    public synchronized void setPort(int port) {
        mPort = port;
    }

    public synchronized String getExclusionList() {
        return mExclusionList;
    }
    public synchronized void setExclusionList(String exclusionList) {
        mExclusionList = exclusionList;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(mProxy.getHostAddress()).append(":").append(mPort)
          .append(" xl=").append(mExclusionList);
        return sb.toString();
    }

    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public synchronized void writeToParcel(Parcel dest, int flags) {
        if (mProxy != null) {
            dest.writeByte((byte)1);
            dest.writeString(mProxy.getHostName());
            dest.writeByteArray(mProxy.getAddress());
        } else {
            dest.writeByte((byte)0);
        }
        dest.writeInt(mPort);
        dest.writeString(mExclusionList);
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<ProxyProperties> CREATOR =
        new Creator<ProxyProperties>() {
            public ProxyProperties createFromParcel(Parcel in) {
                ProxyProperties proxyProperties = new ProxyProperties();
                if (in.readByte() == 1) {
                    try {
                        proxyProperties.setAddress(InetAddress.getByAddress(in.readString(),
                                in.createByteArray()));
                    } catch (UnknownHostException e) {}
                }
                proxyProperties.setPort(in.readInt());
                proxyProperties.setExclusionList(in.readString());
                return proxyProperties;
            }

            public ProxyProperties[] newArray(int size) {
                return new ProxyProperties[size];
            }
        };

};
