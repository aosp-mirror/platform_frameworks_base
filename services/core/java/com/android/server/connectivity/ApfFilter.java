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

package com.android.server.connectivity;

import static android.system.OsConstants.*;

import android.net.NetworkUtils;
import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;
import android.system.ErrnoException;
import android.system.Os;
import android.system.PacketSocketAddress;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.HexDump;
import com.android.server.ConnectivityService;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.Thread;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import libcore.io.IoBridge;

/**
 * For networks that support packet filtering via APF programs, {@code ApfFilter}
 * listens for IPv6 ICMPv6 router advertisements (RAs) and generates APF programs to
 * filter out redundant duplicate ones.
 *
 * @hide
 */
public class ApfFilter {
    // Thread to listen for RAs.
    private class ReceiveThread extends Thread {
        private final byte[] mPacket = new byte[1514];
        private final FileDescriptor mSocket;
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
                    processRa(mPacket, length);
                } catch (IOException|ErrnoException e) {
                    if (!mStopped) {
                        Log.e(TAG, "Read error", e);
                    }
                }
            }
        }
    }

    private static final String TAG = "ApfFilter";

    private final ConnectivityService mConnectivityService;
    private final NetworkAgentInfo mNai;
    private ReceiveThread mReceiveThread;
    private String mIfaceName;
    private long mUniqueCounter;

    private ApfFilter(ConnectivityService connectivityService, NetworkAgentInfo nai) {
        mConnectivityService = connectivityService;
        mNai = nai;
        maybeStartFilter();
    }

    private void log(String s) {
        Log.d(TAG, "(" + mNai.network.netId + "): " + s);
    }

    private long getUniqueNumber() {
        return mUniqueCounter++;
    }

    /**
     * Attempt to start listening for RAs and, if RAs are received, generating and installing
     * filters to ignore useless RAs.
     */
    private void maybeStartFilter() {
        mIfaceName = mNai.linkProperties.getInterfaceName();
        if (mIfaceName == null) return;
        FileDescriptor socket;
        try {
            socket = Os.socket(AF_PACKET, SOCK_RAW, ETH_P_IPV6);
            PacketSocketAddress addr = new PacketSocketAddress((short) ETH_P_IPV6,
                    NetworkInterface.getByName(mIfaceName).getIndex());
            Os.bind(socket, addr);
            NetworkUtils.attachRaFilter(socket, mNai.networkMisc.apfPacketFormat);
        } catch(SocketException|ErrnoException e) {
            Log.e(TAG, "Error filtering raw socket", e);
            return;
        }
        mReceiveThread = new ReceiveThread(socket);
        mReceiveThread.start();
    }

    /**
     * mNai's LinkProperties may have changed, take appropriate action.
     */
    public void updateFilter() {
        // If we're not listening for RAs, try starting.
        if (mReceiveThread == null) {
            maybeStartFilter();
        // If interface name has changed, restart.
        } else if (!mIfaceName.equals(mNai.linkProperties.getInterfaceName())) {
            shutdown();
            maybeStartFilter();
        }
    }

    // Returns seconds since Unix Epoch.
    private static long curTime() {
        return System.currentTimeMillis() / 1000L;
    }

    // A class to hold information about an RA.
    private class Ra {
        private static final int ETH_HEADER_LEN = 14;

        private static final int IPV6_HEADER_LEN = 40;

        // From RFC4861:
        private static final int ICMP6_RA_HEADER_LEN = 16;
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

        private final ByteBuffer mPacket;
        // List of binary ranges that include the whole packet except the lifetimes.
        // Pairs consist of offset and length.
        private final ArrayList<Pair<Integer, Integer>> mNonLifetimes =
                new ArrayList<Pair<Integer, Integer>>();
        // Minimum lifetime in packet
        long mMinLifetime;
        // When the packet was last captured, in seconds since Unix Epoch
        long mLastSeen;

        /**
         * Add a binary range of the packet that does not include a lifetime to mNonLifetimes.
         * Assumes mPacket.position() is as far as we've parsed the packet.
         * @param lastNonLifetimeStart offset within packet of where the last binary range of
         *                             data not including a lifetime.
         * @param lifetimeOffset offset from mPacket.position() to the next lifetime data.
         * @param lifetimeLength length of the next lifetime data.
         * @return offset within packet of where the next binary range of data not including
         *         a lifetime.  This can be passed into the next invocation of this function
         *         via {@code lastNonLifetimeStart}.
         */
        private int addNonLifetime(int lastNonLifetimeStart, int lifetimeOffset,
                int lifetimeLength) {
            lifetimeOffset += mPacket.position();
            mNonLifetimes.add(new Pair<Integer, Integer>(lastNonLifetimeStart,
                    lifetimeOffset - lastNonLifetimeStart));
            return lifetimeOffset + lifetimeLength;
        }

        // Note that this parses RA and may throw IllegalArgumentException (from
        // Buffer.position(int) ) or IndexOutOfBoundsException (from ByteBuffer.get(int) ) if
        // parsing encounters something non-compliant with specifications.
        Ra(byte[] packet, int length) {
            mPacket = ByteBuffer.allocate(length).put(ByteBuffer.wrap(packet, 0, length));
            mPacket.clear();
            mLastSeen = curTime();

            // Parse router lifetime
            int lastNonLifetimeStart = addNonLifetime(0, ICMP6_RA_ROUTER_LIFETIME_OFFSET,
                    ICMP6_RA_ROUTER_LIFETIME_LEN);
            // Parse ICMP6 options
            mPacket.position(ICMP6_RA_OPTION_OFFSET);
            while (mPacket.hasRemaining()) {
                int optionType = ((int)mPacket.get(mPacket.position())) & 0xff;
                int optionLength = (((int)mPacket.get(mPacket.position() + 1)) & 0xff) * 8;
                switch (optionType) {
                    case ICMP6_PREFIX_OPTION_TYPE:
                        // Parse valid lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_VALID_LIFETIME_LEN);
                        // Parse preferred lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET,
                                ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_LEN);
                        break;
                    // These three options have the same lifetime offset and size, so process
                    // together:
                    case ICMP6_ROUTE_INFO_OPTION_TYPE:
                    case ICMP6_RDNSS_OPTION_TYPE:
                    case ICMP6_DNSSL_OPTION_TYPE:
                        // Parse lifetime
                        lastNonLifetimeStart = addNonLifetime(lastNonLifetimeStart,
                                ICMP6_4_BYTE_LIFETIME_OFFSET,
                                ICMP6_4_BYTE_LIFETIME_LEN);
                        break;
                    default:
                        // RFC4861 section 4.2 dictates we ignore unknown options for fowards
                        // compatibility.
                        break;
                }
                mPacket.position(mPacket.position() + optionLength);
            }
            // Mark non-lifetime bytes since last lifetime.
            addNonLifetime(lastNonLifetimeStart, 0, 0);
            mMinLifetime = minLifetime(packet, length);
        }

        // Ignoring lifetimes (which may change) does {@code packet} match this RA?
        boolean matches(byte[] packet, int length) {
            if (length != mPacket.limit()) return false;
            ByteBuffer a = ByteBuffer.wrap(packet);
            ByteBuffer b = mPacket;
            for (Pair<Integer, Integer> nonLifetime : mNonLifetimes) {
                a.clear();
                b.clear();
                a.position(nonLifetime.first);
                b.position(nonLifetime.first);
                a.limit(nonLifetime.first + nonLifetime.second);
                b.limit(nonLifetime.first + nonLifetime.second);
                if (a.compareTo(b) != 0) return false;
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
                int lifetimeLength = mNonLifetimes.get(i+1).first - offset;
                long val;
                switch (lifetimeLength) {
                    case 2: val = byteBuffer.getShort(offset); break;
                    case 4: val = byteBuffer.getInt(offset); break;
                    default: throw new IllegalStateException("bogus lifetime size " + length);
                }
                // Mask to size, converting signed to unsigned
                val &= (1L << (lifetimeLength * 8)) - 1;
                minLifetime = Math.min(minLifetime, val);
            }
            return minLifetime;
        }

        // How many seconds does this RA's have to live, taking into account the fact
        // that we might have seen it a while ago.
        long currentLifetime() {
            return mMinLifetime - (curTime() - mLastSeen);
        }

        boolean isExpired() {
            return currentLifetime() < 0;
        }

        // Append a filter for this RA to {@code gen}. Jump to DROP_LABEL if it should be dropped.
        // Jump to the next filter if packet doesn't match this RA.
        long generateFilter(ApfGenerator gen) throws IllegalInstructionException {
            String nextFilterLabel = "Ra" + getUniqueNumber();
            // Skip if packet is not the right size
            gen.addLoadFromMemory(Register.R0, gen.PACKET_SIZE_MEMORY_SLOT);
            gen.addJumpIfR0NotEquals(mPacket.limit(), nextFilterLabel);
            int filterLifetime = (int)(currentLifetime() / FRACTION_OF_LIFETIME_TO_FILTER);
            // Skip filter if expired
            gen.addLoadFromMemory(Register.R0, gen.FILTER_AGE_MEMORY_SLOT);
            gen.addJumpIfR0GreaterThan(filterLifetime, nextFilterLabel);
            for (int i = 0; i < mNonLifetimes.size(); i++) {
                // Generate code to match the packet bytes
                Pair<Integer, Integer> nonLifetime = mNonLifetimes.get(i);
                gen.addLoadImmediate(Register.R0, nonLifetime.first);
                gen.addJumpIfBytesNotEqual(Register.R0,
                        Arrays.copyOfRange(mPacket.array(), nonLifetime.first,
                                           nonLifetime.first + nonLifetime.second),
                        nextFilterLabel);
                // Generate code to test the lifetimes haven't gone down too far
                if ((i + 1) < mNonLifetimes.size()) {
                    Pair<Integer, Integer> nextNonLifetime = mNonLifetimes.get(i + 1);
                    int offset = nonLifetime.first + nonLifetime.second;
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
    private long mLastTimeInstalledProgram;
    // How long should the last installed filter program live for? In seconds.
    private long mLastInstalledProgramMinLifetime;

    private void installNewProgram() {
        if (mRas.size() == 0) return;
        final byte[] program;
        long programMinLifetime = Long.MAX_VALUE;
        try {
            ApfGenerator gen = new ApfGenerator();
            // This is guaranteed to return true because of the check in maybeInstall.
            gen.setApfVersion(mNai.networkMisc.apfVersionSupported);
            // Step 1: Determine how many RA filters we can fit in the program.
            int ras = 0;
            for (Ra ra : mRas) {
                if (ra.isExpired()) continue;
                ra.generateFilter(gen);
                if (gen.programLengthOverEstimate() > mNai.networkMisc.maximumApfProgramSize) {
                    // We went too far.  Use prior number of RAs in "ras".
                    break;
                } else {
                    // Yay! this RA filter fits, increment "ras".
                    ras++;
                }
            }
            // Step 2: Generate RA filters
            gen = new ApfGenerator();
            // This is guaranteed to return true because of the check in maybeInstall.
            gen.setApfVersion(mNai.networkMisc.apfVersionSupported);
            for (Ra ra : mRas) {
                if (ras-- == 0) break;
                if (ra.isExpired()) continue;
                programMinLifetime = Math.min(programMinLifetime, ra.generateFilter(gen));
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
        hexDump("Installing filter: ", program, program.length);
        mConnectivityService.pushApfProgramToNetwork(mNai, program);
    }

    // Install a new filter program if the last installed one will die soon.
    private void maybeInstallNewProgram() {
        if (mRas.size() == 0) return;
        // If the current program doesn't expire for a while, don't bother updating.
        long expiry = mLastTimeInstalledProgram + mLastInstalledProgramMinLifetime;
        if (expiry < curTime() + MAX_PROGRAM_LIFETIME_WORTH_REFRESHING) {
            installNewProgram();
        }
    }

    private void hexDump(String msg, byte[] packet, int length) {
        log(msg + HexDump.toHexString(packet, 0, length));
    }

    private void processRa(byte[] packet, int length) {
        hexDump("Read packet = ", packet, length);

        // Have we seen this RA before?
        for (int i = 0; i < mRas.size(); i++) {
            Ra ra = mRas.get(i);
            if (ra.matches(packet, length)) {
                log("matched RA");
                // Update lifetimes.
                ra.mLastSeen = curTime();
                ra.mMinLifetime = ra.minLifetime(packet, length);

                // Keep mRas in LRU order so as to prioritize generating filters for recently seen
                // RAs. LRU prioritizes this because RA filters are generated in order from mRas
                // until the filter program exceeds the maximum filter program size allowed by the
                // chipset, so RAs appearing earlier in mRas are more likely to make it into the
                // filter program.
                // TODO: consider sorting the RAs in order of increasing expiry time as well.
                // Swap to front of array.
                mRas.add(0, mRas.remove(i));

                maybeInstallNewProgram();
                return;
            }
        }
        // Purge expired RAs.
        for (int i = 0; i < mRas.size();) {
            if (mRas.get(i).isExpired()) {
                log("expired RA");
                mRas.remove(i);
            } else {
                i++;
            }
        }
        // TODO: figure out how to proceed when we've received more then MAX_RAS RAs.
        if (mRas.size() >= MAX_RAS) return;
        try {
            log("adding RA");
            mRas.add(new Ra(packet, length));
        } catch (Exception e) {
            Log.e(TAG, "Error parsing RA: " + e);
            return;
        }
        installNewProgram();
    }

    /**
     * Install an {@link ApfFilter} on {@code nai} if {@code nai} supports packet
     * filtering using APF programs.
     */
    public static void maybeInstall(ConnectivityService connectivityService, NetworkAgentInfo nai) {
        if (nai.networkMisc == null) return;
        if (nai.networkMisc.apfVersionSupported == 0) return;
        if (nai.networkMisc.maximumApfProgramSize < 200) {
            Log.e(TAG, "Uselessly small APF size limit: " + nai.networkMisc.maximumApfProgramSize);
            return;
        }
        // For now only support generating programs for Ethernet frames. If this restriction is
        // lifted:
        //   1. the program generator will need its offsets adjusted.
        //   2. the packet filter attached to our packet socket will need its offset adjusted.
        if (nai.networkMisc.apfPacketFormat != ARPHRD_ETHER) return;
        if (!new ApfGenerator().setApfVersion(nai.networkMisc.apfVersionSupported)) {
            Log.e(TAG, "Unsupported APF version: " + nai.networkMisc.apfVersionSupported);
            return;
        }
        nai.apfFilter = new ApfFilter(connectivityService, nai);
    }

    public void shutdown() {
        if (mReceiveThread != null) {
            log("shuting down");
            mReceiveThread.halt();  // Also closes socket.
            mReceiveThread = null;
        }
    }
}
