/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** @hide */
public final class NattKeepalivePacketData extends KeepalivePacketData implements Parcelable {
    // This should only be constructed via static factory methods, such as
    // nattKeepalivePacket
    private NattKeepalivePacketData(InetAddress srcAddress, int srcPort,
            InetAddress dstAddress, int dstPort, byte[] data) throws
            InvalidPacketException {
        super(srcAddress, srcPort, dstAddress, dstPort, data);
    }

    /**
     * Factory method to create Nat-T keepalive packet structure.
     */
    public static NattKeepalivePacketData nattKeepalivePacket(
            InetAddress srcAddress, int srcPort, InetAddress dstAddress, int dstPort)
            throws InvalidPacketException {

        if (!(srcAddress instanceof Inet4Address) || !(dstAddress instanceof Inet4Address)) {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        if (dstPort != NattSocketKeepalive.NATT_PORT) {
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

        return new NattKeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, buf.array());
    }

    /** Parcelable Implementation */
    public int describeContents() {
        return 0;
    }

    /** Write to parcel */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(srcAddress.getHostAddress());
        out.writeString(dstAddress.getHostAddress());
        out.writeInt(srcPort);
        out.writeInt(dstPort);
    }

    /** Parcelable Creator */
    public static final Parcelable.Creator<NattKeepalivePacketData> CREATOR =
            new Parcelable.Creator<NattKeepalivePacketData>() {
                public NattKeepalivePacketData createFromParcel(Parcel in) {
                    final InetAddress srcAddress =
                            InetAddresses.parseNumericAddress(in.readString());
                    final InetAddress dstAddress =
                            InetAddresses.parseNumericAddress(in.readString());
                    final int srcPort = in.readInt();
                    final int dstPort = in.readInt();
                    try {
                        return NattKeepalivePacketData.nattKeepalivePacket(srcAddress, srcPort,
                                    dstAddress, dstPort);
                    } catch (InvalidPacketException e) {
                        throw new IllegalArgumentException(
                                "Invalid NAT-T keepalive data: " + e.error);
                    }
                }

                public NattKeepalivePacketData[] newArray(int size) {
                    return new NattKeepalivePacketData[size];
                }
            };
}
