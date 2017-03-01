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
    private static final String TAG = IpSecConfig.class.getSimpleName();

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
        int spi;

        // Encryption Algorithm
        IpSecAlgorithm encryptionAlgo;

        // Authentication Algorithm
        IpSecAlgorithm authenticationAlgo;
    }

    Flow[] flow = new Flow[2];

    // For tunnel mode IPv4 UDP Encapsulation
    // IpSecTransform#ENCAP_ESP_*, such as ENCAP_ESP_OVER_UDP_IKE
    int encapType;
    int encapLocalPort;
    int encapRemotePort;

    // An optional protocol to match with the selector
    int selectorProto;

    // A bitmask of FEATURE_* indicating which of the fields
    // of this class are valid.
    long features;

    // An interval, in seconds between the NattKeepalive packets
    int nattKeepaliveInterval;

    public InetAddress getLocalIp() {
        return localAddress;
    }

    public int getSpi(int direction) {
        return flow[direction].spi;
    }

    public InetAddress getRemoteIp() {
        return remoteAddress;
    }

    public IpSecAlgorithm getEncryptionAlgo(int direction) {
        return flow[direction].encryptionAlgo;
    }

    public IpSecAlgorithm getAuthenticationAlgo(int direction) {
        return flow[direction].authenticationAlgo;
    }

    Network getNetwork() {
        return network;
    }

    public int getEncapType() {
        return encapType;
    }

    public int getEncapLocalPort() {
        return encapLocalPort;
    }

    public int getEncapRemotePort() {
        return encapRemotePort;
    }

    public int getSelectorProto() {
        return selectorProto;
    }

    int getNattKeepaliveInterval() {
        return nattKeepaliveInterval;
    }

    public boolean hasProperty(int featureBits) {
        return (features & featureBits) == featureBits;
    }

    // Parcelable Methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(features);
        // TODO: Use a byte array or other better method for storing IPs that can also include scope
        out.writeString((localAddress != null) ? localAddress.getHostAddress() : null);
        // TODO: Use a byte array or other better method for storing IPs that can also include scope
        out.writeString((remoteAddress != null) ? remoteAddress.getHostAddress() : null);
        out.writeParcelable(network, flags);
        out.writeInt(flow[IpSecTransform.DIRECTION_IN].spi);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_IN].encryptionAlgo, flags);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_IN].authenticationAlgo, flags);
        out.writeInt(flow[IpSecTransform.DIRECTION_OUT].spi);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_OUT].encryptionAlgo, flags);
        out.writeParcelable(flow[IpSecTransform.DIRECTION_OUT].authenticationAlgo, flags);
        out.writeInt(encapType);
        out.writeInt(encapLocalPort);
        out.writeInt(encapRemotePort);
        out.writeInt(selectorProto);
    }

    // Package Private: Used by the IpSecTransform.Builder;
    // there should be no public constructor for this object
    IpSecConfig() {
        flow[IpSecTransform.DIRECTION_IN].spi = 0;
        flow[IpSecTransform.DIRECTION_OUT].spi = 0;
        nattKeepaliveInterval = 0; //FIXME constant
    }

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
        features = in.readLong();
        localAddress = readInetAddressFromParcel(in);
        remoteAddress = readInetAddressFromParcel(in);
        network = (Network) in.readParcelable(Network.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_IN].spi = in.readInt();
        flow[IpSecTransform.DIRECTION_IN].encryptionAlgo =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_IN].authenticationAlgo =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_OUT].spi = in.readInt();
        flow[IpSecTransform.DIRECTION_OUT].encryptionAlgo =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        flow[IpSecTransform.DIRECTION_OUT].authenticationAlgo =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        encapType = in.readInt();
        encapLocalPort = in.readInt();
        encapRemotePort = in.readInt();
        selectorProto = in.readInt();
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
