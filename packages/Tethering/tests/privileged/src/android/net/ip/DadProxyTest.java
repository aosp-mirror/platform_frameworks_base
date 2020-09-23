/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_TCP;

import static com.android.internal.util.BitUtils.uint16;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.util.InterfaceParams;
import android.net.util.IpUtils;
import android.net.util.TetheringUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.TapPacketReader;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DadProxyTest {
    private static final int DATA_BUFFER_LEN = 4096;
    private static final int PACKET_TIMEOUT_MS = 5_000;

    // TODO: make NetworkStackConstants accessible to this test and use the constant from there.
    private static final int ETHER_SRC_ADDR_OFFSET = 6;

    private DadProxy mProxy;
    TestNetworkInterface mUpstreamTestIface, mTetheredTestIface;
    private InterfaceParams mUpstreamParams, mTetheredParams;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mUpstreamPacketReader, mTetheredPacketReader;
    private FileDescriptor mUpstreamTapFd, mTetheredTapFd;

    private static INetd sNetd;

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary("tetherutilsjni");

        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final IBinder netdIBinder =
                (IBinder) inst.getContext().getSystemService(Context.NETD_SERVICE);
        sNetd = INetd.Stub.asInterface(netdIBinder);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        setupTapInterfaces();

        // Looper must be prepared here since AndroidJUnitRunner runs tests on separate threads.
        if (Looper.myLooper() == null) Looper.prepare();

        DadProxy mProxy = setupProxy();
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandler.post(mUpstreamPacketReader::stop); // Also closes the socket
            mHandler.post(mTetheredPacketReader::stop); // Also closes the socket
            mUpstreamTapFd = null;
            mTetheredTapFd = null;
            mHandlerThread.quitSafely();
        }

        if (mTetheredParams != null) {
            sNetd.networkRemoveInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);
        }
        if (mUpstreamParams != null) {
            sNetd.networkRemoveInterface(INetd.LOCAL_NET_ID, mUpstreamParams.name);
        }

        if (mUpstreamTestIface != null) {
            try {
                Os.close(mUpstreamTestIface.getFileDescriptor().getFileDescriptor());
            } catch (ErrnoException e) { }
        }

        if (mTetheredTestIface != null) {
            try {
                Os.close(mTetheredTestIface.getFileDescriptor().getFileDescriptor());
            } catch (ErrnoException e) { }
        }
    }

    private TestNetworkInterface setupTapInterface() {
        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        AtomicReference<TestNetworkInterface> iface = new AtomicReference<>();

        inst.getUiAutomation().adoptShellPermissionIdentity();
        try {
            final TestNetworkManager tnm = (TestNetworkManager) inst.getContext().getSystemService(
                    Context.TEST_NETWORK_SERVICE);
            iface.set(tnm.createTapInterface());
        } finally {
            inst.getUiAutomation().dropShellPermissionIdentity();
        }

        return iface.get();
    }

    private void setupTapInterfaces() {
        // Create upstream test iface.
        mUpstreamTestIface = setupTapInterface();
        mUpstreamParams = InterfaceParams.getByName(mUpstreamTestIface.getInterfaceName());
        assertNotNull(mUpstreamParams);
        mUpstreamTapFd = mUpstreamTestIface.getFileDescriptor().getFileDescriptor();
        mUpstreamPacketReader = new TapPacketReader(mHandler, mUpstreamTapFd,
                                                    DATA_BUFFER_LEN);
        mHandler.post(mUpstreamPacketReader::start);

        // Create tethered test iface.
        mTetheredTestIface = setupTapInterface();
        mTetheredParams = InterfaceParams.getByName(mTetheredTestIface.getInterfaceName());
        assertNotNull(mTetheredParams);
        mTetheredTapFd = mTetheredTestIface.getFileDescriptor().getFileDescriptor();
        mTetheredPacketReader = new TapPacketReader(mHandler, mTetheredTapFd,
                                                    DATA_BUFFER_LEN);
        mHandler.post(mTetheredPacketReader::start);
    }

    private static final int IPV6_HEADER_LEN = 40;
    private static final int ETH_HEADER_LEN = 14;
    private static final int ICMPV6_NA_NS_LEN = 24;
    private static final int LL_TARGET_OPTION_LEN = 8;
    private static final int ICMPV6_CHECKSUM_OFFSET = 2;
    private static final int ETHER_TYPE_IPV6 = 0x86dd;

    // TODO: move the IpUtils code to frameworks/lib/net and link it statically.
    private static int checksumFold(int sum) {
        while (sum > 0xffff) {
            sum = (sum >> 16) + (sum & 0xffff);
        }
        return sum;
    }

    // TODO: move the IpUtils code to frameworks/lib/net and link it statically.
    private static short checksumAdjust(short checksum, short oldWord, short newWord) {
        checksum = (short) ~checksum;
        int tempSum = checksumFold(uint16(checksum) + uint16(newWord) + 0xffff - uint16(oldWord));
        return (short) ~tempSum;
    }

    // TODO: move the IpUtils code to frameworks/lib/net and link it statically.
    private static short icmpv6Checksum(ByteBuffer buf, int ipOffset, int transportOffset,
            int transportLen) {
        // The ICMPv6 checksum is the same as the TCP checksum, except the pseudo-header uses
        // 58 (ICMPv6) instead of 6 (TCP). Calculate the TCP checksum, and then do an incremental
        // checksum adjustment  for the change in the next header byte.
        short checksum = IpUtils.tcpChecksum(buf, ipOffset, transportOffset, transportLen);
        return checksumAdjust(checksum, (short) IPPROTO_TCP, (short) IPPROTO_ICMPV6);
    }

    private static ByteBuffer createDadPacket(int type) {
        // Refer to buildArpPacket()
        int icmpLen = ICMPV6_NA_NS_LEN
                + (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT
                ? LL_TARGET_OPTION_LEN : 0);
        final ByteBuffer buf = ByteBuffer.allocate(icmpLen + IPV6_HEADER_LEN + ETH_HEADER_LEN);

        // Ethernet header.
        final MacAddress srcMac = MacAddress.fromString("33:33:ff:66:77:88");
        buf.put(srcMac.toByteArray());
        final MacAddress dstMac = MacAddress.fromString("01:02:03:04:05:06");
        buf.put(dstMac.toByteArray());
        buf.putShort((short) ETHER_TYPE_IPV6);

        // IPv6 header
        byte[] version = {(byte) 0x60, 0x00, 0x00, 0x00};
        buf.put(version);                                           // Version
        buf.putShort((byte) icmpLen);                               // Length
        buf.put((byte) IPPROTO_ICMPV6);                             // Next header
        buf.put((byte) 0xff);                                       // Hop limit

        final byte[] target =
            InetAddresses.parseNumericAddress("fe80::1122:3344:5566:7788").getAddress();
        final byte[] src;
        final byte[] dst;
        if (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION) {
            src = InetAddresses.parseNumericAddress("::").getAddress();
            dst = InetAddresses.parseNumericAddress("ff02::1:ff66:7788").getAddress();
        } else {
            src = target;
            dst = TetheringUtils.ALL_NODES;
        }
        buf.put(src);
        buf.put(dst);

        // ICMPv6 Header
        buf.put((byte) type);                                       // Type
        buf.put((byte) 0x00);                                       // Code
        buf.putShort((short) 0);                                    // Checksum
        buf.putInt(0);                                              // Reserved
        buf.put(target);

        if (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT) {
            //NA packet has LL target address
            //ICMPv6 Option
            buf.put((byte) 0x02);                                   // Type
            buf.put((byte) 0x01);                                   // Length
            byte[] ll_target = MacAddress.fromString("01:02:03:04:05:06").toByteArray();
            buf.put(ll_target);
        }

        // Populate checksum field
        final int transportOffset = ETH_HEADER_LEN + IPV6_HEADER_LEN;
        final short checksum = icmpv6Checksum(buf, ETH_HEADER_LEN, transportOffset, icmpLen);
        buf.putShort(transportOffset + ICMPV6_CHECKSUM_OFFSET, checksum);

        buf.flip();
        return buf;
    }

    private DadProxy setupProxy() throws Exception {
        DadProxy proxy = new DadProxy(mHandler, mTetheredParams);
        mHandler.post(() -> proxy.setUpstreamIface(mUpstreamParams));

        // Upstream iface is added to local network to simplify test case.
        // Otherwise the test needs to create and destroy a network for the upstream iface.
        sNetd.networkAddInterface(INetd.LOCAL_NET_ID, mUpstreamParams.name);
        sNetd.networkAddInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);

        return proxy;
    }

    // TODO: change to assert.
    private boolean waitForPacket(ByteBuffer packet, TapPacketReader reader) {
        byte[] p;

        while ((p = reader.popPacket(PACKET_TIMEOUT_MS)) != null) {
            final ByteBuffer buffer = ByteBuffer.wrap(p);

            if (buffer.compareTo(packet) == 0) return true;
        }
        return false;
    }

    private void updateDstMac(ByteBuffer buf, MacAddress mac) {
        buf.put(mac.toByteArray());
        buf.rewind();
    }
    private void updateSrcMac(ByteBuffer buf, InterfaceParams ifaceParams) {
        buf.position(ETHER_SRC_ADDR_OFFSET);
        buf.put(ifaceParams.macAddr.toByteArray());
        buf.rewind();
    }

    @Test
    public void testNaForwardingFromUpstreamToTether() throws Exception {
        ByteBuffer na = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);

        mUpstreamPacketReader.sendResponse(na);
        updateDstMac(na, MacAddress.fromString("33:33:00:00:00:01"));
        updateSrcMac(na, mTetheredParams);
        assertTrue(waitForPacket(na, mTetheredPacketReader));
    }

    @Test
    // TODO: remove test once DAD works in both directions.
    public void testNaForwardingFromTetherToUpstream() throws Exception {
        ByteBuffer na = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);

        mTetheredPacketReader.sendResponse(na);
        updateDstMac(na, MacAddress.fromString("33:33:00:00:00:01"));
        updateSrcMac(na, mTetheredParams);
        assertFalse(waitForPacket(na, mUpstreamPacketReader));
    }

    @Test
    public void testNsForwardingFromTetherToUpstream() throws Exception {
        ByteBuffer ns = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);

        mTetheredPacketReader.sendResponse(ns);
        updateSrcMac(ns, mUpstreamParams);
        assertTrue(waitForPacket(ns, mUpstreamPacketReader));
    }

    @Test
    // TODO: remove test once DAD works in both directions.
    public void testNsForwardingFromUpstreamToTether() throws Exception {
        ByteBuffer ns = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);

        mUpstreamPacketReader.sendResponse(ns);
        updateSrcMac(ns, mUpstreamParams);
        assertFalse(waitForPacket(ns, mTetheredPacketReader));
    }
}
