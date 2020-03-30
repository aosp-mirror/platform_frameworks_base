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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.util.IpUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.system.OsConstants;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Represents the actual tcp keep alive packets which will be used for hardware offload.
 * @hide
 */
public class TcpKeepalivePacketData extends KeepalivePacketData implements Parcelable {
    private static final String TAG = "TcpKeepalivePacketData";

    /** TCP sequence number. */
    public final int tcpSeq;

    /** TCP ACK number. */
    public final int tcpAck;

    /** TCP RCV window. */
    public final int tcpWnd;

    /** TCP RCV window scale. */
    public final int tcpWndScale;

    /** IP TOS. */
    public final int ipTos;

    /** IP TTL. */
    public final int ipTtl;

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int TCP_HEADER_LENGTH = 20;

    // This should only be constructed via static factory methods, such as
    // tcpKeepalivePacket.
    private TcpKeepalivePacketData(final TcpKeepalivePacketDataParcelable tcpDetails,
            final byte[] data) throws InvalidPacketException, UnknownHostException {
        super(InetAddress.getByAddress(tcpDetails.srcAddress), tcpDetails.srcPort,
                InetAddress.getByAddress(tcpDetails.dstAddress), tcpDetails.dstPort, data);
        tcpSeq = tcpDetails.seq;
        tcpAck = tcpDetails.ack;
        // In the packet, the window is shifted right by the window scale.
        tcpWnd = tcpDetails.rcvWnd;
        tcpWndScale = tcpDetails.rcvWndScale;
        ipTos = tcpDetails.tos;
        ipTtl = tcpDetails.ttl;
    }

    private TcpKeepalivePacketData(final InetAddress srcAddress, int srcPort,
            final InetAddress dstAddress, int dstPort, final byte[] data, int tcpSeq,
            int tcpAck, int tcpWnd, int tcpWndScale, int ipTos, int ipTtl)
            throws InvalidPacketException {
        super(srcAddress, srcPort, dstAddress, dstPort, data);
        this.tcpSeq = tcpSeq;
        this.tcpAck = tcpAck;
        this.tcpWnd = tcpWnd;
        this.tcpWndScale = tcpWndScale;
        this.ipTos = ipTos;
        this.ipTtl = ipTtl;
    }

    /**
     * Factory method to create tcp keepalive packet structure.
     */
    public static TcpKeepalivePacketData tcpKeepalivePacket(
            TcpKeepalivePacketDataParcelable tcpDetails) throws InvalidPacketException {
        final byte[] packet;
        try {
            if ((tcpDetails.srcAddress != null) && (tcpDetails.dstAddress != null)
                    && (tcpDetails.srcAddress.length == 4 /* V4 IP length */)
                    && (tcpDetails.dstAddress.length == 4 /* V4 IP length */)) {
                packet = buildV4Packet(tcpDetails);
            } else {
                // TODO: support ipv6
                throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
            }
            return new TcpKeepalivePacketData(tcpDetails, packet);
        } catch (UnknownHostException e) {
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

    }

    /**
     * Build ipv4 tcp keepalive packet, not including the link-layer header.
     */
    // TODO : if this code is ever moved to the network stack, factorize constants with the ones
    // over there.
    private static byte[] buildV4Packet(TcpKeepalivePacketDataParcelable tcpDetails) {
        final int length = IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x45);                       // IP version and IHL
        buf.put((byte) tcpDetails.tos);             // TOS
        buf.putShort((short) length);
        buf.putInt(0x00004000);                     // ID, flags=DF, offset
        buf.put((byte) tcpDetails.ttl);             // TTL
        buf.put((byte) OsConstants.IPPROTO_TCP);
        final int ipChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // IP checksum
        buf.put(tcpDetails.srcAddress);
        buf.put(tcpDetails.dstAddress);
        buf.putShort((short) tcpDetails.srcPort);
        buf.putShort((short) tcpDetails.dstPort);
        buf.putInt(tcpDetails.seq);                 // Sequence Number
        buf.putInt(tcpDetails.ack);                 // ACK
        buf.putShort((short) 0x5010);               // TCP length=5, flags=ACK
        buf.putShort((short) (tcpDetails.rcvWnd >> tcpDetails.rcvWndScale));   // Window size
        final int tcpChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // TCP checksum
        // URG is not set therefore the urgent pointer is zero.
        buf.putShort((short) 0);                    // Urgent pointer

        buf.putShort(ipChecksumOffset, IpUtils.ipChecksum(buf, 0));
        buf.putShort(tcpChecksumOffset, IpUtils.tcpChecksum(
                buf, 0, IPV4_HEADER_LENGTH, TCP_HEADER_LENGTH));

        return buf.array();
    }

    // TODO: add buildV6Packet.

    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof TcpKeepalivePacketData)) return false;
        final TcpKeepalivePacketData other = (TcpKeepalivePacketData) o;
        final InetAddress srcAddress = getSrcAddress();
        final InetAddress dstAddress = getDstAddress();
        return srcAddress.equals(other.getSrcAddress())
                && dstAddress.equals(other.getDstAddress())
                && getSrcPort() == other.getSrcPort()
                && getDstPort() == other.getDstPort()
                && this.tcpAck == other.tcpAck
                && this.tcpSeq == other.tcpSeq
                && this.tcpWnd == other.tcpWnd
                && this.tcpWndScale == other.tcpWndScale
                && this.ipTos == other.ipTos
                && this.ipTtl == other.ipTtl;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSrcAddress(), getDstAddress(), getSrcPort(), getDstPort(),
                tcpAck, tcpSeq, tcpWnd, tcpWndScale, ipTos, ipTtl);
    }

    /**
     * Parcelable Implementation.
     * Note that this object implements parcelable (and needs to keep doing this as it inherits
     * from a class that does), but should usually be parceled as a stable parcelable using
     * the toStableParcelable() and fromStableParcelable() methods.
     */
    public int describeContents() {
        return 0;
    }

    /** Write to parcel. */
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(getSrcAddress().getHostAddress());
        out.writeString(getDstAddress().getHostAddress());
        out.writeInt(getSrcPort());
        out.writeInt(getDstPort());
        out.writeByteArray(getPacket());
        out.writeInt(tcpSeq);
        out.writeInt(tcpAck);
        out.writeInt(tcpWnd);
        out.writeInt(tcpWndScale);
        out.writeInt(ipTos);
        out.writeInt(ipTtl);
    }

    private static TcpKeepalivePacketData readFromParcel(Parcel in) throws InvalidPacketException {
        InetAddress srcAddress = InetAddresses.parseNumericAddress(in.readString());
        InetAddress dstAddress = InetAddresses.parseNumericAddress(in.readString());
        int srcPort = in.readInt();
        int dstPort = in.readInt();
        byte[] packet = in.createByteArray();
        int tcpSeq = in.readInt();
        int tcpAck = in.readInt();
        int tcpWnd = in.readInt();
        int tcpWndScale = in.readInt();
        int ipTos = in.readInt();
        int ipTtl = in.readInt();
        return new TcpKeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, packet, tcpSeq,
                tcpAck, tcpWnd, tcpWndScale, ipTos, ipTtl);
    }

    /** Parcelable Creator. */
    public static final @NonNull Parcelable.Creator<TcpKeepalivePacketData> CREATOR =
            new Parcelable.Creator<TcpKeepalivePacketData>() {
                public TcpKeepalivePacketData createFromParcel(Parcel in) {
                    try {
                        return readFromParcel(in);
                    } catch (InvalidPacketException e) {
                        throw new IllegalArgumentException(
                                "Invalid NAT-T keepalive data: " + e.getError());
                    }
                }

                public TcpKeepalivePacketData[] newArray(int size) {
                    return new TcpKeepalivePacketData[size];
                }
            };

    /**
     * Convert this TcpKeepalivePacketData to a TcpKeepalivePacketDataParcelable.
     */
    @NonNull
    public TcpKeepalivePacketDataParcelable toStableParcelable() {
        final TcpKeepalivePacketDataParcelable parcel = new TcpKeepalivePacketDataParcelable();
        final InetAddress srcAddress = getSrcAddress();
        final InetAddress dstAddress = getDstAddress();
        parcel.srcAddress = srcAddress.getAddress();
        parcel.srcPort = getSrcPort();
        parcel.dstAddress = dstAddress.getAddress();
        parcel.dstPort = getDstPort();
        parcel.seq = tcpSeq;
        parcel.ack = tcpAck;
        parcel.rcvWnd = tcpWnd;
        parcel.rcvWndScale = tcpWndScale;
        parcel.tos = ipTos;
        parcel.ttl = ipTtl;
        return parcel;
    }

    @Override
    public String toString() {
        return "saddr: " + getSrcAddress()
                + " daddr: " + getDstAddress()
                + " sport: " + getSrcPort()
                + " dport: " + getDstPort()
                + " seq: " + tcpSeq
                + " ack: " + tcpAck
                + " wnd: " + tcpWnd
                + " wndScale: " + tcpWndScale
                + " tos: " + ipTos
                + " ttl: " + ipTtl;
    }
}
