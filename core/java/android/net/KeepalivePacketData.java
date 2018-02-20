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

import static android.net.ConnectivityManager.PacketKeepalive.*;

import android.net.util.IpUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.system.OsConstants;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents the actual packets that are sent by the
 * {@link android.net.ConnectivityManager.PacketKeepalive} API.
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

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;

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

    public static class InvalidPacketException extends Exception {
        public final int error;
        public InvalidPacketException(int error) {
            this.error = error;
        }
    }

    public byte[] getPacket() {
        return mPacket.clone();
    }

    public static KeepalivePacketData nattKeepalivePacket(
            InetAddress srcAddress, int srcPort, InetAddress dstAddress, int dstPort)
            throws InvalidPacketException {

        if (!(srcAddress instanceof Inet4Address) || !(dstAddress instanceof Inet4Address)) {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        if (dstPort != NATT_PORT) {
            throw new InvalidPacketException(ERROR_INVALID_PORT);
        }

        int length = IPV4_HEADER_LENGTH + UDP_HEADER_LENGTH + 1;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0x4500);             // IP version and TOS
        buf.putShort((short) length);
        buf.putInt(0);                            // ID, flags, offset
        buf.put((byte) 64);                       // TTL
        buf.put((byte) OsConstants.IPPROTO_UDP);
        int ipChecksumOffset = buf.position();
        buf.putShort((short) 0);                  // IP checksum
        buf.put(srcAddress.getAddress());
        buf.put(dstAddress.getAddress());
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) (length - 20));      // UDP length
        int udpChecksumOffset = buf.position();
        buf.putShort((short) 0);                  // UDP checksum
        buf.put((byte) 0xff);                     // NAT-T keepalive
        buf.putShort(ipChecksumOffset, IpUtils.ipChecksum(buf, 0));
        buf.putShort(udpChecksumOffset, IpUtils.udpChecksum(buf, 0, IPV4_HEADER_LENGTH));

        return new KeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, buf.array());
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
