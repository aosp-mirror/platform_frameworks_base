/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.net.UnknownHostException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
/**
 * A simple container for route information.
 *
 * @hide
 */
public class RouteInfo implements Parcelable {
    /**
     * The IP destination address for this route.
     */
    private final LinkAddress mDestination;

    /**
     * The gateway address for this route.
     */
    private final InetAddress mGateway;

    private final boolean mIsDefault;

    public RouteInfo(LinkAddress destination, InetAddress gateway) {
        if (destination == null) {
            try {
                if ((gateway != null) && (gateway instanceof Inet4Address)) {
                    destination = new LinkAddress(InetAddress.getByName("0.0.0.0"), 32);
                } else {
                    destination = new LinkAddress(InetAddress.getByName("::0"), 128);
                }
            } catch (Exception e) {}
        }
        mDestination = destination;
        mGateway = gateway;
        mIsDefault = isDefault();
    }

    public RouteInfo(InetAddress gateway) {
        LinkAddress destination = null;
        try {
            if ((gateway != null) && (gateway instanceof Inet4Address)) {
                destination = new LinkAddress(InetAddress.getByName("0.0.0.0"), 32);
            } else {
                destination = new LinkAddress(InetAddress.getByName("::0"), 128);
            }
        } catch (Exception e) {}
        mDestination = destination;
        mGateway = gateway;
        mIsDefault = isDefault();
    }

    private boolean isDefault() {
        boolean val = false;
        if (mGateway != null) {
            if (mGateway instanceof Inet4Address) {
                val = (mDestination == null || mDestination.getNetworkPrefixLength() == 32);
            } else {
                val = (mDestination == null || mDestination.getNetworkPrefixLength() == 128);
            }
        }
        return val;
    }

    public LinkAddress getDestination() {
        return mDestination;
    }

    public InetAddress getGateway() {
        return mGateway;
    }

    public boolean isDefaultRoute() {
        return mIsDefault;
    }

    public String toString() {
        String val = "";
        if (mDestination != null) val = mDestination.toString();
        if (mGateway != null) val += " -> " + mGateway.getHostAddress();
        return val;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (mDestination == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeByteArray(mDestination.getAddress().getAddress());
            dest.writeInt(mDestination.getNetworkPrefixLength());
        }

        if (mGateway == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeByteArray(mGateway.getAddress());
        }
    }

    public static final Creator<RouteInfo> CREATOR =
        new Creator<RouteInfo>() {
        public RouteInfo createFromParcel(Parcel in) {
            InetAddress destAddr = null;
            int prefix = 0;
            InetAddress gateway = null;

            if (in.readByte() == 1) {
                byte[] addr = in.createByteArray();
                prefix = in.readInt();

                try {
                    destAddr = InetAddress.getByAddress(addr);
                } catch (UnknownHostException e) {}
            }

            if (in.readByte() == 1) {
                byte[] addr = in.createByteArray();

                try {
                    gateway = InetAddress.getByAddress(addr);
                } catch (UnknownHostException e) {}
            }

            LinkAddress dest = null;

            if (destAddr != null) {
                dest = new LinkAddress(destAddr, prefix);
            }

            return new RouteInfo(dest, gateway);
        }

        public RouteInfo[] newArray(int size) {
            return new RouteInfo[size];
        }
    };
}
