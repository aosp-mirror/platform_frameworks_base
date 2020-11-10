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

package android.net.util;

import static android.net.SocketKeepalive.ERROR_INVALID_IP_ADDRESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InvalidPacketException;
import android.net.KeepalivePacketData;
import android.net.NattKeepalivePacketData;
import android.net.NattKeepalivePacketDataParcelable;
import android.net.TcpKeepalivePacketData;
import android.net.TcpKeepalivePacketDataParcelable;
import android.os.Build;
import android.system.OsConstants;
import android.util.Log;

import com.android.net.module.util.IpUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility class to convert to/from keepalive data parcelables.
 *
 * TODO: move to networkstack-client library when it is moved to frameworks/libs/net.
 * This class cannot go into other shared libraries as it depends on NetworkStack AIDLs.
 * @hide
 */
public final class KeepalivePacketDataUtil {
    private static final int IPV4_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int TCP_HEADER_LENGTH = 20;

    private static final String TAG = KeepalivePacketDataUtil.class.getSimpleName();

    /**
     * Convert a NattKeepalivePacketData to a NattKeepalivePacketDataParcelable.
     */
    @NonNull
    public static NattKeepalivePacketDataParcelable toStableParcelable(
            @NonNull NattKeepalivePacketData pkt) {
        final NattKeepalivePacketDataParcelable parcel = new NattKeepalivePacketDataParcelable();
        final InetAddress srcAddress = pkt.getSrcAddress();
        final InetAddress dstAddress = pkt.getDstAddress();
        parcel.srcAddress = srcAddress.getAddress();
        parcel.srcPort = pkt.getSrcPort();
        parcel.dstAddress = dstAddress.getAddress();
        parcel.dstPort = pkt.getDstPort();
        return parcel;
    }

    /**
     * Convert a TcpKeepalivePacketData to a TcpKeepalivePacketDataParcelable.
     */
    @NonNull
    public static TcpKeepalivePacketDataParcelable toStableParcelable(
            @NonNull TcpKeepalivePacketData pkt) {
        final TcpKeepalivePacketDataParcelable parcel = new TcpKeepalivePacketDataParcelable();
        final InetAddress srcAddress = pkt.getSrcAddress();
        final InetAddress dstAddress = pkt.getDstAddress();
        parcel.srcAddress = srcAddress.getAddress();
        parcel.srcPort = pkt.getSrcPort();
        parcel.dstAddress = dstAddress.getAddress();
        parcel.dstPort = pkt.getDstPort();
        parcel.seq = pkt.tcpSeq;
        parcel.ack = pkt.tcpAck;
        parcel.rcvWnd = pkt.tcpWindow;
        parcel.rcvWndScale = pkt.tcpWindowScale;
        parcel.tos = pkt.ipTos;
        parcel.ttl = pkt.ipTtl;
        return parcel;
    }

    /**
     * Factory method to create tcp keepalive packet structure.
     * @hide
     */
    public static TcpKeepalivePacketData fromStableParcelable(
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
            return new TcpKeepalivePacketData(
                    InetAddress.getByAddress(tcpDetails.srcAddress),
                    tcpDetails.srcPort,
                    InetAddress.getByAddress(tcpDetails.dstAddress),
                    tcpDetails.dstPort,
                    packet,
                    tcpDetails.seq, tcpDetails.ack, tcpDetails.rcvWnd, tcpDetails.rcvWndScale,
                    tcpDetails.tos, tcpDetails.ttl);
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

        buf.putShort(ipChecksumOffset, com.android.net.module.util.IpUtils.ipChecksum(buf, 0));
        buf.putShort(tcpChecksumOffset, IpUtils.tcpChecksum(
                buf, 0, IPV4_HEADER_LENGTH, TCP_HEADER_LENGTH));

        return buf.array();
    }

    // TODO: add buildV6Packet.

    /**
     * Get a {@link TcpKeepalivePacketDataParcelable} from {@link KeepalivePacketData}, if the
     * generic class actually contains TCP keepalive data.
     *
     * @deprecated This method is used on R platforms where android.net.TcpKeepalivePacketData was
     * not yet system API. Newer platforms should use android.net.TcpKeepalivePacketData directly.
     *
     * @param data A {@link KeepalivePacketData} that may contain TCP keepalive data.
     * @return A parcelable containing TCP keepalive data, or null if the input data does not
     *         contain TCP keepalive data.
     */
    @Deprecated
    @SuppressWarnings("AndroidFrameworkCompatChange") // API version check used to Log.wtf
    @Nullable
    public static TcpKeepalivePacketDataParcelable parseTcpKeepalivePacketData(
            @Nullable KeepalivePacketData data) {
        if (data == null) return null;

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Log.wtf(TAG, "parseTcpKeepalivePacketData should not be used after R, use "
                    + "TcpKeepalivePacketData instead.");
        }

        // Reconstruct TcpKeepalivePacketData from the packet contained in KeepalivePacketData
        final ByteBuffer buffer = ByteBuffer.wrap(data.getPacket());
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Most of the fields are accessible from the KeepalivePacketData superclass: instead of
        // using Struct to parse everything, just extract the extra fields necessary for
        // TcpKeepalivePacketData.
        final int tcpSeq;
        final int tcpAck;
        final int wndSize;
        final int ipTos;
        final int ttl;
        try {
            // This only support IPv4, because TcpKeepalivePacketData only supports IPv4 for R and
            // below, and this method should not be used on newer platforms.
            tcpSeq = buffer.getInt(IPV4_HEADER_LENGTH + 4);
            tcpAck = buffer.getInt(IPV4_HEADER_LENGTH + 8);
            wndSize = buffer.getShort(IPV4_HEADER_LENGTH + 14);
            ipTos = buffer.get(1);
            ttl = buffer.get(8);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }

        final TcpKeepalivePacketDataParcelable p = new TcpKeepalivePacketDataParcelable();
        p.srcAddress = data.getSrcAddress().getAddress();
        p.srcPort = data.getSrcPort();
        p.dstAddress = data.getDstAddress().getAddress();
        p.dstPort = data.getDstPort();
        p.seq = tcpSeq;
        p.ack = tcpAck;
        // TcpKeepalivePacketData could actually use non-zero wndScale, but this does not affect
        // actual functionality as generated packets will be the same (no wndScale option added)
        p.rcvWnd = wndSize;
        p.rcvWndScale = 0;
        p.tos = ipTos;
        p.ttl = ttl;
        return p;
    }
}
