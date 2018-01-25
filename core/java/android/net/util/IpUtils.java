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

package android.net.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

/**
 * @hide
 */
public class IpUtils {
    /**
     * Converts a signed short value to an unsigned int value.  Needed
     * because Java does not have unsigned types.
     */
    private static int intAbs(short v) {
        return v & 0xFFFF;
    }

    /**
     * Performs an IP checksum (used in IP header and across UDP
     * payload) on the specified portion of a ByteBuffer.  The seed
     * allows the checksum to commence with a specified value.
     */
    private static int checksum(ByteBuffer buf, int seed, int start, int end) {
        int sum = seed;
        final int bufPosition = buf.position();

        // set position of original ByteBuffer, so that the ShortBuffer
        // will be correctly initialized
        buf.position(start);
        ShortBuffer shortBuf = buf.asShortBuffer();

        // re-set ByteBuffer position
        buf.position(bufPosition);

        final int numShorts = (end - start) / 2;
        for (int i = 0; i < numShorts; i++) {
            sum += intAbs(shortBuf.get(i));
        }
        start += numShorts * 2;

        // see if a singleton byte remains
        if (end != start) {
            short b = buf.get(start);

            // make it unsigned
            if (b < 0) {
                b += 256;
            }

            sum += b * 256;
        }

        sum = ((sum >> 16) & 0xFFFF) + (sum & 0xFFFF);
        sum = ((sum + ((sum >> 16) & 0xFFFF)) & 0xFFFF);
        int negated = ~sum;
        return intAbs((short) negated);
    }

    private static int pseudoChecksumIPv4(
            ByteBuffer buf, int headerOffset, int protocol, int transportLen) {
        int partial = protocol + transportLen;
        partial += intAbs(buf.getShort(headerOffset + 12));
        partial += intAbs(buf.getShort(headerOffset + 14));
        partial += intAbs(buf.getShort(headerOffset + 16));
        partial += intAbs(buf.getShort(headerOffset + 18));
        return partial;
    }

    private static int pseudoChecksumIPv6(
            ByteBuffer buf, int headerOffset, int protocol, int transportLen) {
        int partial = protocol + transportLen;
        for (int offset = 8; offset < 40; offset += 2) {
            partial += intAbs(buf.getShort(headerOffset + offset));
        }
        return partial;
    }

    private static byte ipversion(ByteBuffer buf, int headerOffset) {
        return (byte) ((buf.get(headerOffset) & (byte) 0xf0) >> 4);
   }

    public static short ipChecksum(ByteBuffer buf, int headerOffset) {
        byte ihl = (byte) (buf.get(headerOffset) & 0x0f);
        return (short) checksum(buf, 0, headerOffset, headerOffset + ihl * 4);
    }

    private static short transportChecksum(ByteBuffer buf, int protocol,
            int ipOffset, int transportOffset, int transportLen) {
        if (transportLen < 0) {
            throw new IllegalArgumentException("Transport length < 0: " + transportLen);
        }
        int sum;
        byte ver = ipversion(buf, ipOffset);
        if (ver == 4) {
            sum = pseudoChecksumIPv4(buf, ipOffset, protocol, transportLen);
        } else if (ver == 6) {
            sum = pseudoChecksumIPv6(buf, ipOffset, protocol, transportLen);
        } else {
            throw new UnsupportedOperationException("Checksum must be IPv4 or IPv6");
        }

        sum = checksum(buf, sum, transportOffset, transportOffset + transportLen);
        if (protocol == IPPROTO_UDP && sum == 0) {
            sum = (short) 0xffff;
        }
        return (short) sum;
    }

    public static short udpChecksum(ByteBuffer buf, int ipOffset, int transportOffset) {
        int transportLen = intAbs(buf.getShort(transportOffset + 4));
        return transportChecksum(buf, IPPROTO_UDP, ipOffset, transportOffset, transportLen);
    }

    public static short tcpChecksum(ByteBuffer buf, int ipOffset, int transportOffset,
            int transportLen) {
        return transportChecksum(buf, IPPROTO_TCP, ipOffset, transportOffset, transportLen);
    }

    public static String addressAndPortToString(InetAddress address, int port) {
        return String.format(
                (address instanceof Inet6Address) ? "[%s]:%d" : "%s:%d",
                address.getHostAddress(), port);
    }

    public static boolean isValidUdpOrTcpPort(int port) {
        return port > 0 && port < 65536;
    }
}
