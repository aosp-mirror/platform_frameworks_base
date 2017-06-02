/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.util.Log;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** @hide */
public final class IpSecConfig implements Parcelable {
    private static final String TAG = "IpSecConfig";

    //MODE_TRANSPORT or MODE_TUNNEL
    int mode;

    // For tunnel mode
    InetAddress localAddress;

    InetAddress remoteAddress;

    // Limit selection by network interface
    Network network;

    public static class Flow {
        // Minimum requirements for identifying a transform
        // SPI identifying the IPsec flow in packet processing
        // and a remote IP address
        int spiResourceId;

        // Encryption Algorithm
        IpSecAlgorithm encryption;

        // Authentication Algorithm
        IpSecAlgorithm authentication;
    }

    Flow[] flow = new Flow[] {new Flow(), new Flow()};

    // For tunnel mode IPv4 UDP Encapsulation
    // IpSecTransform#ENCAP_ESP_*, such as ENCAP_ESP_OVER_UDP_IKE
    int encapType;
    int encapLocalPortResourceId;
    int encapRemotePort;

    // An interval, in seconds between the NattKeepalive packets
    int nattKeepaliveInterval;

    // Transport or Tunnel
    public int getMode() {
        return mode;
    }

    public InetAddress getLocalAddress() {
        return localAddress;
    }

    public int getSpiResourceId(int direction) {
        return flow[direction].spiResourceId;
    }

    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }

    public IpSecAlgorithm getEncryption(int direction) {
        return flow[direction].encryption;
    }

    public IpSecAlgorithm getAuthentication(int direction) {
        return flow[direction].authentication;
    }

    public Network getNetwork() {
        return network;
    }

    public int getEncapType() {
        return encapType;
    }

    public int getEncapLocalResourceId() {
        return encapLocalPortResourceId;
    }

    public int getEncapRemotePort() {
        return encapRemotePort;
    }

    public int getNattKeepaliveInterval() {
        return nattKeepaliveInterval;
    }

    // Parcelable Methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        // TODO: Use a byte array or other better method for storing IPs that can also include scope
        out.writeString((localAddress != null) ? localAddress.getHostAddress() : null);
        // TODO: Use a byte array or other better method for storing IPs that can also include scope
        out.writeString((remoteAddress != null) ? remoteAddress.getHostAddress() : null);
        out.writeParcelable(network, flags);
        out.writeInt(flow[IpSecTransform.DIRECTION_IN].spiResourceId);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_IN].encryption, flags);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_IN].authentication, flags);
        out.writeInt(flow[IpSecTransform.DIRECTION_OUT].spiResourceId);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_OUT].encryption, flags);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_OUT].authentication, flags);
        out.writeInt(encapType);
        out.writeInt(encapLocalPortResourceId);
        out.writeInt(encapRemotePort);
    }

    // Package Private: Used by the IpSecTransform.Builder;
    // there should be no public constructor for this object
    IpSecConfig() {}

    private static InetAddress readInetAddressFromParcel(Parcel in) {
        String addrString = in.readString();
        if (addrString == null) {
            return null;
        }
        try {
            return InetAddress.getByName(addrString);
        } catch (UnknownHostException e) {
            Log.wtf(TAG, "Invalid IpAddress " + addrString);
            return null;
        }
    }

    private IpSecConfig(Parcel in) {
        localAddress = readInetAddressFromParcel(in);
        remoteAddress = readInetAddressFromParcel(in);
        network = (Network) in.readParcelable(Network.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_IN].spiResourceId = in.readInt();
        flow[IpSecTransform.DIRECTION_IN].encryption =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_IN].authentication =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_OUT].spiResourceId = in.readInt();
        flow[IpSecTransform.DIRECTION_OUT].encryption =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_OUT].authentication =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        encapType = in.readInt();
        encapLocalPortResourceId = in.readInt();
        encapRemotePort = in.readInt();
    }

    public static final Parcelable.Creator<IpSecConfig> CREATOR =
            new Parcelable.Creator<IpSecConfig>() {
                public IpSecConfig createFromParcel(Parcel in) {
                    return new IpSecConfig(in);
                }

                public IpSecConfig[] newArray(int size) {
                    return new IpSecConfig[size];
                }
            };
}
