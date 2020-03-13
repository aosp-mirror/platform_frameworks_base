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
    public final InetAddress srcAddress;

    /** Destination IP address */
    @NonNull
    public final InetAddress dstAddress;

    /** Source port */
    public final int srcPort;

    /** Destination port */
    public final int dstPort;

    /** Packet data. A raw byte string of packet data, not including the link-layer header. */
    private final byte[] mPacket;

    // Note: If you add new fields, please modify the parcelling code in the child classes.


    // This should only be constructed via static factory methods, such as
    // nattKeepalivePacket.
    /**
     * A holding class for data necessary to build a keepalive packet.
     */
    protected KeepalivePacketData(@NonNull InetAddress srcAddress, int srcPort,
            @NonNull InetAddress dstAddress, int dstPort,
                    @NonNull byte[] data) throws InvalidPacketException {
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

    @NonNull
    public byte[] getPacket() {
        return mPacket.clone();
    }

}
