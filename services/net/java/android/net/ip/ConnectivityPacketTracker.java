/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.ip;

import static android.system.OsConstants.*;

import android.net.NetworkUtils;
import android.net.util.BlockingSocketReader;
import android.net.util.ConnectivityPacketSummary;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.system.PacketSocketAddress;
import android.util.Log;
import android.util.LocalLog;

import libcore.io.IoBridge;
import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;


/**
 * Critical connectivity packet tracking daemon.
 *
 * Tracks ARP, DHCPv4, and IPv6 RS/RA/NS/NA packets.
 *
 * This class's constructor, start() and stop() methods must only be called
 * from the same thread on which the passed in |log| is accessed.
 *
 * Log lines include a hexdump of the packet, which can be decoded via:
 *
 *     echo -n H3XSTR1NG | sed -e 's/\([0-9A-F][0-9A-F]\)/\1 /g' -e 's/^/000000 /'
 *                       | text2pcap - -
 *                       | tcpdump -n -vv -e -r -
 *
 * @hide
 */
public class ConnectivityPacketTracker {
    private static final String TAG = ConnectivityPacketTracker.class.getSimpleName();
    private static final boolean DBG = false;
    private static final String MARK_START = "--- START ---";
    private static final String MARK_STOP = "--- STOP ---";

    private final String mTag;
    private final Handler mHandler;
    private final LocalLog mLog;
    private final BlockingSocketReader mPacketListener;

    public ConnectivityPacketTracker(NetworkInterface netif, LocalLog log) {
        final String ifname;
        final int ifindex;
        final byte[] hwaddr;
        final int mtu;

        try {
            ifname = netif.getName();
            ifindex = netif.getIndex();
            hwaddr = netif.getHardwareAddress();
            mtu = netif.getMTU();
        } catch (NullPointerException|SocketException e) {
            throw new IllegalArgumentException("bad network interface", e);
        }

        mTag = TAG + "." + ifname;
        mHandler = new Handler();
        mLog = log;
        mPacketListener = new PacketListener(ifindex, hwaddr, mtu);
    }

    public void start() {
        mLog.log(MARK_START);
        mPacketListener.start();
    }

    public void stop() {
        mPacketListener.stop();
        mLog.log(MARK_STOP);
    }

    private final class PacketListener extends BlockingSocketReader {
        private final int mIfIndex;
        private final byte mHwAddr[];

        PacketListener(int ifindex, byte[] hwaddr, int mtu) {
            super(mtu);
            mIfIndex = ifindex;
            mHwAddr = hwaddr;
        }

        @Override
        protected FileDescriptor createSocket() {
            FileDescriptor s = null;
            try {
                // TODO: Evaluate switching to SOCK_DGRAM and changing the
                // BlockingSocketReader's read() to recvfrom(), so that this
                // might work on non-ethernet-like links (via SLL).
                s = Os.socket(AF_PACKET, SOCK_RAW, 0);
                NetworkUtils.attachControlPacketFilter(s, ARPHRD_ETHER);
                Os.bind(s, new PacketSocketAddress((short) ETH_P_ALL, mIfIndex));
            } catch (ErrnoException | IOException e) {
                logError("Failed to create packet tracking socket: ", e);
                closeSocket(s);
                return null;
            }
            return s;
        }

        @Override
        protected void handlePacket(byte[] recvbuf, int length) {
            final String summary = ConnectivityPacketSummary.summarize(
                    mHwAddr, recvbuf, length);
            if (summary == null) return;

            if (DBG) Log.d(mTag, summary);
            addLogEntry(summary +
                        "\n[" + new String(HexEncoding.encode(recvbuf, 0, length)) + "]");
        }

        @Override
        protected void logError(String msg, Exception e) {
            Log.e(mTag, msg, e);
            addLogEntry(msg + e);
        }

        private void addLogEntry(String entry) {
            mHandler.post(() -> mLog.log(entry));
        }
    }
}
