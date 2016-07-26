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

package android.net.apf;

import static android.system.OsConstants.*;

import android.os.SystemClock;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;
import android.net.ip.IpManager;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.RaEvent;
import android.system.ErrnoException;
import android.system.Os;
import android.system.PacketSocketAddress;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.Thread;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.Arrays;

import libcore.io.IoBridge;

/**
 * For networks that support packet filtering via APF programs, {@code ApfFilter}
 * listens for IPv6 ICMPv6 router advertisements (RAs) and generates APF programs to
 * filter out redundant duplicate ones.
 *
 * Threading model:
 * A collection of RAs we've received is kept in mRas. Generating APF programs uses mRas to
 * know what RAs to filter for, thus generating APF programs is dependent on mRas.
 * mRas can be accessed by multiple threads:
 * - ReceiveThread, which listens for RAs and adds them to mRas, and generates APF programs.
 * - callers of:
 *    - setMulticastFilter(), which can cause an APF program to be generated.
 *    - dump(), which dumps mRas among other things.
 *    - shutdown(), which clears mRas.
 * So access to mRas is synchronized.
 *
 * @hide
 */
public class ApfFilter {

    // Enums describing the outcome of receiving an RA packet.
    private static enum ProcessRaResult {
        MATCH,          // Received RA matched a known RA
        DROPPED,        // Received RA ignored due to MAX_RAS
        PARSE_ERROR,    // Received RA could not be parsed
        ZERO_LIFETIME,  // Received RA had 0 lifetime
        UPDATE_NEW_RA,  // APF program updated for new RA
        UPDATE_EXPIRY   // APF program updated for expiry
    }

    // Thread to listen for RAs.
    @VisibleForTesting
    class ReceiveThread extends Thread {
        private final byte[] mPacket = new byte[1514];
        private final FileDescriptor mSocket;
        private volatile boolean mStopped;

        // Starting time of the RA receiver thread.
        private final long mStart = SystemClock.elapsedRealtime();

        private int mReceivedRas;     // Number of received RAs
        private int mMatchingRas;     // Number of received RAs matching a known RA
        private int mDroppedRas;      // Number of received RAs ignored due to the MAX_RAS limit
        private int mParseErrors;     // Number of received RAs that could not be parsed
        private int mZeroLifetimeRas; // Number of received RAs with a 0 lifetime
        private int mProgramUpdates;  // Number of APF program updates triggered by receiving a RA

        public ReceiveThread(FileDescriptor socket) {
            mSocket = socket;
        }

        public void halt() {
            mStopped = true;
            try {
                // Interrupts the read() call the thread is blocked in.
                IoBridge.closeAndSignalBlockedThreads(mSocket);
            } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            log("begin monitoring");
            while (!mStopped) {
                try {
                    int length = Os.read(mSocket, mPacket, 0, mPacket.length);
                    updateStats(processRa(mPacket, length));
                } catch (IOException|ErrnoException e) {
                    if (!mStopped) {
                        Log.e(TAG, "Read error", e);
                    }
                }
            }
            logStats();
        }

        private void updateStats(ProcessRaResult result) {
            mReceivedRas++;
            switch(result) {
                case MATCH:
                    mMatchingRas++;
                    return;
                case DROPPED:
                    mDroppedRas++;
                    return;
                case PARSE_ERROR:
                    mParseErrors++;
                    return;
                case ZERO_LIFETIME:
                    mZeroLifetimeRas++;
                    return;
                case UPDATE_EXPIRY:
                    mMatchingRas++;
                    mProgramUpdates++;
                    return;
                case UPDATE_NEW_RA:
                    mProgramUpdates++;
                    return;
            }
        }

        private void logStats() {
            long durationMs = SystemClock.elapsedRealtime() - mStart;
            int maxSize = mApfCapabilities.maximumApfProgramSize;
            mMetricsLog.log(new ApfStats(durationMs, mReceivedRas, mMatchingRas, mDroppedRas,
                     mZeroLifetimeRas, mParseErrors, mProgramUpdates, maxSize));
        }
    }

    private static final String TAG = "ApfFilter";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int ETH_HEADER_LEN = 14;
    private static final int ETH_DEST_ADDR_OFFSET = 0;
    private static final int ETH_ETHERTYPE_OFFSET = 12;
    private static final byte[] ETH_BROADCAST_MAC_ADDRESS = new byte[]{
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };
    // TODO: Make these offsets relative to end of link-layer header; don't include ETH_HEADER_LEN.
    private static final int IPV4_FRAGMENT_OFFSET_OFFSET = ETH_HEADER_LEN + 6;
    // Endianness is not an issue for this constant because the APF interpreter always operates in
    // network byte order.
    private static final int IPV4_FRAGMENT_OFFSET_MASK = 0x1fff;
    private static final int IPV4_PROTOCOL_OFFSET = ETH_HEADER_LEN + 9;
    private static final int IPV4_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 16;
    private static final int IPV4_ANY_HOST_ADDRESS = 0;

    private static final int IPV6_NEXT_HEADER_OFFSET = ETH_HEADER_LEN + 6;
    private static final int IPV6_SRC_ADDR_OFFSET = ETH_HEADER_LEN + 8;
    private static final int IPV6_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 24;
    private static final int IPV6_HEADER_LEN = 40;
    // The IPv6 all nodes address ff02::1
    private static final byte[] IPV6_ALL_NODES_ADDRESS =
            new byte[]{ (byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };

    private static final int ICMP6_TYPE_OFFSET = ETH_HEADER_LEN + IPV6_HEADER_LEN;
    private static final int ICMP6_NEIGHBOR_ANNOUNCEMENT = 136;
    private static final int ICMP6_ROUTER_ADVERTISEMENT = 134;

    // NOTE: this must be added to the IPv4 header length in IPV4_HEADER_SIZE_MEMORY_SLOT
    private static final int UDP_DESTINATION_PORT_OFFSET = ETH_HEADER_LEN + 2;
    private static final int UDP_HEADER_LEN = 8;

    private static final int DHCP_CLIENT_PORT = 68;
    // NOTE: this must be added to the IPv4 header length in IPV4_HEADER_SIZE_MEMORY_SLOT
    private static final int DHCP_CLIENT_MAC_OFFSET = ETH_HEADER_LEN + UDP_HEADER_LEN + 28;

    private static final int ARP_HEADER_OFFSET = ETH_HEADER_LEN;
    private static final int ARP_OPCODE_OFFSET = ARP_HEADER_OFFSET + 6;
    private static final short ARP_OPCODE_REQUEST = 1;
    private static final short ARP_OPCODE_REPLY = 2;
    private static final byte[] ARP_IPV4_HEADER = new byte[]{
            0, 1, // Hardware type: Ethernet (1)
            8, 0, // Protocol type: IP (0x0800)
            6,    // Hardware size: 6
            4,    // Protocol size: 4
    };
    private static final int ARP_TARGET_IP_ADDRESS_OFFSET = ETH_HEADER_LEN + 24;

    private final ApfCapabilities mApfCapabilities;
    private final IpManager.Callback mIpManagerCallback;
    private final NetworkInterface mNetworkInterface;
    private final IpConnectivityLog mMetricsLog;
    @VisibleForTesting
    byte[] mHardwareAddress;
    @VisibleForTesting
    ReceiveThread mReceiveThread;
    @GuardedBy("this")
    private long mUniqueCounter;
    @GuardedBy("this")
    private boolean mMulticastFilter;
    // Our IPv4 address, if we have just one, otherwise null.
    @GuardedBy("this")
    private byte[] mIPv4Address;

    @VisibleForTesting
    ApfFilter(ApfCapabilities apfCapabilities, NetworkInterface networkInterface,
            IpManager.Callback ipManagerCallback, boolean multicastFilter, IpConnectivityLog log) {
        mApfCapabilities = apfCapabilities;
        mIpManagerCallback = ipManagerCallback;
        mNetworkInterface = networkInterface;
        mMulticastFilter = multicastFilter;
        mMetricsLog = log;

        maybeStartFilter();
    }

    private void log(String s) {
        Log.d(TAG, "(" + mNetworkInterface.getName() + "): " + s);
    }

    @GuardedBy("this")
    private long getUniqueNumberLocked() {
        return mUniqueCounter++;
    }

    /**
     * Attempt to start listening for RAs and, if RAs are received, generating and installing
     * filters to ignore useless RAs.
     */
    @VisibleForTesting
    void maybeStartFilter() {
        FileDescriptor socket;
        try {
            mHardwareAddress = mNetworkInterface.getHardwareAddress();
            synchronized(this) {
                // Install basic filters
                installNewProgramLocked();
            }
            socket = Os.socket(AF_PACKET, SOCK_RAW, ETH_P_IPV6);
            PacketSocketAddress addr = new PacketSocketAddress((short) ETH_P_IPV6,
                    mNetworkInterface.getIndex());
            Os.bind(socket, addr);
            NetworkUtils.attachRaFilter(socket, mApfCapabilities.apfPacketFormat);
        } catch(SocketException|ErrnoException e) {
            Log.e(TAG, "Error starting filter", e);
            return;
        }
        mReceiveThread = new ReceiveThread(socket);
        mReceiveThread.start();
    }

    // Returns seconds since Unix Epoch.
    // TODO: use SystemClock.elapsedRealtime() instead
    private static long curTime() {
        return System.currentTimeMillis() / DateUtils.SECOND_IN_MILLIS;
    }

    // A class to hold information about an RA.
    private class Ra {
        // From RFC4861:
        private static final int ICMP6_RA_HEADER_LEN = 16;
        private static final int ICMP6_RA_CHECKSUM_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + 2;
        private static final int ICMP6_RA_CHECKSUM_LEN = 2;
        private static final int ICMP6_RA_OPTION_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + ICMP6_RA_HEADER_LEN;
        private static final int ICMP6_RA_ROUTER_LIFETIME_OFFSET =
                ETH_HEADER_LEN + IPV6_HEADER_LEN + 6;
        private static final int ICMP6_RA_ROUTER_LIFETIME_LEN = 2;
        // Prefix information option.
        private static final int ICMP6_PREFIX_OPTION_TYPE = 3;
        private static final int ICMP6_PREFIX_OPTION_LEN = 32;
        private static final int ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET = 4;
        private static final int ICMP6_PREFIX_OPTION_VALID_LIFETIME_LEN = 4;
        private static final int ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET = 8;
        private static final int ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_LEN = 4;

        // From RFC6106: Recursive DNS Server option
        private static final int ICMP6_RDNSS_OPTION_TYPE = 25;
        // From RFC6106: DNS Search List option
        private static final int ICMP6_DNSSL_OPTION_TYPE = 31;

        // From RFC4191: Route Information option
        private static final int ICMP6_ROUTE_INFO_OPTION_TYPE = 24;
        // Above three options all have the same format:
        private static final int ICMP6_4_BYTE_LIFETIME_OFFSET = 4;
        private static final int ICMP6_4_BYTE_LIFETIME_LEN = 4;

        // Note: mPacket's position() cannot be assumed to be reset.
        private final ByteBuffer mPacket;
        // List of binary ranges that include the whole packet except the lifetimes.
        // Pairs consist of offset and length.
        private final ArrayList<Pair<Integer, Integer>> mNonLifetimes =
                new ArrayList<Pair<Integer, Integer>>();
        // Minimum lifetime in packet
        long mMinLifetime;
        // When the packet was last captured, in seconds since Unix Epoch
        long mLastSeen;

        // For debugging only. Offsets into the packet where PIOs are.
        private final ArrayList<Integer> mPrefixOptionOffsets = new ArrayList<>();

        // For debugging only. Offsets into the packet where RDNSS options are.
        private final ArrayList<Integer> mRdnssOptionOffsets = new ArrayList<>();

        // For debugging only. How many times this RA was seen.
        int seenCount = 0;

        // For debugging only. Returns the hex representation of the last matching packet.
        String getLastMatchingPacket() {
            return HexDump.toHexString(mPacket.array(), 0, mPacket.capacity(),
                    false /* lowercase */);
        }

        // For debugging only. Returns the string representation of the IPv6 address starting at
        // position pos in the packet.
        private String IPv6AddresstoString(int pos) {
            try {
                byte[] array = mPacket.array();
                // Can't just call copyOfRange() and see if it throws, because if it reads past the
                // end it pads with zeros instead of throwing.
                if (pos < 0 || pos + 16 > array.length || pos + 16 < pos) {
                    return "???";
                }
                byte[] addressBytes = Arrays.copyOfRange(array, pos, pos + 16);
                InetAddress address = (Inet6Address) InetAddress.getByAddress(addressBytes);
                return address.getHostAddress();
            } catch (UnsupportedOperationException e) {
                // array() failed. Cannot happen, mPacket is array-backed and read-write.
                return "???";
            } catch (ClassCastException | UnknownHostException e) {
                // Cannot happen.
                return "???";
            }
        }

        // Can't be static because it's in a non-static inner class.
        // TODO: Make this static once RA is its own class.
        private int uint8(byte b) {
            return b & 0xff;
        }

        private int uint16(short s) {
            return s & 0xffff;
        }

        private long uint32(int i) {
            return i & 0xffffffffL;
        }

        private long getUint16(ByteBuffer buffer, int position) {
            return uint16(buffer.getShort(position));
        }

        private long getUint32(ByteBuffer buffer, int position) {
            return uint32(buffer.getInt(position));
        }

        private void prefixOptionToString(StringBuffer sb, int offset) {
            String prefix = IPv6AddresstoString(offset + 16);
            int length = uint8(mPacket.get(offset + 2));
            long valid = mPacket.getInt(offset + 4);
            long preferred = mPacket.getInt(offset + 8);
            sb.append(String.format("%s/%d %ds/%ds ", prefix, length, valid, preferred));
        }

        private void rdnssOptionToString(StringBuffer sb, int offset) {
            int optLen = uint8(mPacket.get(offset + 1)) * 8;
            if (optLen < 24) return;  // Malformed or empty.
            long lifetime = uint32(mPacket.getInt(offset + 4));
            int numServers = (optLen - 8) / 16;
            sb.append("DNS ").append(lifetime).append("s");
            for (int server = 0; server < numServers; server++) {
                sb.append(" ").append(IPv6AddresstoString(offset + 8 + 16 * server));
            }
        }

        public String toString() {
            try {
                StringBuffer sb = new StringBuffer();
                sb.append(String.format("RA %s -> %s %ds ",
                        IPv6AddresstoString(IPV6_SRC_ADDR_OFFSET),
                        IPv6AddresstoString(IPV6_DEST_ADDR_OFFSET),
                        uint16(mPacket.getShort(ICMP6_RA_ROUTER_LIFETIME_OFFSET))));
                for (int i: mPrefixOptionOffsets) {
                    prefixOptionToString(sb, i);
                }
                for (int i: mRdnssOptionOffsets) {
                    rdnssOptionToString(sb, i);
                }
                return sb.toString();
            } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
                return "<Malformed RA>";
            }
        }

        /**
         * Add a binary range of the packet that does not include a lifetime to mNonLifetimes.
         * Assumes mPacket.position() is as far as we've parsed the packet.
         * @param lastNonLifetimeStart offset within packet of where the last binary range of
         *                             data not including a lifetime.
         * @param lifetimeOffset offset from mPacket.position() to the next lifetime data.
         * @param lifetimeLength length of the next lifetime data.
         * @return offset within packet of where the next binary range of data not including
         *         a lifetime. This can be passed into the next invocation of this function
         *         via {@code lastNonLifetimeStart}.
         */
        private int addNonLifetime(int lastNonLifetimeStart, int lifetimeOffset,
                int lifetimeLength) {
            lifetimeOffset += mPacket.position();
            mNonLifetimes.add(new Pair<Integer, Integer>(lastNonLifetimeStart,
                    lifetimeOffset - lastNonLifetimeStart));
            return lifetimeOffset + lifetimeLength;
        }

        private int addNonLifetimeU32(int lastNonLifetimeStart) {
            return addNonLifetime(lastNonLifetimeStart,
                    ICMP6_4_BYTE_LIFETIME_OFFSET, ICMP6_4_BYTE_LIFETIME_LEN);
        }

        // Note that this parses RA and may throw IllegalArgumentException (from
        // Buffer.position(int) or due to an invalid-length option) or IndexOutOfBoundsException
        // (from ByteBuffer.get(int) ) if parsing encounters something non-compliant with
        // specifications.
        Ra(byte[] packet, int length) {
            mPacket = ByteBuffer.wrap(Arrays.copyOf(packet, length));
            mLastSeen = curTime();

            // Sanity check packet in case a packet arrives before we attach RA filter
            // to our packet socket. b/29586253
            if (getUint16(mPacket, ETH_ETHERTYPE_OFFSET) != ETH_P_IPV6 ||
                    uint8(mPacket.get(IPV6_NEXT_HEADER_OFFSET)) != IPPROTO_ICMPV6 ||
                    uint8(mPacket.get(ICMP6_TYPE_OFFSET)) != ICMP6_ROUTER_ADVERTISEMENT) {
                throw new IllegalArgumentException("Not an ICMP6 router advertisement");
            }


            RaEvent.Builder builder = new RaEvent.Builder();

            // Ignore the checksum.
            int lastNonLifetimeStart = addNonLifetime(0,
                    ICMP6_RA_CHECKSUM_OFFSET,
                    ICMP6_RA_CHECKSUM_LEN);

            // Parse router lifetime
            lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                    ICMP6_RA_ROUTER_LIFETIME_OFFSET,
                    ICMP6_RA_ROUTER_LIFETIME_LEN);
            builder.updateRouterLifetime(getUint16(mPacket, ICMP6_RA_ROUTER_LIFETIME_OFFSET));

            // Ensures that the RA is not truncated.
            mPacket.position(ICMP6_RA_OPTION_OFFSET);
            while (mPacket.hasRemaining()) {
                final int position = mPacket.position();
                final int optionType = uint8(mPacket.get(position));
                final int optionLength = uint8(mPacket.get(position + 1)) * 8;
                long lifetime;
                switch (optionType) {
                    case ICMP6_PREFIX_OPTION_TYPE:
                        // Parse valid lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_LEN);
                        lifetime = getUint32(mPacket,
                                position + ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET);
                        builder.updatePrefixValidLifetime(lifetime);
                        // Parse preferred lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_LEN);
                        lifetime = getUint32(mPacket,
                                position + ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET);
                        builder.updatePrefixPreferredLifetime(lifetime);
                        mPrefixOptionOffsets.add(position);
                        break;
                    // These three options have the same lifetime offset and size, and
                    // are processed with the same specialized addNonLifetimeU32:
                    case ICMP6_RDNSS_OPTION_TYPE:
                        mRdnssOptionOffsets.add(position);
                        lastNonLifetimeStart = addNonLifetimeU32(lastNonLifetimeStart);
                        lifetime = getUint32(mPacket, position + ICMP6_4_BYTE_LIFETIME_OFFSET);
                        builder.updateRdnssLifetime(lifetime);
                        break;
                    case ICMP6_ROUTE_INFO_OPTION_TYPE:
                        lastNonLifetimeStart = addNonLifetimeU32(lastNonLifetimeStart);
                        lifetime = getUint32(mPacket, position + ICMP6_4_BYTE_LIFETIME_OFFSET);
                        builder.updateRouteInfoLifetime(lifetime);
                        break;
                    case ICMP6_DNSSL_OPTION_TYPE:
                        lastNonLifetimeStart = addNonLifetimeU32(lastNonLifetimeStart);
                        lifetime = getUint32(mPacket, position + ICMP6_4_BYTE_LIFETIME_OFFSET);
                        builder.updateDnsslLifetime(lifetime);
                        break;
                    default:
                        // RFC4861 section 4.2 dictates we ignore unknown options for fowards
                        // compatibility.
                        break;
                }
                if (optionLength <= 0) {
                    throw new IllegalArgumentException(String.format(
                        "Invalid option length opt=%d len=%d", optionType, optionLength));
                }
                mPacket.position(position + optionLength);
            }
            // Mark non-lifetime bytes since last lifetime.
            addNonLifetime(lastNonLifetimeStart, 0, 0);
            mMinLifetime = minLifetime(packet, length);
            mMetricsLog.log(builder.build());
        }

        // Ignoring lifetimes (which may change) does {@code packet} match this RA?
        boolean matches(byte[] packet, int length) {
            if (length != mPacket.capacity()) return false;
            byte[] referencePacket = mPacket.array();
            for (Pair<Integer, Integer> nonLifetime : mNonLifetimes) {
                for (int i = nonLifetime.first; i < (nonLifetime.first + nonLifetime.second); i++) {
                    if (packet[i] != referencePacket[i]) return false;
                }
            }
            return true;
        }

        // What is the minimum of all lifetimes within {@code packet} in seconds?
        // Precondition: matches(packet, length) already returned true.
        long minLifetime(byte[] packet, int length) {
            long minLifetime = Long.MAX_VALUE;
            // Wrap packet in ByteBuffer so we can read big-endian values easily
            ByteBuffer byteBuffer = ByteBuffer.wrap(packet);
            for (int i = 0; (i + 1) < mNonLifetimes.size(); i++) {
                int offset = mNonLifetimes.get(i).first + mNonLifetimes.get(i).second;

                // The checksum is in mNonLifetimes, but it's not a lifetime.
                if (offset == ICMP6_RA_CHECKSUM_OFFSET) {
                     continue;
                }

                final int lifetimeLength = mNonLifetimes.get(i+1).first - offset;
                final long optionLifetime;
                switch (lifetimeLength) {
                    case 2:
                        optionLifetime = uint16(byteBuffer.getShort(offset));
                        break;
                    case 4:
                        optionLifetime = uint32(byteBuffer.getInt(offset));
                        break;
                    default:
                        throw new IllegalStateException("bogus lifetime size " + lifetimeLength);
                }
                minLifetime = Math.min(minLifetime, optionLifetime);
            }
            return minLifetime;
        }

        // How many seconds does this RA's have to live, taking into account the fact
        // that we might have seen it a while ago.
        long currentLifetime() {
            return mMinLifetime - (curTime() - mLastSeen);
        }

        boolean isExpired() {
            // TODO: We may want to handle 0 lifetime RAs differently, if they are common. We'll
            // have to calculte the filter lifetime specially as a fraction of 0 is still 0.
            return currentLifetime() <= 0;
        }

        // Append a filter for this RA to {@code gen}. Jump to DROP_LABEL if it should be dropped.
        // Jump to the next filter if packet doesn't match this RA.
        @GuardedBy("ApfFilter.this")
        long generateFilterLocked(ApfGenerator gen) throws IllegalInstructionException {
            String nextFilterLabel = "Ra" + getUniqueNumberLocked();
            // Skip if packet is not the right size
            gen.addLoadFromMemory(Register.R0, gen.PACKET_SIZE_MEMORY_SLOT);
            gen.addJumpIfR0NotEquals(mPacket.capacity(), nextFilterLabel);
            int filterLifetime = (int)(currentLifetime() / FRACTION_OF_LIFETIME_TO_FILTER);
            // Skip filter if expired
            gen.addLoadFromMemory(Register.R0, gen.FILTER_AGE_MEMORY_SLOT);
            gen.addJumpIfR0GreaterThan(filterLifetime, nextFilterLabel);
            for (int i = 0; i < mNonLifetimes.size(); i++) {
                // Generate code to match the packet bytes
                Pair<Integer, Integer> nonLifetime = mNonLifetimes.get(i);
                // Don't generate JNEBS instruction for 0 bytes as it always fails the
                // ASSERT_FORWARD_IN_PROGRAM(pc + cmp_imm - 1) check where cmp_imm is
                // the number of bytes to compare. nonLifetime is zero between the
                // valid and preferred lifetimes in the prefix option.
                if (nonLifetime.second != 0) {
                    gen.addLoadImmediate(Register.R0, nonLifetime.first);
                    gen.addJumpIfBytesNotEqual(Register.R0,
                            Arrays.copyOfRange(mPacket.array(), nonLifetime.first,
                                               nonLifetime.first + nonLifetime.second),
                            nextFilterLabel);
                }
                // Generate code to test the lifetimes haven't gone down too far
                if ((i + 1) < mNonLifetimes.size()) {
                    Pair<Integer, Integer> nextNonLifetime = mNonLifetimes.get(i + 1);
                    int offset = nonLifetime.first + nonLifetime.second;
                    // Skip the checksum.
                    if (offset == ICMP6_RA_CHECKSUM_OFFSET) {
                        continue;
                    }
                    int length = nextNonLifetime.first - offset;
                    switch (length) {
                        case 4: gen.addLoad32(Register.R0, offset); break;
                        case 2: gen.addLoad16(Register.R0, offset); break;
                        default: throw new IllegalStateException("bogus lifetime size " + length);
                    }
                    gen.addJumpIfR0LessThan(filterLifetime, nextFilterLabel);
                }
            }
            gen.addJump(gen.DROP_LABEL);
            gen.defineLabel(nextFilterLabel);
            return filterLifetime;
        }
    }

    // Maximum number of RAs to filter for.
    private static final int MAX_RAS = 10;

    @GuardedBy("this")
    private ArrayList<Ra> mRas = new ArrayList<Ra>();

    // There is always some marginal benefit to updating the installed APF program when an RA is
    // seen because we can extend the program's lifetime slightly, but there is some cost to
    // updating the program, so don't bother unless the program is going to expire soon. This
    // constant defines "soon" in seconds.
    private static final long MAX_PROGRAM_LIFETIME_WORTH_REFRESHING = 30;
    // We don't want to filter an RA for it's whole lifetime as it'll be expired by the time we ever
    // see a refresh.  Using half the lifetime might be a good idea except for the fact that
    // packets may be dropped, so let's use 6.
    private static final int FRACTION_OF_LIFETIME_TO_FILTER = 6;

    // When did we last install a filter program? In seconds since Unix Epoch.
    @GuardedBy("this")
    private long mLastTimeInstalledProgram;
    // How long should the last installed filter program live for? In seconds.
    @GuardedBy("this")
    private long mLastInstalledProgramMinLifetime;

    // For debugging only. The last program installed.
    @GuardedBy("this")
    private byte[] mLastInstalledProgram;

    // For debugging only. How many times the program was updated since we started.
    @GuardedBy("this")
    private int mNumProgramUpdates;

    /**
     * Generate filter code to process ARP packets. Execution of this code ends in either the
     * DROP_LABEL or PASS_LABEL and does not fall off the end.
     * Preconditions:
     *  - Packet being filtered is ARP
     */
    @GuardedBy("this")
    private void generateArpFilterLocked(ApfGenerator gen) throws IllegalInstructionException {
        // Here's a basic summary of what the ARP filter program does:
        //
        // if not ARP IPv4
        //   pass
        // if not ARP IPv4 reply or request
        //   pass
        // if unicast ARP reply
        //   pass
        // if interface has no IPv4 address
        //   if target ip is 0.0.0.0
        //      drop
        // else
        //   if target ip is not the interface ip
        //      drop
        // pass

        final String checkTargetIPv4 = "checkTargetIPv4";

        // Pass if not ARP IPv4.
        gen.addLoadImmediate(Register.R0, ARP_HEADER_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, ARP_IPV4_HEADER, gen.PASS_LABEL);

        // Pass if unknown ARP opcode.
        gen.addLoad16(Register.R0, ARP_OPCODE_OFFSET);
        gen.addJumpIfR0Equals(ARP_OPCODE_REQUEST, checkTargetIPv4); // Skip to unicast check
        gen.addJumpIfR0NotEquals(ARP_OPCODE_REPLY, gen.PASS_LABEL);

        // Pass if unicast reply.
        gen.addLoadImmediate(Register.R0, ETH_DEST_ADDR_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, gen.PASS_LABEL);

        // Either a unicast request, a unicast reply, or a broadcast reply.
        gen.defineLabel(checkTargetIPv4);
        if (mIPv4Address == null) {
            // When there is no IPv4 address, drop GARP replies (b/29404209).
            gen.addLoad32(Register.R0, ARP_TARGET_IP_ADDRESS_OFFSET);
            gen.addJumpIfR0Equals(IPV4_ANY_HOST_ADDRESS, gen.DROP_LABEL);
        } else {
            // When there is an IPv4 address, drop unicast/broadcast requests
            // and broadcast replies with a different target IPv4 address.
            gen.addLoadImmediate(Register.R0, ARP_TARGET_IP_ADDRESS_OFFSET);
            gen.addJumpIfBytesNotEqual(Register.R0, mIPv4Address, gen.DROP_LABEL);
        }

        gen.addJump(gen.PASS_LABEL);
    }

    /**
     * Generate filter code to process IPv4 packets. Execution of this code ends in either the
     * DROP_LABEL or PASS_LABEL and does not fall off the end.
     * Preconditions:
     *  - Packet being filtered is IPv4
     */
    @GuardedBy("this")
    private void generateIPv4FilterLocked(ApfGenerator gen) throws IllegalInstructionException {
        // Here's a basic summary of what the IPv4 filter program does:
        //
        // if filtering multicast (i.e. multicast lock not held):
        //   if it's multicast:
        //     drop
        //   if it's not broadcast:
        //     pass
        //   if it's not DHCP destined to our MAC:
        //     drop
        // pass

        if (mMulticastFilter) {
            // Check for multicast destination address range
            gen.addLoad8(Register.R0, IPV4_DEST_ADDR_OFFSET);
            gen.addAnd(0xf0);
            gen.addJumpIfR0Equals(0xe0, gen.DROP_LABEL);

            // Drop all broadcasts besides DHCP addressed to us
            // If not a broadcast packet, pass
            gen.addLoadImmediate(Register.R0, ETH_DEST_ADDR_OFFSET);
            gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, gen.PASS_LABEL);
            // If not UDP, drop
            gen.addLoad8(Register.R0, IPV4_PROTOCOL_OFFSET);
            gen.addJumpIfR0NotEquals(IPPROTO_UDP, gen.DROP_LABEL);
            // If fragment, drop. This matches the BPF filter installed by the DHCP client.
            gen.addLoad16(Register.R0, IPV4_FRAGMENT_OFFSET_OFFSET);
            gen.addJumpIfR0AnyBitsSet(IPV4_FRAGMENT_OFFSET_MASK, gen.DROP_LABEL);
            // If not to DHCP client port, drop
            gen.addLoadFromMemory(Register.R1, gen.IPV4_HEADER_SIZE_MEMORY_SLOT);
            gen.addLoad16Indexed(Register.R0, UDP_DESTINATION_PORT_OFFSET);
            gen.addJumpIfR0NotEquals(DHCP_CLIENT_PORT, gen.DROP_LABEL);
            // If not DHCP to our MAC address, drop
            gen.addLoadImmediate(Register.R0, DHCP_CLIENT_MAC_OFFSET);
            // NOTE: Relies on R1 containing IPv4 header offset.
            gen.addAddR1();
            gen.addJumpIfBytesNotEqual(Register.R0, mHardwareAddress, gen.DROP_LABEL);
        }

        // Otherwise, pass
        gen.addJump(gen.PASS_LABEL);
    }


    /**
     * Generate filter code to process IPv6 packets. Execution of this code ends in either the
     * DROP_LABEL or PASS_LABEL, or falls off the end for ICMPv6 packets.
     * Preconditions:
     *  - Packet being filtered is IPv6
     */
    @GuardedBy("this")
    private void generateIPv6FilterLocked(ApfGenerator gen) throws IllegalInstructionException {
        // Here's a basic summary of what the IPv6 filter program does:
        //
        // if it's not ICMPv6:
        //   if it's multicast and we're dropping multicast:
        //     drop
        //   pass
        // if it's ICMPv6 NA to ff02::1:
        //   drop

        gen.addLoad8(Register.R0, IPV6_NEXT_HEADER_OFFSET);

        // Drop multicast if the multicast filter is enabled.
        if (mMulticastFilter) {
            // Don't touch ICMPv6 multicast here, we deal with it in more detail later.
            String skipIpv6MulticastFilterLabel = "skipIPv6MulticastFilter";
            gen.addJumpIfR0Equals(IPPROTO_ICMPV6, skipIpv6MulticastFilterLabel);

            // Drop all other packets sent to ff00::/8.
            gen.addLoad8(Register.R0, IPV6_DEST_ADDR_OFFSET);
            gen.addJumpIfR0Equals(0xff, gen.DROP_LABEL);
            // Not multicast and not ICMPv6. Pass.
            gen.addJump(gen.PASS_LABEL);
            gen.defineLabel(skipIpv6MulticastFilterLabel);
        } else {
            // If not ICMPv6, pass.
            gen.addJumpIfR0NotEquals(IPPROTO_ICMPV6, gen.PASS_LABEL);
        }

        // Add unsolicited multicast neighbor announcements filter
        String skipUnsolicitedMulticastNALabel = "skipUnsolicitedMulticastNA";
        // If not neighbor announcements, skip unsolicited multicast NA filter
        gen.addLoad8(Register.R0, ICMP6_TYPE_OFFSET);
        gen.addJumpIfR0NotEquals(ICMP6_NEIGHBOR_ANNOUNCEMENT, skipUnsolicitedMulticastNALabel);
        // If to ff02::1, drop
        // TODO: Drop only if they don't contain the address of on-link neighbours.
        gen.addLoadImmediate(Register.R0, IPV6_DEST_ADDR_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, IPV6_ALL_NODES_ADDRESS,
                skipUnsolicitedMulticastNALabel);
        gen.addJump(gen.DROP_LABEL);
        gen.defineLabel(skipUnsolicitedMulticastNALabel);
    }

    /**
     * Begin generating an APF program to:
     * <ul>
     * <li>Drop ARP requests not for us, if mIPv4Address is set,
     * <li>Drop IPv4 broadcast packets, except DHCP destined to our MAC,
     * <li>Drop IPv4 multicast packets, if mMulticastFilter,
     * <li>Pass all other IPv4 packets,
     * <li>Drop all broadcast non-IP non-ARP packets.
     * <li>Pass all non-ICMPv6 IPv6 packets,
     * <li>Pass all non-IPv4 and non-IPv6 packets,
     * <li>Drop IPv6 ICMPv6 NAs to ff02::1.
     * <li>Let execution continue off the end of the program for IPv6 ICMPv6 packets. This allows
     *     insertion of RA filters here, or if there aren't any, just passes the packets.
     * </ul>
     */
    @GuardedBy("this")
    private ApfGenerator beginProgramLocked() throws IllegalInstructionException {
        ApfGenerator gen = new ApfGenerator();
        // This is guaranteed to return true because of the check in maybeCreate.
        gen.setApfVersion(mApfCapabilities.apfVersionSupported);

        // Here's a basic summary of what the initial program does:
        //
        // if it's ARP:
        //   insert ARP filter to drop or pass these appropriately
        // if it's IPv4:
        //   insert IPv4 filter to drop or pass these appropriately
        // if it's not IPv6:
        //   if it's broadcast:
        //     drop
        //   pass
        // insert IPv6 filter to drop, pass, or fall off the end for ICMPv6 packets

        // Add ARP filters:
        String skipArpFiltersLabel = "skipArpFilters";
        gen.addLoad16(Register.R0, ETH_ETHERTYPE_OFFSET);
        gen.addJumpIfR0NotEquals(ETH_P_ARP, skipArpFiltersLabel);
        generateArpFilterLocked(gen);
        gen.defineLabel(skipArpFiltersLabel);

        // Add IPv4 filters:
        String skipIPv4FiltersLabel = "skipIPv4Filters";
        // NOTE: Relies on R0 containing ethertype. This is safe because if we got here, we did not
        // execute the ARP filter, since that filter does not fall through, but either drops or
        // passes.
        gen.addJumpIfR0NotEquals(ETH_P_IP, skipIPv4FiltersLabel);
        generateIPv4FilterLocked(gen);
        gen.defineLabel(skipIPv4FiltersLabel);

        // Check for IPv6:
        // NOTE: Relies on R0 containing ethertype. This is safe because if we got here, we did not
        // execute the ARP or IPv4 filters, since those filters do not fall through, but either
        // drop or pass.
        String ipv6FilterLabel = "IPv6Filters";
        gen.addJumpIfR0Equals(ETH_P_IPV6, ipv6FilterLabel);

        // Drop non-IP non-ARP broadcasts, pass the rest
        gen.addLoadImmediate(Register.R0, ETH_DEST_ADDR_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, gen.PASS_LABEL);
        gen.addJump(gen.DROP_LABEL);

        // Add IPv6 filters:
        gen.defineLabel(ipv6FilterLabel);
        generateIPv6FilterLocked(gen);
        return gen;
    }

    /**
     * Generate and install a new filter program.
     */
    @GuardedBy("this")
    @VisibleForTesting
    void installNewProgramLocked() {
        purgeExpiredRasLocked();
        ArrayList<Ra> rasToFilter = new ArrayList<>();
        final byte[] program;
        long programMinLifetime = Long.MAX_VALUE;
        try {
            // Step 1: Determine how many RA filters we can fit in the program.
            ApfGenerator gen = beginProgramLocked();
            for (Ra ra : mRas) {
                ra.generateFilterLocked(gen);
                // Stop if we get too big.
                if (gen.programLengthOverEstimate() > mApfCapabilities.maximumApfProgramSize) break;
                rasToFilter.add(ra);
            }
            // Step 2: Actually generate the program
            gen = beginProgramLocked();
            for (Ra ra : rasToFilter) {
                programMinLifetime = Math.min(programMinLifetime, ra.generateFilterLocked(gen));
            }
            // Execution will reach the end of the program if no filters match, which will pass the
            // packet to the AP.
            program = gen.generate();
        } catch (IllegalInstructionException e) {
            Log.e(TAG, "Program failed to generate: ", e);
            return;
        }
        mLastTimeInstalledProgram = curTime();
        mLastInstalledProgramMinLifetime = programMinLifetime;
        mLastInstalledProgram = program;
        mNumProgramUpdates++;

        if (VDBG) {
            hexDump("Installing filter: ", program, program.length);
        }
        mIpManagerCallback.installPacketFilter(program);
        int flags = ApfProgramEvent.flagsFor(mIPv4Address != null, mMulticastFilter);
        mMetricsLog.log(new ApfProgramEvent(
                programMinLifetime, rasToFilter.size(), mRas.size(), program.length, flags));
    }

    /**
     * Returns {@code true} if a new program should be installed because the current one dies soon.
     */
    private boolean shouldInstallnewProgram() {
        long expiry = mLastTimeInstalledProgram + mLastInstalledProgramMinLifetime;
        return expiry < curTime() + MAX_PROGRAM_LIFETIME_WORTH_REFRESHING;
    }

    private void hexDump(String msg, byte[] packet, int length) {
        log(msg + HexDump.toHexString(packet, 0, length, false /* lowercase */));
    }

    @GuardedBy("this")
    private void purgeExpiredRasLocked() {
        for (int i = 0; i < mRas.size();) {
            if (mRas.get(i).isExpired()) {
                log("Expiring " + mRas.get(i));
                mRas.remove(i);
            } else {
                i++;
            }
        }
    }

    /**
     * Process an RA packet, updating the list of known RAs and installing a new APF program
     * if the current APF program should be updated.
     * @return a ProcessRaResult enum describing what action was performed.
     */
    private synchronized ProcessRaResult processRa(byte[] packet, int length) {
        if (VDBG) hexDump("Read packet = ", packet, length);

        // Have we seen this RA before?
        for (int i = 0; i < mRas.size(); i++) {
            Ra ra = mRas.get(i);
            if (ra.matches(packet, length)) {
                if (VDBG) log("matched RA " + ra);
                // Update lifetimes.
                ra.mLastSeen = curTime();
                ra.mMinLifetime = ra.minLifetime(packet, length);
                ra.seenCount++;

                // Keep mRas in LRU order so as to prioritize generating filters for recently seen
                // RAs. LRU prioritizes this because RA filters are generated in order from mRas
                // until the filter program exceeds the maximum filter program size allowed by the
                // chipset, so RAs appearing earlier in mRas are more likely to make it into the
                // filter program.
                // TODO: consider sorting the RAs in order of increasing expiry time as well.
                // Swap to front of array.
                mRas.add(0, mRas.remove(i));

                // If the current program doesn't expire for a while, don't update.
                if (shouldInstallnewProgram()) {
                    installNewProgramLocked();
                    return ProcessRaResult.UPDATE_EXPIRY;
                }
                return ProcessRaResult.MATCH;
            }
        }
        purgeExpiredRasLocked();
        // TODO: figure out how to proceed when we've received more then MAX_RAS RAs.
        if (mRas.size() >= MAX_RAS) {
            return ProcessRaResult.DROPPED;
        }
        final Ra ra;
        try {
            ra = new Ra(packet, length);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing RA: " + e);
            return ProcessRaResult.PARSE_ERROR;
        }
        // Ignore 0 lifetime RAs.
        if (ra.isExpired()) {
            return ProcessRaResult.ZERO_LIFETIME;
        }
        log("Adding " + ra);
        mRas.add(ra);
        installNewProgramLocked();
        return ProcessRaResult.UPDATE_NEW_RA;
    }

    /**
     * Create an {@link ApfFilter} if {@code apfCapabilities} indicates support for packet
     * filtering using APF programs.
     */
    public static ApfFilter maybeCreate(ApfCapabilities apfCapabilities,
            NetworkInterface networkInterface, IpManager.Callback ipManagerCallback,
            boolean multicastFilter) {
        if (apfCapabilities == null || networkInterface == null) return null;
        if (apfCapabilities.apfVersionSupported == 0) return null;
        if (apfCapabilities.maximumApfProgramSize < 512) {
            Log.e(TAG, "Unacceptably small APF limit: " + apfCapabilities.maximumApfProgramSize);
            return null;
        }
        // For now only support generating programs for Ethernet frames. If this restriction is
        // lifted:
        //   1. the program generator will need its offsets adjusted.
        //   2. the packet filter attached to our packet socket will need its offset adjusted.
        if (apfCapabilities.apfPacketFormat != ARPHRD_ETHER) return null;
        if (!new ApfGenerator().setApfVersion(apfCapabilities.apfVersionSupported)) {
            Log.e(TAG, "Unsupported APF version: " + apfCapabilities.apfVersionSupported);
            return null;
        }
        return new ApfFilter(apfCapabilities, networkInterface, ipManagerCallback,
                multicastFilter, new IpConnectivityLog());
    }

    public synchronized void shutdown() {
        if (mReceiveThread != null) {
            log("shutting down");
            mReceiveThread.halt();  // Also closes socket.
            mReceiveThread = null;
        }
        mRas.clear();
    }

    public synchronized void setMulticastFilter(boolean enabled) {
        if (mMulticastFilter != enabled) {
            mMulticastFilter = enabled;
            installNewProgramLocked();
        }
    }

    // Find the single IPv4 address if there is one, otherwise return null.
    private static byte[] findIPv4Address(LinkProperties lp) {
        byte[] ipv4Address = null;
        for (InetAddress inetAddr : lp.getAddresses()) {
            byte[] addr = inetAddr.getAddress();
            if (addr.length != 4) continue;
            // More than one IPv4 address, abort
            if (ipv4Address != null && !Arrays.equals(ipv4Address, addr)) return null;
            ipv4Address = addr;
        }
        return ipv4Address;
    }

    public synchronized void setLinkProperties(LinkProperties lp) {
        // NOTE: Do not keep a copy of LinkProperties as it would further duplicate state.
        byte[] ipv4Address = findIPv4Address(lp);
        // If ipv4Address is the same as mIPv4Address, then there's no change, just return.
        if (Arrays.equals(ipv4Address, mIPv4Address)) return;
        // Otherwise update mIPv4Address and install new program.
        mIPv4Address = ipv4Address;
        installNewProgramLocked();
    }

    public synchronized void dump(IndentingPrintWriter pw) {
        pw.println("Capabilities: " + mApfCapabilities);
        pw.println("Receive thread: " + (mReceiveThread != null ? "RUNNING" : "STOPPED"));
        pw.println("Multicast: " + (mMulticastFilter ? "DROP" : "ALLOW"));
        try {
            pw.println("IPv4 address: " + InetAddress.getByAddress(mIPv4Address).getHostAddress());
        } catch (UnknownHostException|NullPointerException e) {}

        if (mLastTimeInstalledProgram == 0) {
            pw.println("No program installed.");
            return;
        }
        pw.println("Program updates: " + mNumProgramUpdates);
        pw.println(String.format(
                "Last program length %d, installed %ds ago, lifetime %ds",
                mLastInstalledProgram.length, curTime() - mLastTimeInstalledProgram,
                mLastInstalledProgramMinLifetime));

        pw.println("RA filters:");
        pw.increaseIndent();
        for (Ra ra: mRas) {
            pw.println(ra);
            pw.increaseIndent();
            pw.println(String.format(
                    "Seen: %d, last %ds ago", ra.seenCount, curTime() - ra.mLastSeen));
            if (DBG) {
                pw.println("Last match:");
                pw.increaseIndent();
                pw.println(ra.getLastMatchingPacket());
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();

        if (DBG) {
            pw.println("Last program:");
            pw.increaseIndent();
            pw.println(HexDump.toHexString(mLastInstalledProgram, false /* lowercase */));
            pw.decreaseIndent();
        }
    }
}
