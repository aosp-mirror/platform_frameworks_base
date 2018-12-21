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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.util.FdEventsReader;
import android.os.Handler;
import android.system.Os;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetSocketAddress;

/**
 * A {@link FdEventsReader} to receive and parse {@link DhcpPacket}.
 * @hide
 */
abstract class DhcpPacketListener extends FdEventsReader<DhcpPacketListener.Payload> {
    static final class Payload {
        protected final byte[] mBytes = new byte[DhcpPacket.MAX_LENGTH];
        protected Inet4Address mSrcAddr;
        protected int mSrcPort;
    }

    DhcpPacketListener(@NonNull Handler handler) {
        super(handler, new Payload());
    }

    @Override
    protected int recvBufSize(@NonNull Payload buffer) {
        return buffer.mBytes.length;
    }

    @Override
    protected final void handlePacket(@NonNull Payload recvbuf, int length) {
        if (recvbuf.mSrcAddr == null) {
            return;
        }

        try {
            final DhcpPacket packet = DhcpPacket.decodeFullPacket(recvbuf.mBytes, length,
                    DhcpPacket.ENCAP_BOOTP);
            onReceive(packet, recvbuf.mSrcAddr, recvbuf.mSrcPort);
        } catch (DhcpPacket.ParseException e) {
            logParseError(recvbuf.mBytes, length, e);
        }
    }

    @Override
    protected int readPacket(@NonNull FileDescriptor fd, @NonNull Payload packetBuffer)
            throws Exception {
        final InetSocketAddress addr = new InetSocketAddress();
        final int read = Os.recvfrom(
                fd, packetBuffer.mBytes, 0, packetBuffer.mBytes.length, 0 /* flags */, addr);

        // Buffers with null srcAddr will be dropped in handlePacket()
        packetBuffer.mSrcAddr = inet4AddrOrNull(addr);
        packetBuffer.mSrcPort = addr.getPort();
        return read;
    }

    @Nullable
    private static Inet4Address inet4AddrOrNull(@NonNull InetSocketAddress addr) {
        return addr.getAddress() instanceof Inet4Address
                ? (Inet4Address) addr.getAddress()
                : null;
    }

    protected abstract void onReceive(@NonNull DhcpPacket packet, @NonNull Inet4Address srcAddr,
            int srcPort);
    protected abstract void logParseError(@NonNull byte[] packet, int length,
            @NonNull DhcpPacket.ParseException e);
}
