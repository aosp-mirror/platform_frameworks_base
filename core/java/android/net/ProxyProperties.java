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
import android.text.TextUtils;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * A container class for the http proxy info
 * @hide
 */
public class ProxyProperties implements Parcelable {

    private String mHost;
    private int mPort;
    private String mExclusionList;
    private String[] mParsedExclusionList;

    private String mPacFileUrl;
    public static final String LOCAL_EXCL_LIST = "";
    public static final int LOCAL_PORT = -1;
    public static final String LOCAL_HOST = "localhost";

    public ProxyProperties(String host, int port, String exclList) {
        mHost = host;
        mPort = port;
        setExclusionList(exclList);
    }

    public ProxyProperties(String pacFileUrl) {
        mHost = LOCAL_HOST;
        mPort = LOCAL_PORT;
        setExclusionList(LOCAL_EXCL_LIST);
        mPacFileUrl = pacFileUrl;
    }

    // Only used in PacManager after Local Proxy is bound.
    public ProxyProperties(String pacFileUrl, int localProxyPort) {
        mHost = LOCAL_HOST;
        mPort = localProxyPort;
        setExclusionList(LOCAL_EXCL_LIST);
        mPacFileUrl = pacFileUrl;
    }

    private ProxyProperties(String host, int port, String exclList, String[] parsedExclList) {
        mHost = host;
        mPort = port;
        mExclusionList = exclList;
        mParsedExclusionList = parsedExclList;
        mPacFileUrl = null;
    }

    // copy constructor instead of clone
    public ProxyProperties(ProxyProperties source) {
        if (source != null) {
            mHost = source.getHost();
            mPort = source.getPort();
            mPacFileUrl = source.getPacFileUrl();
            mExclusionList = source.getExclusionList();
            mParsedExclusionList = source.mParsedExclusionList;
        }
    }

    public InetSocketAddress getSocketAddress() {
        InetSocketAddress inetSocketAddress = null;
        try {
            inetSocketAddress = new InetSocketAddress(mHost, mPort);
        } catch (IllegalArgumentException e) { }
        return inetSocketAddress;
    }

    public String getPacFileUrl() {
        return mPacFileUrl;
    }

    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    // comma separated
    public String getExclusionList() {
        return mExclusionList;
    }

    // comma separated
    private void setExclusionList(String exclusionList) {
        mExclusionList = exclusionList;
        if (mExclusionList == null) {
            mParsedExclusionList = new String[0];
        } else {
            String splitExclusionList[] = exclusionList.toLowerCase(Locale.ROOT).split(",");
            mParsedExclusionList = new String[splitExclusionList.length * 2];
            for (int i = 0; i < splitExclusionList.length; i++) {
                String s = splitExclusionList[i].trim();
                if (s.startsWith(".")) s = s.substring(1);
                mParsedExclusionList[i*2] = s;
                mParsedExclusionList[(i*2)+1] = "." + s;
            }
        }
    }

    public boolean isExcluded(String url) {
        if (TextUtils.isEmpty(url) || mParsedExclusionList == null ||
                mParsedExclusionList.length == 0) return false;

        Uri u = Uri.parse(url);
        String urlDomain = u.getHost();
        if (urlDomain == null) return false;
        for (int i = 0; i< mParsedExclusionList.length; i+=2) {
            if (urlDomain.equals(mParsedExclusionList[i]) ||
                    urlDomain.endsWith(mParsedExclusionList[i+1])) {
                return true;
            }
        }
        return false;
    }

    public boolean isValid() {
        if (!TextUtils.isEmpty(mPacFileUrl)) return true;
        try {
            Proxy.validate(mHost == null ? "" : mHost, mPort == 0 ? "" : Integer.toString(mPort),
                    mExclusionList == null ? "" : mExclusionList);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    public java.net.Proxy makeProxy() {
        java.net.Proxy proxy = java.net.Proxy.NO_PROXY;
        if (mHost != null) {
            try {
                InetSocketAddress inetSocketAddress = new InetSocketAddress(mHost, mPort);
                proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, inetSocketAddress);
            } catch (IllegalArgumentException e) {
            }
        }
        return proxy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (mPacFileUrl != null) {
            sb.append("PAC Script: ");
            sb.append(mPacFileUrl);
        } else if (mHost != null) {
            sb.append("[");
            sb.append(mHost);
            sb.append("] ");
            sb.append(Integer.toString(mPort));
            if (mExclusionList != null) {
                    sb.append(" xl=").append(mExclusionList);
            }
        } else {
            sb.append("[ProxyProperties.mHost == null]");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ProxyProperties)) return false;
        ProxyProperties p = (ProxyProperties)o;
        // If PAC URL is present in either then they must be equal.
        // Other parameters will only be for fall back.
        if (!TextUtils.isEmpty(mPacFileUrl)) {
            return mPacFileUrl.equals(p.getPacFileUrl()) && mPort == p.mPort;
        }
        if (!TextUtils.isEmpty(p.getPacFileUrl())) {
            return false;
        }
        if (mExclusionList != null && !mExclusionList.equals(p.getExclusionList())) return false;
        if (mHost != null && p.getHost() != null && mHost.equals(p.getHost()) == false) {
            return false;
        }
        if (mHost != null && p.mHost == null) return false;
        if (mHost == null && p.mHost != null) return false;
        if (mPort != p.mPort) return false;
        return true;
    }

    /**
     * Implement the Parcelable interface
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    @Override
    /*
     * generate hashcode based on significant fields
     */
    public int hashCode() {
        return ((null == mHost) ? 0 : mHost.hashCode())
        + ((null == mExclusionList) ? 0 : mExclusionList.hashCode())
        + mPort;
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public void writeToParcel(Parcel dest, int flags) {
        if (mPacFileUrl != null) {
            dest.writeByte((byte)1);
            dest.writeString(mPacFileUrl);
            dest.writeInt(mPort);
            return;
        } else {
            dest.writeByte((byte)0);
        }
        if (mHost != null) {
            dest.writeByte((byte)1);
            dest.writeString(mHost);
            dest.writeInt(mPort);
        } else {
            dest.writeByte((byte)0);
        }
        dest.writeString(mExclusionList);
        dest.writeStringArray(mParsedExclusionList);
    }

    /**
     * Implement the Parcelable interface.
     * @hide
     */
    public static final Creator<ProxyProperties> CREATOR =
        new Creator<ProxyProperties>() {
            public ProxyProperties createFromParcel(Parcel in) {
                String host = null;
                int port = 0;
                if (in.readByte() != 0) {
                    String url = in.readString();
                    int localPort = in.readInt();
                    return new ProxyProperties(url, localPort);
                }
                if (in.readByte() != 0) {
                    host = in.readString();
                    port = in.readInt();
                }
                String exclList = in.readString();
                String[] parsedExclList = in.readStringArray();
                ProxyProperties proxyProperties =
                        new ProxyProperties(host, port, exclList, parsedExclList);
                return proxyProperties;
            }

            public ProxyProperties[] newArray(int size) {
                return new ProxyProperties[size];
            }
        };
}
