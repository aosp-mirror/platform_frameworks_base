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

package com.android.server.connectivity;

import static android.system.OsConstants.*;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.text.TextUtils;

import com.android.internal.util.IndentingPrintWriter;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import libcore.io.IoUtils;


/**
 * NetworkDiagnostics
 *
 * A simple class to diagnose network connectivity fundamentals.  Current
 * checks performed are:
 *     - ICMPv4/v6 echo requests for all routers
 *     - ICMPv4/v6 echo requests for all DNS servers
 *     - DNS UDP queries to all DNS servers
 *
 * Currently unimplemented checks include:
 *     - report ARP/ND data about on-link neighbors
 *     - DNS TCP queries to all DNS servers
 *     - HTTP DIRECT and PROXY checks
 *     - port 443 blocking/TLS intercept checks
 *     - QUIC reachability checks
 *     - MTU checks
 *
 * The supplied timeout bounds the entire diagnostic process.  Each specific
 * check class must implement this upper bound on measurements in whichever
 * manner is most appropriate and effective.
 *
 * @hide
 */
public class NetworkDiagnostics {
    private static final String TAG = "NetworkDiagnostics";

    private static final InetAddress TEST_DNS4 = NetworkUtils.numericToInetAddress("8.8.8.8");
    private static final InetAddress TEST_DNS6 = NetworkUtils.numericToInetAddress(
            "2001:4860:4860::8888");

    // For brevity elsewhere.
    private static final long now() {
        return SystemClock.elapsedRealtime();
    }

    // Values from RFC 1035 section 4.1.1, names from <arpa/nameser.h>.
    // Should be a member of DnsUdpCheck, but "compiler says no".
    public static enum DnsResponseCode { NOERROR, FORMERR, SERVFAIL, NXDOMAIN, NOTIMP, REFUSED };

    private final Network mNetwork;
    private final LinkProperties mLinkProperties;
    private final Integer mInterfaceIndex;

    private final long mTimeoutMs;
    private final long mStartTime;
    private final long mDeadlineTime;

    // A counter, initialized to the total number of measurements,
    // so callers can wait for completion.
    private final CountDownLatch mCountDownLatch;

    private class Measurement {
        private static final String SUCCEEDED = "SUCCEEDED";
        private static final String FAILED = "FAILED";

        // TODO: Refactor to make these private for better encapsulation.
        public String description = "";
        public long startTime;
        public long finishTime;
        public String result = "";
        public Thread thread;

        public void recordSuccess(String msg) {
            maybeFixupTimes();
            result = SUCCEEDED + ": " + msg;
            if (mCountDownLatch != null) {
                mCountDownLatch.countDown();
            }
        }

        public void recordFailure(String msg) {
            maybeFixupTimes();
            result = FAILED + ": " + msg;
            if (mCountDownLatch != null) {
                mCountDownLatch.countDown();
            }
        }

        private void maybeFixupTimes() {
            // Allows the caller to just set success/failure and not worry
            // about also setting the correct finishing time.
            if (finishTime == 0) { finishTime = now(); }

            // In cases where, for example, a failure has occurred before the
            // measurement even began, fixup the start time to reflect as much.
            if (startTime == 0) { startTime = finishTime; }
        }

        @Override
        public String toString() {
            return description + ": " + result + " (" + (finishTime - startTime) + "ms)";
        }
    }

    private final Map<InetAddress, Measurement> mIcmpChecks = new HashMap<>();
    private final Map<InetAddress, Measurement> mDnsUdpChecks = new HashMap<>();
    private final String mDescription;


    public NetworkDiagnostics(Network network, LinkProperties lp, long timeoutMs) {
        mNetwork = network;
        mLinkProperties = lp;
        mInterfaceIndex = getInterfaceIndex(mLinkProperties.getInterfaceName());
        mTimeoutMs = timeoutMs;
        mStartTime = now();
        mDeadlineTime = mStartTime + mTimeoutMs;

        // Hardcode measurements to TEST_DNS4 and TEST_DNS6 in order to test off-link connectivity.
        // We are free to modify mLinkProperties with impunity because ConnectivityService passes us
        // a copy and not the original object. It's easier to do it this way because we don't need
        // to check whether the LinkProperties already contains these DNS servers because
        // LinkProperties#addDnsServer checks for duplicates.
        if (mLinkProperties.isReachable(TEST_DNS4)) {
            mLinkProperties.addDnsServer(TEST_DNS4);
        }
        // TODO: we could use mLinkProperties.isReachable(TEST_DNS6) here, because we won't set any
        // DNS servers for which isReachable() is false, but since this is diagnostic code, be extra
        // careful.
        if (mLinkProperties.hasGlobalIPv6Address() || mLinkProperties.hasIPv6DefaultRoute()) {
            mLinkProperties.addDnsServer(TEST_DNS6);
        }

        for (RouteInfo route : mLinkProperties.getRoutes()) {
            if (route.hasGateway()) {
                prepareIcmpMeasurement(route.getGateway());
            }
        }
        for (InetAddress nameserver : mLinkProperties.getDnsServers()) {
                prepareIcmpMeasurement(nameserver);
                prepareDnsMeasurement(nameserver);
        }

        mCountDownLatch = new CountDownLatch(totalMeasurementCount());

        startMeasurements();

        mDescription = "ifaces{" + TextUtils.join(",", mLinkProperties.getAllInterfaceNames()) + "}"
                + " index{" + mInterfaceIndex + "}"
                + " network{" + mNetwork + "}"
                + " nethandle{" + mNetwork.getNetworkHandle() + "}";
    }

    private static Integer getInterfaceIndex(String ifname) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifname);
            return ni.getIndex();
        } catch (NullPointerException | SocketException e) {
            return null;
        }
    }

    private void prepareIcmpMeasurement(InetAddress target) {
        if (!mIcmpChecks.containsKey(target)) {
            Measurement measurement = new Measurement();
            measurement.thread = new Thread(new IcmpCheck(target, measurement));
            mIcmpChecks.put(target, measurement);
        }
    }

    private void prepareDnsMeasurement(InetAddress target) {
        if (!mDnsUdpChecks.containsKey(target)) {
            Measurement measurement = new Measurement();
            measurement.thread = new Thread(new DnsUdpCheck(target, measurement));
            mDnsUdpChecks.put(target, measurement);
        }
    }

    private int totalMeasurementCount() {
        return mIcmpChecks.size() + mDnsUdpChecks.size();
    }

    private void startMeasurements() {
        for (Measurement measurement : mIcmpChecks.values()) {
            measurement.thread.start();
        }
        for (Measurement measurement : mDnsUdpChecks.values()) {
            measurement.thread.start();
        }
    }

    public void waitForMeasurements() {
        try {
            mCountDownLatch.await(mDeadlineTime - now(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println(TAG + ":" + mDescription);
        final long unfinished = mCountDownLatch.getCount();
        if (unfinished > 0) {
            // This can't happen unless a caller forgets to call waitForMeasurements()
            // or a measurement isn't implemented to correctly honor the timeout.
            pw.println("WARNING: countdown wait incomplete: "
                    + unfinished + " unfinished measurements");
        }

        pw.increaseIndent();
        for (Map.Entry<InetAddress, Measurement> entry : mIcmpChecks.entrySet()) {
            if (entry.getKey() instanceof Inet4Address) {
                pw.println(entry.getValue().toString());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry : mIcmpChecks.entrySet()) {
            if (entry.getKey() instanceof Inet6Address) {
                pw.println(entry.getValue().toString());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry : mDnsUdpChecks.entrySet()) {
            if (entry.getKey() instanceof Inet4Address) {
                pw.println(entry.getValue().toString());
            }
        }
        for (Map.Entry<InetAddress, Measurement> entry : mDnsUdpChecks.entrySet()) {
            if (entry.getKey() instanceof Inet6Address) {
                pw.println(entry.getValue().toString());
            }
        }
        pw.decreaseIndent();
    }


    private class SimpleSocketCheck implements Closeable {
        protected final InetAddress mTarget;
        protected final int mAddressFamily;
        protected final Measurement mMeasurement;
        protected FileDescriptor mFileDescriptor;
        protected SocketAddress mSocketAddress;

        protected SimpleSocketCheck(InetAddress target, Measurement measurement) {
            mMeasurement = measurement;

            if (target instanceof Inet6Address) {
                Inet6Address targetWithScopeId = null;
                if (target.isLinkLocalAddress() && mInterfaceIndex != null) {
                    try {
                        targetWithScopeId = Inet6Address.getByAddress(
                                null, target.getAddress(), mInterfaceIndex);
                    } catch (UnknownHostException e) {
                        mMeasurement.recordFailure(e.toString());
                    }
                }
                mTarget = (targetWithScopeId != null) ? targetWithScopeId : target;
                mAddressFamily = AF_INET6;
            } else {
                mTarget = target;
                mAddressFamily = AF_INET;
            }
        }

        protected void setupSocket(
                int sockType, int protocol, long writeTimeout, long readTimeout, int dstPort)
                throws ErrnoException, IOException {
            mFileDescriptor = Os.socket(mAddressFamily, sockType, protocol);
            // Setting SNDTIMEO is purely for defensive purposes.
            Os.setsockoptTimeval(mFileDescriptor,
                    SOL_SOCKET, SO_SNDTIMEO, StructTimeval.fromMillis(writeTimeout));
            Os.setsockoptTimeval(mFileDescriptor,
                    SOL_SOCKET, SO_RCVTIMEO, StructTimeval.fromMillis(readTimeout));
            // TODO: Use IP_RECVERR/IPV6_RECVERR, pending OsContants availability.
            mNetwork.bindSocket(mFileDescriptor);
            Os.connect(mFileDescriptor, mTarget, dstPort);
            mSocketAddress = Os.getsockname(mFileDescriptor);
        }

        protected String getSocketAddressString() {
            // The default toString() implementation is not the prettiest.
            InetSocketAddress inetSockAddr = (InetSocketAddress) mSocketAddress;
            InetAddress localAddr = inetSockAddr.getAddress();
            return String.format(
                    (localAddr instanceof Inet6Address ? "[%s]:%d" : "%s:%d"),
                    localAddr.getHostAddress(), inetSockAddr.getPort());
        }

        @Override
        public void close() {
            IoUtils.closeQuietly(mFileDescriptor);
        }
    }


    private class IcmpCheck extends SimpleSocketCheck implements Runnable {
        private static final int TIMEOUT_SEND = 100;
        private static final int TIMEOUT_RECV = 300;
        private static final int ICMPV4_ECHO_REQUEST = 8;
        private static final int ICMPV6_ECHO_REQUEST = 128;
        private static final int PACKET_BUFSIZE = 512;
        private final int mProtocol;
        private final int mIcmpType;

        public IcmpCheck(InetAddress target, Measurement measurement) {
            super(target, measurement);

            if (mAddressFamily == AF_INET6) {
                mProtocol = IPPROTO_ICMPV6;
                mIcmpType = ICMPV6_ECHO_REQUEST;
                mMeasurement.description = "ICMPv6";
            } else {
                mProtocol = IPPROTO_ICMP;
                mIcmpType = ICMPV4_ECHO_REQUEST;
                mMeasurement.description = "ICMPv4";
            }

            mMeasurement.description += " dst{" + mTarget.getHostAddress() + "}";
        }

        @Override
        public void run() {
            // Check if this measurement has already failed during setup.
            if (mMeasurement.finishTime > 0) {
                // If the measurement failed during construction it didn't
                // decrement the countdown latch; do so here.
                mCountDownLatch.countDown();
                return;
            }

            try {
                setupSocket(SOCK_DGRAM, mProtocol, TIMEOUT_SEND, TIMEOUT_RECV, 0);
            } catch (ErrnoException | IOException e) {
                mMeasurement.recordFailure(e.toString());
                return;
            }
            mMeasurement.description += " src{" + getSocketAddressString() + "}";

            // Build a trivial ICMP packet.
            final byte[] icmpPacket = {
                    (byte) mIcmpType, 0, 0, 0, 0, 0, 0, 0  // ICMP header
            };

            int count = 0;
            mMeasurement.startTime = now();
            while (now() < mDeadlineTime - (TIMEOUT_SEND + TIMEOUT_RECV)) {
                count++;
                icmpPacket[icmpPacket.length - 1] = (byte) count;
                try {
                    Os.write(mFileDescriptor, icmpPacket, 0, icmpPacket.length);
                } catch (ErrnoException | InterruptedIOException e) {
                    mMeasurement.recordFailure(e.toString());
                    break;
                }

                try {
                    ByteBuffer reply = ByteBuffer.allocate(PACKET_BUFSIZE);
                    Os.read(mFileDescriptor, reply);
                    // TODO: send a few pings back to back to guesstimate packet loss.
                    mMeasurement.recordSuccess("1/" + count);
                    break;
                } catch (ErrnoException | InterruptedIOException e) {
                    continue;
                }
            }
            if (mMeasurement.finishTime == 0) {
                mMeasurement.recordFailure("0/" + count);
            }

            close();
        }
    }


    private class DnsUdpCheck extends SimpleSocketCheck implements Runnable {
        private static final int TIMEOUT_SEND = 100;
        private static final int TIMEOUT_RECV = 500;
        private static final int DNS_SERVER_PORT = 53;
        private static final int RR_TYPE_A = 1;
        private static final int RR_TYPE_AAAA = 28;
        private static final int PACKET_BUFSIZE = 512;

        private final Random mRandom = new Random();

        // Should be static, but the compiler mocks our puny, human attempts at reason.
        private String responseCodeStr(int rcode) {
            try {
                return DnsResponseCode.values()[rcode].toString();
            } catch (IndexOutOfBoundsException e) {
                return String.valueOf(rcode);
            }
        }

        private final int mQueryType;

        public DnsUdpCheck(InetAddress target, Measurement measurement) {
            super(target, measurement);

            // TODO: Ideally, query the target for both types regardless of address family.
            if (mAddressFamily == AF_INET6) {
                mQueryType = RR_TYPE_AAAA;
            } else {
                mQueryType = RR_TYPE_A;
            }

            mMeasurement.description = "DNS UDP dst{" + mTarget.getHostAddress() + "}";
        }

        @Override
        public void run() {
            // Check if this measurement has already failed during setup.
            if (mMeasurement.finishTime > 0) {
                // If the measurement failed during construction it didn't
                // decrement the countdown latch; do so here.
                mCountDownLatch.countDown();
                return;
            }

            try {
                setupSocket(SOCK_DGRAM, IPPROTO_UDP, TIMEOUT_SEND, TIMEOUT_RECV, DNS_SERVER_PORT);
            } catch (ErrnoException | IOException e) {
                mMeasurement.recordFailure(e.toString());
                return;
            }
            mMeasurement.description += " src{" + getSocketAddressString() + "}";

            // This needs to be fixed length so it can be dropped into the pre-canned packet.
            final String sixRandomDigits =
                    Integer.valueOf(mRandom.nextInt(900000) + 100000).toString();
            mMeasurement.description += " qtype{" + mQueryType + "}"
                    + " qname{" + sixRandomDigits + "-android-ds.metric.gstatic.com}";

            // Build a trivial DNS packet.
            final byte[] dnsPacket = getDnsQueryPacket(sixRandomDigits);

            int count = 0;
            mMeasurement.startTime = now();
            while (now() < mDeadlineTime - (TIMEOUT_RECV + TIMEOUT_RECV)) {
                count++;
                try {
                    Os.write(mFileDescriptor, dnsPacket, 0, dnsPacket.length);
                } catch (ErrnoException | InterruptedIOException e) {
                    mMeasurement.recordFailure(e.toString());
                    break;
                }

                try {
                    ByteBuffer reply = ByteBuffer.allocate(PACKET_BUFSIZE);
                    Os.read(mFileDescriptor, reply);
                    // TODO: more correct and detailed evaluation of the response,
                    // possibly adding the returned IP address(es) to the output.
                    final String rcodeStr = (reply.limit() > 3)
                            ? " " + responseCodeStr((int) (reply.get(3)) & 0x0f)
                            : "";
                    mMeasurement.recordSuccess("1/" + count + rcodeStr);
                    break;
                } catch (ErrnoException | InterruptedIOException e) {
                    continue;
                }
            }
            if (mMeasurement.finishTime == 0) {
                mMeasurement.recordFailure("0/" + count);
            }

            close();
        }

        private byte[] getDnsQueryPacket(String sixRandomDigits) {
            byte[] rnd = sixRandomDigits.getBytes(StandardCharsets.US_ASCII);
            return new byte[] {
                (byte) mRandom.nextInt(), (byte) mRandom.nextInt(),  // [0-1]   query ID
                1, 0,  // [2-3]   flags; byte[2] = 1 for recursion desired (RD).
                0, 1,  // [4-5]   QDCOUNT (number of queries)
                0, 0,  // [6-7]   ANCOUNT (number of answers)
                0, 0,  // [8-9]   NSCOUNT (number of name server records)
                0, 0,  // [10-11] ARCOUNT (number of additional records)
                17, rnd[0], rnd[1], rnd[2], rnd[3], rnd[4], rnd[5],
                        '-', 'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 's',
                6, 'm', 'e', 't', 'r', 'i', 'c',
                7, 'g', 's', 't', 'a', 't', 'i', 'c',
                3, 'c', 'o', 'm',
                0,  // null terminator of FQDN (root TLD)
                0, (byte) mQueryType,  // QTYPE
                0, 1  // QCLASS, set to 1 = IN (Internet)
            };
        }
    }
}
