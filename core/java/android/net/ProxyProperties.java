/*
 * Copyright (C) 2010 The Android Open Source Project
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
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * A container class for the http proxy info
 * @hide
 */
public class ProxyProperties implements Parcelable {

    private InetSocketAddress mProxy;
    private String mExclusionList;

    public ProxyProperties() {
    }

    // copy constructor instead of clone
    public ProxyProperties(ProxyProperties source) {
        if (source != null) {
            mProxy = source.getSocketAddress();
            String exclusionList = source.getExclusionList();
            if (exclusionList != null) {
                mExclusionList = new String(exclusionList);
            }
        }
    }

    public InetSocketAddress getSocketAddress() {
        return mProxy;
    }

    public void setSocketAddress(InetSocketAddress proxy) {
        mProxy = proxy;
    }

    public String getExclusionList() {
        return mExclusionList;
    }

    public void setExclusionList(String exclusionList) {
        mExclusionList = exclusionList;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (mProxy != null) {
            sb.append(mProxy.toString());
            if (mExclusionList != null) {
                    sb.append(" xl=").append(mExclusionList);
            }
        }
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
    public void writeToParcel(Parcel dest, int flags) {
        String host = null;
        if (mProxy != null) {
            try {
                InetAddress addr = mProxy.getAddress();
                if (addr != null) {
                    host = addr.getHostAddress();
                } else {
                    /* Does not resolve when addr is null */
                    host = mProxy.getHostName();
                }
            } catch (Exception e) { }
        }

        if (host != null) {
            dest.writeByte((byte)1);
            dest.writeString(host);
            dest.writeInt(mProxy.getPort());
        } else {
            dest.writeByte((byte)0);
        }
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
                        String host = in.readString();
                        int port = in.readInt();
                        proxyProperties.setSocketAddress(InetSocketAddress.createUnresolved(
                                host, port));
                    } catch (IllegalArgumentException e) { }
                }
                proxyProperties.setExclusionList(in.readString());
                return proxyProperties;
            }

            public ProxyProperties[] newArray(int size) {
                return new ProxyProperties[size];
            }
        };
}
