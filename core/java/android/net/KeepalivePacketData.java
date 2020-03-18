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

import static android.net.InvalidPacketException.ERROR_INVALID_IP_ADDRESS;
import static android.net.InvalidPacketException.ERROR_INVALID_PORT;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.util.IpUtils;
import android.util.Log;

import java.net.InetAddress;

/**
 * Represents the actual packets that are sent by the
 * {@link android.net.SocketKeepalive} API.
 * @hide
 */
@SystemApi
public class KeepalivePacketData {
    private static final String TAG = "KeepalivePacketData";

    /** Source IP address */
    @NonNull
    private final InetAddress mSrcAddress;

    /** Destination IP address */
    @NonNull
    private final InetAddress mDstAddress;

    /** Source port */
    private final int mSrcPort;

    /** Destination port */
    private final int mDstPort;

    /** Packet data. A raw byte string of packet data, not including the link-layer header. */
    private final byte[] mPacket;

    // Note: If you add new fields, please modify the parcelling code in the child classes.


    // This should only be constructed via static factory methods, such as
    // nattKeepalivePacket.
    /**
     * A holding class for data necessary to build a keepalive packet.
     */
    protected KeepalivePacketData(@NonNull InetAddress srcAddress,
            @IntRange(from = 0, to = 65535) int srcPort, @NonNull InetAddress dstAddress,
            @IntRange(from = 0, to = 65535) int dstPort,
            @NonNull byte[] data) throws InvalidPacketException {
        this.mSrcAddress = srcAddress;
        this.mDstAddress = dstAddress;
        this.mSrcPort = srcPort;
        this.mDstPort = dstPort;
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

    /** Get source IP address. */
    @NonNull
    public InetAddress getSrcAddress() {
        return mSrcAddress;
    }

    /** Get destination IP address. */
    @NonNull
    public InetAddress getDstAddress() {
        return mDstAddress;
    }

    /** Get source port number. */
    public int getSrcPort() {
        return mSrcPort;
    }

    /** Get destination port number. */
    public int getDstPort() {
        return mDstPort;
    }

    /**
     * Returns a byte array of the given packet data.
     */
    @NonNull
    public byte[] getPacket() {
        return mPacket.clone();
    }

}
