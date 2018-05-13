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

import static android.net.util.NetworkConstants.*;
import static android.system.OsConstants.*;
import static com.android.internal.util.BitUtils.bytesToBEInt;
import static com.android.internal.util.BitUtils.getUint16;
import static com.android.internal.util.BitUtils.getUint32;
import static com.android.internal.util.BitUtils.getUint8;
import static com.android.internal.util.BitUtils.uint32;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;
import android.net.ip.IpClient;
import android.net.metrics.ApfProgramEvent;
import android.net.metrics.ApfStats;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.RaEvent;
import android.net.util.InterfaceParams;
import android.os.PowerManager;
import android.os.SystemClock;
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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
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

    // Helper class for specifying functional filter parameters.
    public static class ApfConfiguration {
        public ApfCapabilities apfCapabilities;
        public boolean multicastFilter;
        public boolean ieee802_3Filter;
        public int[] ethTypeBlackList;
    }

    // Enums describing the outcome of receiving an RA packet.
    private static enum ProcessRaResult {
        MATCH,          // Received RA matched a known RA
        DROPPED,        // Received RA ignored due to MAX_RAS
        PARSE_ERROR,    // Received RA could not be parsed
        ZERO_LIFETIME,  // Received RA had 0 lifetime
        UPDATE_NEW_RA,  // APF program updated for new RA
        UPDATE_EXPIRY   // APF program updated for expiry
    }

    /**
     * APF packet counters.
     *
     * Packet counters are 32bit big-endian values, and allocated near the end of the APF data
     * buffer, using negative byte offsets, where -4 is equivalent to maximumApfProgramSize - 4,
     * the last writable 32bit word.
     */
    @VisibleForTesting
    private static enum Counter {
        RESERVED_OOB,  // Points to offset 0 from the end of the buffer (out-of-bounds)
        TOTAL_PACKETS,
        PASSED_ARP,
        PASSED_DHCP,
        PASSED_IPV4,
        PASSED_IPV6_NON_ICMP,
        PASSED_IPV4_UNICAST,
        PASSED_IPV6_ICMP,
        PASSED_IPV6_UNICAST_NON_ICMP,
        PASSED_ARP_NON_IPV4,
        PASSED_ARP_UNKNOWN,
        PASSED_ARP_UNICAST_REPLY,
        PASSED_NON_IP_UNICAST,
        DROPPED_ETH_BROADCAST,
        DROPPED_RA,
        DROPPED_GARP_REPLY,
        DROPPED_ARP_OTHER_HOST,
        DROPPED_IPV4_L2_BROADCAST,
        DROPPED_IPV4_BROADCAST_ADDR,
        DROPPED_IPV4_BROADCAST_NET,
        DROPPED_IPV4_MULTICAST,
        DROPPED_IPV6_ROUTER_SOLICITATION,
        DROPPED_IPV6_MULTICAST_NA,
        DROPPED_IPV6_MULTICAST,
        DROPPED_IPV6_MULTICAST_PING,
        DROPPED_IPV6_NON_ICMP_MULTICAST,
        DROPPED_802_3_FRAME,
        DROPPED_ETHERTYPE_BLACKLISTED;

        // Returns the negative byte offset from the end of the APF data segment for
        // a given counter.
        public int offset() {
            return - this.ordinal() * 4;  // Currently, all counters are 32bit long.
        }

        // Returns the total size of the data segment in bytes.
        public static int totalSize() {
            return (Counter.class.getEnumConstants().length - 1) * 4;
        }
    }

    /**
     * When APFv4 is supported, loads R1 with the offset of the specified counter.
     */
    private void maybeSetCounter(ApfGenerator gen, Counter c) {
        if (mApfCapabilities.hasDataAccess()) {
            gen.addLoadImmediate(Register.R1, c.offset());
        }
    }

    // When APFv4 is supported, these point to the trampolines generated by emitEpilogue().
    // Otherwise, they're just aliases for PASS_LABEL and DROP_LABEL.
    private final String mCountAndPassLabel;
    private final String mCountAndDropLabel;

    // Thread to listen for RAs.
    @VisibleForTesting
    class ReceiveThread extends Thread {
        private final byte[] mPacket = new byte[1514];
        private final FileDescriptor mSocket;
        private final long mStart = SystemClock.elapsedRealtime();
        private final ApfStats mStats = new ApfStats();

        private volatile boolean mStopped;

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
            mStats.receivedRas++;
            switch(result) {
                case MATCH:
                    mStats.matchingRas++;
                    return;
                case DROPPED:
                    mStats.droppedRas++;
                    return;
                case PARSE_ERROR:
                    mStats.parseErrors++;
                    return;
                case ZERO_LIFETIME:
                    mStats.zeroLifetimeRas++;
                    return;
                case UPDATE_EXPIRY:
                    mStats.matchingRas++;
                    mStats.programUpdates++;
                    return;
                case UPDATE_NEW_RA:
                    mStats.programUpdates++;
                    return;
            }
        }

        private void logStats() {
            final long nowMs = SystemClock.elapsedRealtime();
            synchronized (this) {
                mStats.durationMs = nowMs - mStart;
                mStats.maxProgramSize = mApfCapabilities.maximumApfProgramSize;
                mStats.programUpdatesAll = mNumProgramUpdates;
                mStats.programUpdatesAllowingMulticast = mNumProgramUpdatesAllowingMulticast;
                mMetricsLog.log(mStats);
                logApfProgramEventLocked(nowMs / DateUtils.SECOND_IN_MILLIS);
            }
        }
    }

    private static final String TAG = "ApfFilter";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int ETH_HEADER_LEN = 14;
    private static final int ETH_DEST_ADDR_OFFSET = 0;
    private static final int ETH_ETHERTYPE_OFFSET = 12;
    private static final int ETH_TYPE_MIN = 0x0600;
    private static final int ETH_TYPE_MAX = 0xFFFF;
    private static final byte[] ETH_BROADCAST_MAC_ADDRESS =
            {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };
    // TODO: Make these offsets relative to end of link-layer header; don't include ETH_HEADER_LEN.
    private static final int IPV4_FRAGMENT_OFFSET_OFFSET = ETH_HEADER_LEN + 6;
    // Endianness is not an issue for this constant because the APF interpreter always operates in
    // network byte order.
    private static final int IPV4_FRAGMENT_OFFSET_MASK = 0x1fff;
    private static final int IPV4_PROTOCOL_OFFSET = ETH_HEADER_LEN + 9;
    private static final int IPV4_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 16;
    private static final int IPV4_ANY_HOST_ADDRESS = 0;
    private static final int IPV4_BROADCAST_ADDRESS = -1; // 255.255.255.255

    // Traffic class and Flow label are not byte aligned. Luckily we
    // don't care about either value so we'll consider bytes 1-3 of the
    // IPv6 header as don't care.
    private static final int IPV6_FLOW_LABEL_OFFSET = ETH_HEADER_LEN + 1;
    private static final int IPV6_FLOW_LABEL_LEN = 3;
    private static final int IPV6_NEXT_HEADER_OFFSET = ETH_HEADER_LEN + 6;
    private static final int IPV6_SRC_ADDR_OFFSET = ETH_HEADER_LEN + 8;
    private static final int IPV6_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 24;
    private static final int IPV6_HEADER_LEN = 40;
    // The IPv6 all nodes address ff02::1
    private static final byte[] IPV6_ALL_NODES_ADDRESS =
            { (byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };

    private static final int ICMP6_TYPE_OFFSET = ETH_HEADER_LEN + IPV6_HEADER_LEN;

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
    private static final byte[] ARP_IPV4_HEADER = {
            0, 1, // Hardware type: Ethernet (1)
            8, 0, // Protocol type: IP (0x0800)
            6,    // Hardware size: 6
            4,    // Protocol size: 4
    };
    private static final int ARP_TARGET_IP_ADDRESS_OFFSET = ETH_HEADER_LEN + 24;
    // Do not log ApfProgramEvents whose actual lifetimes was less than this.
    private static final int APF_PROGRAM_EVENT_LIFETIME_THRESHOLD = 2;
    // Limit on the Black List size to cap on program usage for this
    // TODO: Select a proper max length
    private static final int APF_MAX_ETH_TYPE_BLACK_LIST_LEN = 20;

    private final ApfCapabilities mApfCapabilities;
    private final IpClient.Callback mIpClientCallback;
    private final InterfaceParams mInterfaceParams;
    private final IpConnectivityLog mMetricsLog;

    @VisibleForTesting
    byte[] mHardwareAddress;
    @VisibleForTesting
    ReceiveThread mReceiveThread;
    @GuardedBy("this")
    private long mUniqueCounter;
    @GuardedBy("this")
    private boolean mMulticastFilter;
    @GuardedBy("this")
    private boolean mInDozeMode;
    private final boolean mDrop802_3Frames;
    private final int[] mEthTypeBlackList;

    // Detects doze mode state transitions.
    private final BroadcastReceiver mDeviceIdleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
                PowerManager powerManager =
                        (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                final boolean deviceIdle = powerManager.isDeviceIdleMode();
                setDozeMode(deviceIdle);
            }
        }
    };
    private final Context mContext;

    // Our IPv4 address, if we have just one, otherwise null.
    @GuardedBy("this")
    private byte[] mIPv4Address;
    // The subnet prefix length of our IPv4 network. Only valid if mIPv4Address is not null.
    @GuardedBy("this")
    private int mIPv4PrefixLength;

    @VisibleForTesting
    ApfFilter(Context context, ApfConfiguration config, InterfaceParams ifParams,
            IpClient.Callback ipClientCallback, IpConnectivityLog log) {
        mApfCapabilities = config.apfCapabilities;
        mIpClientCallback = ipClientCallback;
        mInterfaceParams = ifParams;
        mMulticastFilter = config.multicastFilter;
        mDrop802_3Frames = config.ieee802_3Filter;
        mContext = context;

        if (mApfCapabilities.hasDataAccess()) {
            mCountAndPassLabel = "countAndPass";
            mCountAndDropLabel = "countAndDrop";
        } else {
            // APFv4 unsupported: turn jumps to the counter trampolines to immediately PASS or DROP,
            // preserving the original pre-APFv4 behavior.
            mCountAndPassLabel = ApfGenerator.PASS_LABEL;
            mCountAndDropLabel = ApfGenerator.DROP_LABEL;
        }

        // Now fill the black list from the passed array
        mEthTypeBlackList = filterEthTypeBlackList(config.ethTypeBlackList);

        mMetricsLog = log;

        // TODO: ApfFilter should not generate programs until IpClient sends provisioning success.
        maybeStartFilter();

        // Listen for doze-mode transition changes to enable/disable the IPv6 multicast filter.
        mContext.registerReceiver(mDeviceIdleReceiver,
                new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
    }

    public synchronized void setDataSnapshot(byte[] data) {
        mDataSnapshot = data;
    }

    private void log(String s) {
        Log.d(TAG, "(" + mInterfaceParams.name + "): " + s);
    }

    @GuardedBy("this")
    private long getUniqueNumberLocked() {
        return mUniqueCounter++;
    }

    @GuardedBy("this")
    private static int[] filterEthTypeBlackList(int[] ethTypeBlackList) {
        ArrayList<Integer> bl = new ArrayList<Integer>();

        for (int p : ethTypeBlackList) {
            // Check if the protocol is a valid ether type
            if ((p < ETH_TYPE_MIN) || (p > ETH_TYPE_MAX)) {
                continue;
            }

            // Check if the protocol is not repeated in the passed array
            if (bl.contains(p)) {
                continue;
            }

            // Check if list reach its max size
            if (bl.size() == APF_MAX_ETH_TYPE_BLACK_LIST_LEN) {
                Log.w(TAG, "Passed EthType Black List size too large (" + bl.size() +
                        ") using top " + APF_MAX_ETH_TYPE_BLACK_LIST_LEN + " protocols");
                break;
            }

            // Now add the protocol to the list
            bl.add(p);
        }

        return bl.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Attempt to start listening for RAs and, if RAs are received, generating and installing
     * filters to ignore useless RAs.
     */
    @VisibleForTesting
    void maybeStartFilter() {
        FileDescriptor socket;
        try {
            mHardwareAddress = mInterfaceParams.macAddr.toByteArray();
            synchronized(this) {
                // Clear the APF memory to reset all counters upon connecting to the first AP
                // in an SSID. This is limited to APFv4 devices because this large write triggers
                // a crash on some older devices (b/78905546).
                if (mApfCapabilities.hasDataAccess()) {
                    byte[] zeroes = new byte[mApfCapabilities.maximumApfProgramSize];
                    mIpClientCallback.installPacketFilter(zeroes);
                }

                // Install basic filters
                installNewProgramLocked();
            }
            socket = Os.socket(AF_PACKET, SOCK_RAW, ETH_P_IPV6);
            PacketSocketAddress addr = new PacketSocketAddress(
                    (short) ETH_P_IPV6, mInterfaceParams.index);
            Os.bind(socket, addr);
            NetworkUtils.attachRaFilter(socket, mApfCapabilities.apfPacketFormat);
        } catch(SocketException|ErrnoException e) {
            Log.e(TAG, "Error starting filter", e);
            return;
        }
        mReceiveThread = new ReceiveThread(socket);
        mReceiveThread.start();
    }

    // Returns seconds since device boot.
    @VisibleForTesting
    protected long currentTimeSeconds() {
        return SystemClock.elapsedRealtime() / DateUtils.SECOND_IN_MILLIS;
    }

    public static class InvalidRaException extends Exception {
        public InvalidRaException(String m) {
            super(m);
        }
    }

    // A class to hold information about an RA.
    @VisibleForTesting
    class Ra {
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
            } catch (ClassCastException|UnknownHostException e) {
                // Cannot happen.
                return "???";
            }
        }

        // Can't be static because it's in a non-static inner class.
        // TODO: Make this static once RA is its own class.
        private void prefixOptionToString(StringBuffer sb, int offset) {
            String prefix = IPv6AddresstoString(offset + 16);
            int length = getUint8(mPacket, offset + 2);
            long valid = getUint32(mPacket, offset + 4);
            long preferred = getUint32(mPacket, offset + 8);
            sb.append(String.format("%s/%d %ds/%ds ", prefix, length, valid, preferred));
        }

        private void rdnssOptionToString(StringBuffer sb, int offset) {
            int optLen = getUint8(mPacket, offset + 1) * 8;
            if (optLen < 24) return;  // Malformed or empty.
            long lifetime = getUint32(mPacket, offset + 4);
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
                        getUint16(mPacket, ICMP6_RA_ROUTER_LIFETIME_OFFSET)));
                for (int i: mPrefixOptionOffsets) {
                    prefixOptionToString(sb, i);
                }
                for (int i: mRdnssOptionOffsets) {
                    rdnssOptionToString(sb, i);
                }
                return sb.toString();
            } catch (BufferUnderflowException|IndexOutOfBoundsException e) {
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

        // Note that this parses RA and may throw InvalidRaException (from
        // Buffer.position(int) or due to an invalid-length option) or IndexOutOfBoundsException
        // (from ByteBuffer.get(int) ) if parsing encounters something non-compliant with
        // specifications.
        Ra(byte[] packet, int length) throws InvalidRaException {
            if (length < ICMP6_RA_OPTION_OFFSET) {
                throw new InvalidRaException("Not an ICMP6 router advertisement");
            }

            mPacket = ByteBuffer.wrap(Arrays.copyOf(packet, length));
            mLastSeen = currentTimeSeconds();

            // Sanity check packet in case a packet arrives before we attach RA filter
            // to our packet socket. b/29586253
            if (getUint16(mPacket, ETH_ETHERTYPE_OFFSET) != ETH_P_IPV6 ||
                    getUint8(mPacket, IPV6_NEXT_HEADER_OFFSET) != IPPROTO_ICMPV6 ||
                    getUint8(mPacket, ICMP6_TYPE_OFFSET) != ICMPV6_ROUTER_ADVERTISEMENT) {
                throw new InvalidRaException("Not an ICMP6 router advertisement");
            }


            RaEvent.Builder builder = new RaEvent.Builder();

            // Ignore the flow label and low 4 bits of traffic class.
            int lastNonLifetimeStart = addNonLifetime(0,
                    IPV6_FLOW_LABEL_OFFSET,
                    IPV6_FLOW_LABEL_LEN);

            // Ignore the checksum.
            lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
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
                final int optionType = getUint8(mPacket, position);
                final int optionLength = getUint8(mPacket, position + 1) * 8;
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
                    throw new InvalidRaException(String.format(
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

                // The flow label is in mNonLifetimes, but it's not a lifetime.
                if (offset == IPV6_FLOW_LABEL_OFFSET) {
                    continue;
                }

                // The checksum is in mNonLifetimes, but it's not a lifetime.
                if (offset == ICMP6_RA_CHECKSUM_OFFSET) {
                    continue;
                }

                final int lifetimeLength = mNonLifetimes.get(i+1).first - offset;
                final long optionLifetime;
                switch (lifetimeLength) {
                    case 2:
                        optionLifetime = getUint16(byteBuffer, offset);
                        break;
                    case 4:
                        optionLifetime = getUint32(byteBuffer, offset);
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
            return mMinLifetime - (currentTimeSeconds() - mLastSeen);
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

                    // Skip the Flow label.
                    if (offset == IPV6_FLOW_LABEL_OFFSET) {
                        continue;
                    }
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
            maybeSetCounter(gen, Counter.DROPPED_RA);
            gen.addJump(mCountAndDropLabel);
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
    @GuardedBy("this")
    private ApfProgramEvent mLastInstallEvent;

    // For debugging only. The last program installed.
    @GuardedBy("this")
    private byte[] mLastInstalledProgram;

    /**
     * For debugging only. Contains the latest APF buffer snapshot captured from the firmware.
     *
     * A typical size for this buffer is 4KB. It is present only if the WiFi HAL supports
     * IWifiStaIface#readApfPacketFilterData(), and the APF interpreter advertised support for
     * the opcodes to access the data buffer (LDDW and STDW).
     */
    @GuardedBy("this") @Nullable
    private byte[] mDataSnapshot;

    // How many times the program was updated since we started.
    @GuardedBy("this")
    private int mNumProgramUpdates = 0;
    // How many times the program was updated since we started for allowing multicast traffic.
    @GuardedBy("this")
    private int mNumProgramUpdatesAllowingMulticast = 0;

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
        maybeSetCounter(gen, Counter.PASSED_ARP_NON_IPV4);
        gen.addJumpIfBytesNotEqual(Register.R0, ARP_IPV4_HEADER, mCountAndPassLabel);

        // Pass if unknown ARP opcode.
        gen.addLoad16(Register.R0, ARP_OPCODE_OFFSET);
        gen.addJumpIfR0Equals(ARP_OPCODE_REQUEST, checkTargetIPv4); // Skip to unicast check
        maybeSetCounter(gen, Counter.PASSED_ARP_UNKNOWN);
        gen.addJumpIfR0NotEquals(ARP_OPCODE_REPLY, mCountAndPassLabel);

        // Pass if unicast reply.
        gen.addLoadImmediate(Register.R0, ETH_DEST_ADDR_OFFSET);
        maybeSetCounter(gen, Counter.PASSED_ARP_UNICAST_REPLY);
        gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, mCountAndPassLabel);

        // Either a unicast request, a unicast reply, or a broadcast reply.
        gen.defineLabel(checkTargetIPv4);
        if (mIPv4Address == null) {
            // When there is no IPv4 address, drop GARP replies (b/29404209).
            gen.addLoad32(Register.R0, ARP_TARGET_IP_ADDRESS_OFFSET);
            maybeSetCounter(gen, Counter.DROPPED_GARP_REPLY);
            gen.addJumpIfR0Equals(IPV4_ANY_HOST_ADDRESS, mCountAndDropLabel);
        } else {
            // When there is an IPv4 address, drop unicast/broadcast requests
            // and broadcast replies with a different target IPv4 address.
            gen.addLoadImmediate(Register.R0, ARP_TARGET_IP_ADDRESS_OFFSET);
            maybeSetCounter(gen, Counter.DROPPED_ARP_OTHER_HOST);
            gen.addJumpIfBytesNotEqual(Register.R0, mIPv4Address, mCountAndDropLabel);
        }

        maybeSetCounter(gen, Counter.PASSED_ARP);
        gen.addJump(mCountAndPassLabel);
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
        //   if it's DHCP destined to our MAC:
        //     pass
        //   if it's L2 broadcast:
        //     drop
        //   if it's IPv4 multicast:
        //     drop
        //   if it's IPv4 broadcast:
        //     drop
        // pass

        if (mMulticastFilter) {
            final String skipDhcpv4Filter = "skip_dhcp_v4_filter";

            // Pass DHCP addressed to us.
            // Check it's UDP.
            gen.addLoad8(Register.R0, IPV4_PROTOCOL_OFFSET);
            gen.addJumpIfR0NotEquals(IPPROTO_UDP, skipDhcpv4Filter);
            // Check it's not a fragment. This matches the BPF filter installed by the DHCP client.
            gen.addLoad16(Register.R0, IPV4_FRAGMENT_OFFSET_OFFSET);
            gen.addJumpIfR0AnyBitsSet(IPV4_FRAGMENT_OFFSET_MASK, skipDhcpv4Filter);
            // Check it's addressed to DHCP client port.
            gen.addLoadFromMemory(Register.R1, gen.IPV4_HEADER_SIZE_MEMORY_SLOT);
            gen.addLoad16Indexed(Register.R0, UDP_DESTINATION_PORT_OFFSET);
            gen.addJumpIfR0NotEquals(DHCP_CLIENT_PORT, skipDhcpv4Filter);
            // Check it's DHCP to our MAC address.
            gen.addLoadImmediate(Register.R0, DHCP_CLIENT_MAC_OFFSET);
            // NOTE: Relies on R1 containing IPv4 header offset.
            gen.addAddR1();
            gen.addJumpIfBytesNotEqual(Register.R0, mHardwareAddress, skipDhcpv4Filter);
            maybeSetCounter(gen, Counter.PASSED_DHCP);
            gen.addJump(mCountAndPassLabel);

            // Drop all multicasts/broadcasts.
            gen.defineLabel(skipDhcpv4Filter);

            // If IPv4 destination address is in multicast range, drop.
            gen.addLoad8(Register.R0, IPV4_DEST_ADDR_OFFSET);
            gen.addAnd(0xf0);
            maybeSetCounter(gen, Counter.DROPPED_IPV4_MULTICAST);
            gen.addJumpIfR0Equals(0xe0, mCountAndDropLabel);

            // If IPv4 broadcast packet, drop regardless of L2 (b/30231088).
            maybeSetCounter(gen, Counter.DROPPED_IPV4_BROADCAST_ADDR);
            gen.addLoad32(Register.R0, IPV4_DEST_ADDR_OFFSET);
            gen.addJumpIfR0Equals(IPV4_BROADCAST_ADDRESS, mCountAndDropLabel);
            if (mIPv4Address != null && mIPv4PrefixLength < 31) {
                maybeSetCounter(gen, Counter.DROPPED_IPV4_BROADCAST_NET);
                int broadcastAddr = ipv4BroadcastAddress(mIPv4Address, mIPv4PrefixLength);
                gen.addJumpIfR0Equals(broadcastAddr, mCountAndDropLabel);
            }

            // If L2 broadcast packet, drop.
            // TODO: can we invert this condition to fall through to the common pass case below?
            maybeSetCounter(gen, Counter.PASSED_IPV4_UNICAST);
            gen.addLoadImmediate(Register.R0, ETH_DEST_ADDR_OFFSET);
            gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, mCountAndPassLabel);
            maybeSetCounter(gen, Counter.DROPPED_IPV4_L2_BROADCAST);
            gen.addJump(mCountAndDropLabel);
        }

        // Otherwise, pass
        maybeSetCounter(gen, Counter.PASSED_IPV4);
        gen.addJump(mCountAndPassLabel);
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
        // if we're dropping multicast
        //   if it's not IPCMv6 or it's ICMPv6 but we're in doze mode:
        //     if it's multicast:
        //       drop
        //     pass
        // if it's ICMPv6 RS to any:
        //   drop
        // if it's ICMPv6 NA to ff02::1:
        //   drop

        gen.addLoad8(Register.R0, IPV6_NEXT_HEADER_OFFSET);

        // Drop multicast if the multicast filter is enabled.
        if (mMulticastFilter) {
            final String skipIPv6MulticastFilterLabel = "skipIPv6MulticastFilter";
            final String dropAllIPv6MulticastsLabel = "dropAllIPv6Multicast";

            // While in doze mode, drop ICMPv6 multicast pings, let the others pass.
            // While awake, let all ICMPv6 multicasts through.
            if (mInDozeMode) {
                // Not ICMPv6? -> Proceed to multicast filtering
                gen.addJumpIfR0NotEquals(IPPROTO_ICMPV6, dropAllIPv6MulticastsLabel);

                // ICMPv6 but not ECHO? -> Skip the multicast filter.
                // (ICMPv6 ECHO requests will go through the multicast filter below).
                gen.addLoad8(Register.R0, ICMP6_TYPE_OFFSET);
                gen.addJumpIfR0NotEquals(ICMPV6_ECHO_REQUEST_TYPE, skipIPv6MulticastFilterLabel);
            } else {
                gen.addJumpIfR0Equals(IPPROTO_ICMPV6, skipIPv6MulticastFilterLabel);
            }

            // Drop all other packets sent to ff00::/8 (multicast prefix).
            gen.defineLabel(dropAllIPv6MulticastsLabel);
            maybeSetCounter(gen, Counter.DROPPED_IPV6_NON_ICMP_MULTICAST);
            gen.addLoad8(Register.R0, IPV6_DEST_ADDR_OFFSET);
            gen.addJumpIfR0Equals(0xff, mCountAndDropLabel);
            // Not multicast. Pass.
            maybeSetCounter(gen, Counter.PASSED_IPV6_UNICAST_NON_ICMP);
            gen.addJump(mCountAndPassLabel);
            gen.defineLabel(skipIPv6MulticastFilterLabel);
        } else {
            // If not ICMPv6, pass.
            maybeSetCounter(gen, Counter.PASSED_IPV6_NON_ICMP);
            gen.addJumpIfR0NotEquals(IPPROTO_ICMPV6, mCountAndPassLabel);
        }

        // If we got this far, the packet is ICMPv6.  Drop some specific types.

        // Add unsolicited multicast neighbor announcements filter
        String skipUnsolicitedMulticastNALabel = "skipUnsolicitedMulticastNA";
        gen.addLoad8(Register.R0, ICMP6_TYPE_OFFSET);
        // Drop all router solicitations (b/32833400)
        maybeSetCounter(gen, Counter.DROPPED_IPV6_ROUTER_SOLICITATION);
        gen.addJumpIfR0Equals(ICMPV6_ROUTER_SOLICITATION, mCountAndDropLabel);
        // If not neighbor announcements, skip filter.
        gen.addJumpIfR0NotEquals(ICMPV6_NEIGHBOR_ADVERTISEMENT, skipUnsolicitedMulticastNALabel);
        // If to ff02::1, drop.
        // TODO: Drop only if they don't contain the address of on-link neighbours.
        gen.addLoadImmediate(Register.R0, IPV6_DEST_ADDR_OFFSET);
        gen.addJumpIfBytesNotEqual(Register.R0, IPV6_ALL_NODES_ADDRESS,
                skipUnsolicitedMulticastNALabel);
        maybeSetCounter(gen, Counter.DROPPED_IPV6_MULTICAST_NA);
        gen.addJump(mCountAndDropLabel);
        gen.defineLabel(skipUnsolicitedMulticastNALabel);
    }

    /**
     * Begin generating an APF program to:
     * <ul>
     * <li>Drop/Pass 802.3 frames (based on policy)
     * <li>Drop packets with EtherType within the Black List
     * <li>Drop ARP requests not for us, if mIPv4Address is set,
     * <li>Drop IPv4 broadcast packets, except DHCP destined to our MAC,
     * <li>Drop IPv4 multicast packets, if mMulticastFilter,
     * <li>Pass all other IPv4 packets,
     * <li>Drop all broadcast non-IP non-ARP packets.
     * <li>Pass all non-ICMPv6 IPv6 packets,
     * <li>Pass all non-IPv4 and non-IPv6 packets,
     * <li>Drop IPv6 ICMPv6 NAs to ff02::1.
     * <li>Drop IPv6 ICMPv6 RSs.
     * <li>Let execution continue off the end of the program for IPv6 ICMPv6 packets. This allows
     *     insertion of RA filters here, or if there aren't any, just passes the packets.
     * </ul>
     */
    @GuardedBy("this")
    private ApfGenerator emitPrologueLocked() throws IllegalInstructionException {
        // This is guaranteed to succeed because of the check in maybeCreate.
        ApfGenerator gen = new ApfGenerator(mApfCapabilities.apfVersionSupported);

        if (mApfCapabilities.hasDataAccess()) {
            // Increment TOTAL_PACKETS
            maybeSetCounter(gen, Counter.TOTAL_PACKETS);
            gen.addLoadData(Register.R0, 0);  // load counter
            gen.addAdd(1);
            gen.addStoreData(Register.R0, 0);  // write-back counter
        }

        // Here's a basic summary of what the initial program does:
        //
        // if it's a 802.3 Frame (ethtype < 0x0600):
        //    drop or pass based on configurations
        // if it has a ether-type that belongs to the black list
        //    drop
        // if it's ARP:
        //   insert ARP filter to drop or pass these appropriately
        // if it's IPv4:
        //   insert IPv4 filter to drop or pass these appropriately
        // if it's not IPv6:
        //   if it's broadcast:
        //     drop
        //   pass
        // insert IPv6 filter to drop, pass, or fall off the end for ICMPv6 packets

        gen.addLoad16(Register.R0, ETH_ETHERTYPE_OFFSET);

        if (mDrop802_3Frames) {
            // drop 802.3 frames (ethtype < 0x0600)
            maybeSetCounter(gen, Counter.DROPPED_802_3_FRAME);
            gen.addJumpIfR0LessThan(ETH_TYPE_MIN, mCountAndDropLabel);
        }

        // Handle ether-type black list
        maybeSetCounter(gen, Counter.DROPPED_ETHERTYPE_BLACKLISTED);
        for (int p : mEthTypeBlackList) {
            gen.addJumpIfR0Equals(p, mCountAndDropLabel);
        }

        // Add ARP filters:
        String skipArpFiltersLabel = "skipArpFilters";
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
        maybeSetCounter(gen, Counter.PASSED_NON_IP_UNICAST);
        gen.addJumpIfBytesNotEqual(Register.R0, ETH_BROADCAST_MAC_ADDRESS, mCountAndPassLabel);
        maybeSetCounter(gen, Counter.DROPPED_ETH_BROADCAST);
        gen.addJump(mCountAndDropLabel);

        // Add IPv6 filters:
        gen.defineLabel(ipv6FilterLabel);
        generateIPv6FilterLocked(gen);
        return gen;
    }

    /**
     * Append packet counting epilogue to the APF program.
     *
     * Currently, the epilogue consists of two trampolines which count passed and dropped packets
     * before jumping to the actual PASS and DROP labels.
     */
    @GuardedBy("this")
    private void emitEpilogue(ApfGenerator gen) throws IllegalInstructionException {
        // If APFv4 is unsupported, no epilogue is necessary: if execution reached this far, it
        // will just fall-through to the PASS label.
        if (!mApfCapabilities.hasDataAccess()) return;

        // Execution will reach the bottom of the program if none of the filters match,
        // which will pass the packet to the application processor.
        maybeSetCounter(gen, Counter.PASSED_IPV6_ICMP);

        // Append the count & pass trampoline, which increments the counter at the data address
        // pointed to by R1, then jumps to the pass label. This saves a few bytes over inserting
        // the entire sequence inline for every counter.
        gen.defineLabel(mCountAndPassLabel);
        gen.addLoadData(Register.R0, 0);   // R0 = *(R1 + 0)
        gen.addAdd(1);                     // R0++
        gen.addStoreData(Register.R0, 0);  // *(R1 + 0) = R0
        gen.addJump(gen.PASS_LABEL);

        // Same as above for the count & drop trampoline.
        gen.defineLabel(mCountAndDropLabel);
        gen.addLoadData(Register.R0, 0);   // R0 = *(R1 + 0)
        gen.addAdd(1);                     // R0++
        gen.addStoreData(Register.R0, 0);  // *(R1 + 0) = R0
        gen.addJump(gen.DROP_LABEL);
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
        long maximumApfProgramSize = mApfCapabilities.maximumApfProgramSize;
        if (mApfCapabilities.hasDataAccess()) {
            // Reserve space for the counters.
            maximumApfProgramSize -= Counter.totalSize();
        }

        try {
            // Step 1: Determine how many RA filters we can fit in the program.
            ApfGenerator gen = emitPrologueLocked();

            // The epilogue normally goes after the RA filters, but add it early to include its
            // length when estimating the total.
            emitEpilogue(gen);

            // Can't fit the program even without any RA filters?
            if (gen.programLengthOverEstimate() > maximumApfProgramSize) {
                Log.e(TAG, "Program exceeds maximum size " + maximumApfProgramSize);
                return;
            }

            for (Ra ra : mRas) {
                ra.generateFilterLocked(gen);
                // Stop if we get too big.
                if (gen.programLengthOverEstimate() > maximumApfProgramSize) break;
                rasToFilter.add(ra);
            }

            // Step 2: Actually generate the program
            gen = emitPrologueLocked();
            for (Ra ra : rasToFilter) {
                programMinLifetime = Math.min(programMinLifetime, ra.generateFilterLocked(gen));
            }
            emitEpilogue(gen);
            program = gen.generate();
        } catch (IllegalInstructionException|IllegalStateException e) {
            Log.e(TAG, "Failed to generate APF program.", e);
            return;
        }
        final long now = currentTimeSeconds();
        mLastTimeInstalledProgram = now;
        mLastInstalledProgramMinLifetime = programMinLifetime;
        mLastInstalledProgram = program;
        mNumProgramUpdates++;

        if (VDBG) {
            hexDump("Installing filter: ", program, program.length);
        }
        mIpClientCallback.installPacketFilter(program);
        logApfProgramEventLocked(now);
        mLastInstallEvent = new ApfProgramEvent();
        mLastInstallEvent.lifetime = programMinLifetime;
        mLastInstallEvent.filteredRas = rasToFilter.size();
        mLastInstallEvent.currentRas = mRas.size();
        mLastInstallEvent.programLength = program.length;
        mLastInstallEvent.flags = ApfProgramEvent.flagsFor(mIPv4Address != null, mMulticastFilter);
    }

    @GuardedBy("this")
    private void logApfProgramEventLocked(long now) {
        if (mLastInstallEvent == null) {
            return;
        }
        ApfProgramEvent ev = mLastInstallEvent;
        mLastInstallEvent = null;
        ev.actualLifetime = now - mLastTimeInstalledProgram;
        if (ev.actualLifetime < APF_PROGRAM_EVENT_LIFETIME_THRESHOLD) {
            return;
        }
        mMetricsLog.log(ev);
    }

    /**
     * Returns {@code true} if a new program should be installed because the current one dies soon.
     */
    private boolean shouldInstallnewProgram() {
        long expiry = mLastTimeInstalledProgram + mLastInstalledProgramMinLifetime;
        return expiry < currentTimeSeconds() + MAX_PROGRAM_LIFETIME_WORTH_REFRESHING;
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
    @VisibleForTesting
    synchronized ProcessRaResult processRa(byte[] packet, int length) {
        if (VDBG) hexDump("Read packet = ", packet, length);

        // Have we seen this RA before?
        for (int i = 0; i < mRas.size(); i++) {
            Ra ra = mRas.get(i);
            if (ra.matches(packet, length)) {
                if (VDBG) log("matched RA " + ra);
                // Update lifetimes.
                ra.mLastSeen = currentTimeSeconds();
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
            Log.e(TAG, "Error parsing RA", e);
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
    public static ApfFilter maybeCreate(Context context, ApfConfiguration config,
            InterfaceParams ifParams, IpClient.Callback ipClientCallback) {
        if (context == null || config == null || ifParams == null) return null;
        ApfCapabilities apfCapabilities =  config.apfCapabilities;
        if (apfCapabilities == null) return null;
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
        if (!ApfGenerator.supportsVersion(apfCapabilities.apfVersionSupported)) {
            Log.e(TAG, "Unsupported APF version: " + apfCapabilities.apfVersionSupported);
            return null;
        }

        return new ApfFilter(context, config, ifParams, ipClientCallback, new IpConnectivityLog());
    }

    public synchronized void shutdown() {
        if (mReceiveThread != null) {
            log("shutting down");
            mReceiveThread.halt();  // Also closes socket.
            mReceiveThread = null;
        }
        mRas.clear();
        mContext.unregisterReceiver(mDeviceIdleReceiver);
    }

    public synchronized void setMulticastFilter(boolean isEnabled) {
        if (mMulticastFilter == isEnabled) return;
        mMulticastFilter = isEnabled;
        if (!isEnabled) {
            mNumProgramUpdatesAllowingMulticast++;
        }
        installNewProgramLocked();
    }

    @VisibleForTesting
    public synchronized void setDozeMode(boolean isEnabled) {
        if (mInDozeMode == isEnabled) return;
        mInDozeMode = isEnabled;
        installNewProgramLocked();
    }

    /** Find the single IPv4 LinkAddress if there is one, otherwise return null. */
    private static LinkAddress findIPv4LinkAddress(LinkProperties lp) {
        LinkAddress ipv4Address = null;
        for (LinkAddress address : lp.getLinkAddresses()) {
            if (!(address.getAddress() instanceof Inet4Address)) {
                continue;
            }
            if (ipv4Address != null && !ipv4Address.isSameAddressAs(address)) {
                // More than one IPv4 address, abort.
                return null;
            }
            ipv4Address = address;
        }
        return ipv4Address;
    }

    public synchronized void setLinkProperties(LinkProperties lp) {
        // NOTE: Do not keep a copy of LinkProperties as it would further duplicate state.
        final LinkAddress ipv4Address = findIPv4LinkAddress(lp);
        final byte[] addr = (ipv4Address != null) ? ipv4Address.getAddress().getAddress() : null;
        final int prefix = (ipv4Address != null) ? ipv4Address.getPrefixLength() : 0;
        if ((prefix == mIPv4PrefixLength) && Arrays.equals(addr, mIPv4Address)) {
            return;
        }
        mIPv4Address = addr;
        mIPv4PrefixLength = prefix;
        installNewProgramLocked();
    }

    static public long counterValue(byte[] data, Counter counter)
            throws ArrayIndexOutOfBoundsException {
        // Follow the same wrap-around addressing scheme of the interpreter.
        int offset = counter.offset();
        if (offset < 0) {
            offset = data.length + offset;
        }

        // Decode 32bit big-endian integer into a long so we can count up beyond 2^31.
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = value << 8 | (data[offset] & 0xFF);
            offset++;
        }
        return value;
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
                mLastInstalledProgram.length, currentTimeSeconds() - mLastTimeInstalledProgram,
                mLastInstalledProgramMinLifetime));

        pw.println("RA filters:");
        pw.increaseIndent();
        for (Ra ra: mRas) {
            pw.println(ra);
            pw.increaseIndent();
            pw.println(String.format(
                    "Seen: %d, last %ds ago", ra.seenCount, currentTimeSeconds() - ra.mLastSeen));
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

        pw.println("APF packet counters: ");
        pw.increaseIndent();
        if (!mApfCapabilities.hasDataAccess()) {
            pw.println("APF counters not supported");
        } else if (mDataSnapshot == null) {
            pw.println("No last snapshot.");
        } else {
            try {
                Counter[] counters = Counter.class.getEnumConstants();
                for (Counter c : Arrays.asList(counters).subList(1, counters.length)) {
                    long value = counterValue(mDataSnapshot, c);
                    // Only print non-zero counters
                    if (value != 0) {
                        pw.println(c.toString() + ": " + value);
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                pw.println("Uh-oh: " + e);
            }
            if (VDBG) {
                pw.println("Raw data dump: ");
                pw.println(HexDump.dumpHexString(mDataSnapshot));
            }
        }
        pw.decreaseIndent();
    }

    // TODO: move to android.net.NetworkUtils
    @VisibleForTesting
    public static int ipv4BroadcastAddress(byte[] addrBytes, int prefixLength) {
        return bytesToBEInt(addrBytes) | (int) (uint32(-1) >>> prefixLength);
    }
}
