/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.net.SocketKeepalive.ERROR_INVALID_IP_ADDRESS;
import static android.net.SocketKeepalive.ERROR_INVALID_PORT;

import android.net.SocketKeepalive.InvalidPacketException;
import android.net.util.IpUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.net.InetAddress;

/**
 * Represents the actual packets that are sent by the
 * {@link android.net.SocketKeepalive} API.
 *
 * @hide
 */
public class KeepalivePacketData implements Parcelable {
    private static final String TAG = "KeepalivePacketData";

    /** Source IP address */
    public final InetAddress srcAddress;

    /** Destination IP address */
    public final InetAddress dstAddress;

    /** Source port */
    public final int srcPort;

    /** Destination port */
    public final int dstPort;

    /** Packet data. A raw byte string of packet data, not including the link-layer header. */
    private final byte[] mPacket;

    protected static final int IPV4_HEADER_LENGTH = 20;
    protected static final int UDP_HEADER_LENGTH = 8;

    // This should only be constructed via static factory methods, such as
    // nattKeepalivePacket
    protected KeepalivePacketData(InetAddress srcAddress, int srcPort,
            InetAddress dstAddress, int dstPort, byte[] data) throws InvalidPacketException {
        this.srcAddress = srcAddress;
        this.dstAddress = dstAddress;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.mPacket = data;

        // Check we have two IP addresses of the same family.
        if (srcAddress == null || dstAddress == null || !srcAddress.getClass().getName()
                .equals(dstAddress.getClass().getName())) {
            Log.e(TAG, "Invalid or mismatched InetAddresses in KeepalivePacketData");
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        // Check the ports.
        if (!IpUtils.isValidUdpOrTcpPort(srcPort) || !IpUtils.isValidUdpOrTcpPort(dstPort)) {
            Log.e(TAG, "Invalid ports in KeepalivePacketData");
            throw new InvalidPacketException(ERROR_INVALID_PORT);
        }
    }

    public byte[] getPacket() {
        return mPacket.clone();
    }

    /* Parcelable Implementation */
    public int describeContents() {
        return 0;
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(srcAddress.getHostAddress());
        out.writeString(dstAddress.getHostAddress());
        out.writeInt(srcPort);
        out.writeInt(dstPort);
        out.writeByteArray(mPacket);
    }

    private KeepalivePacketData(Parcel in) {
        srcAddress = NetworkUtils.numericToInetAddress(in.readString());
        dstAddress = NetworkUtils.numericToInetAddress(in.readString());
        srcPort = in.readInt();
        dstPort = in.readInt();
        mPacket = in.createByteArray();
    }

    /** Parcelable Creator */
    public static final Parcelable.Creator<KeepalivePacketData> CREATOR =
            new Parcelable.Creator<KeepalivePacketData>() {
                public KeepalivePacketData createFromParcel(Parcel in) {
                    return new KeepalivePacketData(in);
                }

                public KeepalivePacketData[] newArray(int size) {
                    return new KeepalivePacketData[size];
                }
            };

}
