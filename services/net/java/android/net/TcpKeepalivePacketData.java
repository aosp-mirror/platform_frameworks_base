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
import android.net.SocketKeepalive.InvalidPacketException;
import android.net.util.IpUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.system.OsConstants;

import java.net.Inet4Address;
import java.net.InetAddress;
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

    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int TCP_HEADER_LENGTH = 20;

    // This should only be constructed via static factory methods, such as
    // tcpKeepalivePacket.
    private TcpKeepalivePacketData(TcpSocketInfo tcpDetails, byte[] data)
            throws InvalidPacketException {
        super(tcpDetails.srcAddress, tcpDetails.srcPort, tcpDetails.dstAddress,
                tcpDetails.dstPort, data);
        tcpSeq = tcpDetails.seq;
        tcpAck = tcpDetails.ack;
        // In the packet, the window is shifted right by the window scale.
        tcpWnd = tcpDetails.rcvWnd;
        tcpWndScale = tcpDetails.rcvWndScale;
    }

    /**
     * Factory method to create tcp keepalive packet structure.
     */
    public static TcpKeepalivePacketData tcpKeepalivePacket(
            TcpSocketInfo tcpDetails) throws InvalidPacketException {
        final byte[] packet;
        if ((tcpDetails.srcAddress instanceof Inet4Address)
                && (tcpDetails.dstAddress instanceof Inet4Address)) {
            packet = buildV4Packet(tcpDetails);
        } else {
            // TODO: support ipv6
            throw new InvalidPacketException(ERROR_INVALID_IP_ADDRESS);
        }

        return new TcpKeepalivePacketData(tcpDetails, packet);
    }

    /**
     * Build ipv4 tcp keepalive packet, not including the link-layer header.
     */
    // TODO : if this code is ever moved to the network stack, factorize constants with the ones
    // over there.
    private static byte[] buildV4Packet(TcpSocketInfo tcpDetails) {
        final int length = IPV4_HEADER_LENGTH + TCP_HEADER_LENGTH;
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.order(ByteOrder.BIG_ENDIAN);
        // IP version and TOS. TODO : fetch this from getsockopt(SOL_IP, IP_TOS)
        buf.putShort((short) 0x4500);
        buf.putShort((short) length);
        buf.putInt(0x4000);                         // ID, flags=DF, offset
        // TODO : fetch TTL from getsockopt(SOL_IP, IP_TTL)
        buf.put((byte) 64);
        buf.put((byte) OsConstants.IPPROTO_TCP);
        final int ipChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // IP checksum
        buf.put(tcpDetails.srcAddress.getAddress());
        buf.put(tcpDetails.dstAddress.getAddress());
        buf.putShort((short) tcpDetails.srcPort);
        buf.putShort((short) tcpDetails.dstPort);
        buf.putInt(tcpDetails.seq);                 // Sequence Number
        buf.putInt(tcpDetails.ack);                 // ACK
        buf.putShort((short) 0x5010);               // TCP length=5, flags=ACK
        buf.putShort((short) (tcpDetails.rcvWnd >> tcpDetails.rcvWndScale));   // Window size
        final int tcpChecksumOffset = buf.position();
        buf.putShort((short) 0);                    // TCP checksum
        // URG is not set therefore the urgent pointer is not included
        buf.putShort(ipChecksumOffset, IpUtils.ipChecksum(buf, 0));
        buf.putShort(tcpChecksumOffset, IpUtils.tcpChecksum(
                buf, 0, IPV4_HEADER_LENGTH, TCP_HEADER_LENGTH));

        return buf.array();
    }

    // TODO: add buildV6Packet.

    /** Represents tcp/ip information. */
    // TODO: Replace TcpSocketInfo with TcpKeepalivePacketDataParcelable.
    public static class TcpSocketInfo {
        public final InetAddress srcAddress;
        public final InetAddress dstAddress;
        public final int srcPort;
        public final int dstPort;
        public final int seq;
        public final int ack;
        public final int rcvWnd;
        public final int rcvWndScale;

        public TcpSocketInfo(InetAddress sAddr, int sPort, InetAddress dAddr,
                int dPort, int writeSeq, int readSeq, int rWnd, int rWndScale) {
            srcAddress = sAddr;
            dstAddress = dAddr;
            srcPort = sPort;
            dstPort = dPort;
            seq = writeSeq;
            ack = readSeq;
            rcvWnd = rWnd;
            rcvWndScale = rWndScale;
        }
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof TcpKeepalivePacketData)) return false;
        final TcpKeepalivePacketData other = (TcpKeepalivePacketData) o;
        return this.srcAddress.equals(other.srcAddress)
                && this.dstAddress.equals(other.dstAddress)
                && this.srcPort == other.srcPort
                && this.dstPort == other.dstPort
                && this.tcpAck == other.tcpAck
                && this.tcpSeq == other.tcpSeq
                && this.tcpWnd == other.tcpWnd
                && this.tcpWndScale == other.tcpWndScale;
    }

    @Override
    public int hashCode() {
        return Objects.hash(srcAddress, dstAddress, srcPort, dstPort, tcpAck, tcpSeq, tcpWnd,
                tcpWndScale);
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
        super.writeToParcel(out, flags);
        out.writeInt(tcpSeq);
        out.writeInt(tcpAck);
        out.writeInt(tcpWnd);
        out.writeInt(tcpWndScale);
    }

    private TcpKeepalivePacketData(Parcel in) {
        super(in);
        tcpSeq = in.readInt();
        tcpAck = in.readInt();
        tcpWnd = in.readInt();
        tcpWndScale = in.readInt();
    }

    /** Parcelable Creator. */
    public static final @NonNull Parcelable.Creator<TcpKeepalivePacketData> CREATOR =
            new Parcelable.Creator<TcpKeepalivePacketData>() {
                public TcpKeepalivePacketData createFromParcel(Parcel in) {
                    return new TcpKeepalivePacketData(in);
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
        parcel.srcAddress = srcAddress.getAddress();
        parcel.srcPort = srcPort;
        parcel.dstAddress = dstAddress.getAddress();
        parcel.dstPort = dstPort;
        parcel.seq = tcpSeq;
        parcel.ack = tcpAck;
        return parcel;
    }

    @Override
    public String toString() {
        return "saddr: " + srcAddress
                + " daddr: " + dstAddress
                + " sport: " + srcPort
                + " dport: " + dstPort
                + " seq: " + tcpSeq
                + " ack: " + tcpAck
                + " wnd: " + tcpWnd
                + " wndScale: " + tcpWndScale;
    }
}
