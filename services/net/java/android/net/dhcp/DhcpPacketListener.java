/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.dhcp;

import android.annotation.Nullable;
import android.net.util.FdEventsReader;
import android.net.util.PacketReader;
import android.os.Handler;
import android.system.Os;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * A {@link FdEventsReader} to receive and parse {@link DhcpPacket}.
 * @hide
 */
abstract class DhcpPacketListener extends FdEventsReader<DhcpPacketListener.Payload> {
    static final class Payload {
        final byte[] bytes = new byte[DhcpPacket.MAX_LENGTH];
        Inet4Address srcAddr;
    }

    public DhcpPacketListener(Handler handler) {
        super(handler, new Payload());
    }

    @Override
    protected int recvBufSize(Payload buffer) {
        return buffer.bytes.length;
    }

    @Override
    protected final void handlePacket(Payload recvbuf, int length) {
        if (recvbuf.srcAddr == null) {
            return;
        }

        try {
            final DhcpPacket packet = DhcpPacket.decodeFullPacket(recvbuf.bytes, length,
                    DhcpPacket.ENCAP_BOOTP);
            onReceive(packet, recvbuf.srcAddr);
        } catch (DhcpPacket.ParseException e) {
            logParseError(recvbuf.bytes, length, e);
        }
    }

    @Override
    protected int readPacket(FileDescriptor fd, Payload packetBuffer) throws Exception {
        final InetSocketAddress addr = new InetSocketAddress();
        final int read = Os.recvfrom(
                fd, packetBuffer.bytes, 0, packetBuffer.bytes.length, 0 /* flags */, addr);

        // Buffers with null srcAddr will be dropped in handlePacket()
        packetBuffer.srcAddr = inet4AddrOrNull(addr);
        return read;
    }

    @Nullable
    private static Inet4Address inet4AddrOrNull(InetSocketAddress addr) {
        return addr.getAddress() instanceof Inet4Address
                ? (Inet4Address) addr.getAddress()
                : null;
    }

    protected abstract void onReceive(DhcpPacket packet, Inet4Address srcAddr);
    protected abstract void logParseError(byte[] packet, int length, DhcpPacket.ParseException e);
}
