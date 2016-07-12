/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.frameworks.servicestests.R;

import android.net.apf.ApfCapabilities;
import android.net.apf.ApfFilter;
import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;
import android.net.ip.IpManager;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.RaEvent;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.ConditionVariable;
import android.os.Parcelable;
import android.system.ErrnoException;
import android.system.Os;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.List;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for APF program generator and interpreter.
 *
 * Build, install and run with:
 *  runtest frameworks-services -c com.android.server.ApfTest
 */
public class ApfTest extends AndroidTestCase {
    private static final int TIMEOUT_MS = 500;

    @Mock IpConnectivityLog mLog;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        // Load up native shared library containing APF interpreter exposed via JNI.
        System.loadLibrary("servicestestsjni");
    }

    // Expected return codes from APF interpreter.
    private final static int PASS = 1;
    private final static int DROP = 0;
    // Interpreter will just accept packets without link layer headers, so pad fake packet to at
    // least the minimum packet size.
    private final static int MIN_PKT_SIZE = 15;

    private final static boolean DROP_MULTICAST = true;
    private final static boolean ALLOW_MULTICAST = false;

    private void assertVerdict(int expected, byte[] program, byte[] packet, int filterAge) {
        assertEquals(expected, apfSimulate(program, packet, filterAge));
    }

    private void assertPass(byte[] program, byte[] packet, int filterAge) {
        assertVerdict(PASS, program, packet, filterAge);
    }

    private void assertDrop(byte[] program, byte[] packet, int filterAge) {
        assertVerdict(DROP, program, packet, filterAge);
    }

    private void assertVerdict(int expected, ApfGenerator gen, byte[] packet, int filterAge)
            throws IllegalInstructionException {
        assertEquals(expected, apfSimulate(gen.generate(), packet, filterAge));
    }

    private void assertPass(ApfGenerator gen, byte[] packet, int filterAge)
            throws IllegalInstructionException {
        assertVerdict(PASS, gen, packet, filterAge);
    }

    private void assertDrop(ApfGenerator gen, byte[] packet, int filterAge)
            throws IllegalInstructionException {
        assertVerdict(DROP, gen, packet, filterAge);
    }

    private void assertPass(ApfGenerator gen)
            throws IllegalInstructionException {
        assertVerdict(PASS, gen, new byte[MIN_PKT_SIZE], 0);
    }

    private void assertDrop(ApfGenerator gen)
            throws IllegalInstructionException {
        assertVerdict(DROP, gen, new byte[MIN_PKT_SIZE], 0);
    }

    /**
     * Test each instruction by generating a program containing the instruction,
     * generating bytecode for that program and running it through the
     * interpreter to verify it functions correctly.
     */
    @LargeTest
    public void testApfInstructions() throws IllegalInstructionException {
        // Empty program should pass because having the program counter reach the
        // location immediately after the program indicates the packet should be
        // passed to the AP.
        ApfGenerator gen = new ApfGenerator();
        assertPass(gen);

        // Test jumping to pass label.
        gen = new ApfGenerator();
        gen.addJump(gen.PASS_LABEL);
        byte[] program = gen.generate();
        assertEquals(1, program.length);
        assertEquals((14 << 3) | (0 << 1) | 0, program[0]);
        assertPass(program, new byte[MIN_PKT_SIZE], 0);

        // Test jumping to drop label.
        gen = new ApfGenerator();
        gen.addJump(gen.DROP_LABEL);
        program = gen.generate();
        assertEquals(2, program.length);
        assertEquals((14 << 3) | (1 << 1) | 0, program[0]);
        assertEquals(1, program[1]);
        assertDrop(program, new byte[15], 15);

        // Test jumping if equal to 0.
        gen = new ApfGenerator();
        gen.addJumpIfR0Equals(0, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if not equal to 0.
        gen = new ApfGenerator();
        gen.addJumpIfR0NotEquals(0, gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfR0NotEquals(0, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if registers equal.
        gen = new ApfGenerator();
        gen.addJumpIfR0EqualsR1(gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if registers not equal.
        gen = new ApfGenerator();
        gen.addJumpIfR0NotEqualsR1(gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfR0NotEqualsR1(gen.DROP_LABEL);
        assertDrop(gen);

        // Test load immediate.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test add.
        gen = new ApfGenerator();
        gen.addAdd(1234567890);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test subtract.
        gen = new ApfGenerator();
        gen.addAdd(-1234567890);
        gen.addJumpIfR0Equals(-1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test or.
        gen = new ApfGenerator();
        gen.addOr(1234567890);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test and.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addAnd(123456789);
        gen.addJumpIfR0Equals(1234567890 & 123456789, gen.DROP_LABEL);
        assertDrop(gen);

        // Test left shift.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLeftShift(1);
        gen.addJumpIfR0Equals(1234567890 << 1, gen.DROP_LABEL);
        assertDrop(gen);

        // Test right shift.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addRightShift(1);
        gen.addJumpIfR0Equals(1234567890 >> 1, gen.DROP_LABEL);
        assertDrop(gen);

        // Test multiply.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addMul(2);
        gen.addJumpIfR0Equals(1234567890 * 2, gen.DROP_LABEL);
        assertDrop(gen);

        // Test divide.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addDiv(2);
        gen.addJumpIfR0Equals(1234567890 / 2, gen.DROP_LABEL);
        assertDrop(gen);

        // Test divide by zero.
        gen = new ApfGenerator();
        gen.addDiv(0);
        gen.addJump(gen.DROP_LABEL);
        assertPass(gen);

        // Test add.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1234567890);
        gen.addAddR1();
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test subtract.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, -1234567890);
        gen.addAddR1();
        gen.addJumpIfR0Equals(-1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test or.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1234567890);
        gen.addOrR1();
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test and.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLoadImmediate(Register.R1, 123456789);
        gen.addAndR1();
        gen.addJumpIfR0Equals(1234567890 & 123456789, gen.DROP_LABEL);
        assertDrop(gen);

        // Test left shift.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLoadImmediate(Register.R1, 1);
        gen.addLeftShiftR1();
        gen.addJumpIfR0Equals(1234567890 << 1, gen.DROP_LABEL);
        assertDrop(gen);

        // Test right shift.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLoadImmediate(Register.R1, -1);
        gen.addLeftShiftR1();
        gen.addJumpIfR0Equals(1234567890 >> 1, gen.DROP_LABEL);
        assertDrop(gen);

        // Test multiply.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLoadImmediate(Register.R1, 2);
        gen.addMulR1();
        gen.addJumpIfR0Equals(1234567890 * 2, gen.DROP_LABEL);
        assertDrop(gen);

        // Test divide.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addLoadImmediate(Register.R1, 2);
        gen.addDivR1();
        gen.addJumpIfR0Equals(1234567890 / 2, gen.DROP_LABEL);
        assertDrop(gen);

        // Test divide by zero.
        gen = new ApfGenerator();
        gen.addDivR1();
        gen.addJump(gen.DROP_LABEL);
        assertPass(gen);

        // Test byte load.
        gen = new ApfGenerator();
        gen.addLoad8(Register.R0, 1);
        gen.addJumpIfR0Equals(45, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,0,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test out of bounds load.
        gen = new ApfGenerator();
        gen.addLoad8(Register.R0, 16);
        gen.addJumpIfR0Equals(0, gen.DROP_LABEL);
        assertPass(gen, new byte[]{123,45,0,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test half-word load.
        gen = new ApfGenerator();
        gen.addLoad16(Register.R0, 1);
        gen.addJumpIfR0Equals((45 << 8) | 67, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,67,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test word load.
        gen = new ApfGenerator();
        gen.addLoad32(Register.R0, 1);
        gen.addJumpIfR0Equals((45 << 24) | (67 << 16) | (89 << 8) | 12, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,67,89,12,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test byte indexed load.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1);
        gen.addLoad8Indexed(Register.R0, 0);
        gen.addJumpIfR0Equals(45, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,0,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test out of bounds indexed load.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 8);
        gen.addLoad8Indexed(Register.R0, 8);
        gen.addJumpIfR0Equals(0, gen.DROP_LABEL);
        assertPass(gen, new byte[]{123,45,0,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test half-word indexed load.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1);
        gen.addLoad16Indexed(Register.R0, 0);
        gen.addJumpIfR0Equals((45 << 8) | 67, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,67,0,0,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test word indexed load.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1);
        gen.addLoad32Indexed(Register.R0, 0);
        gen.addJumpIfR0Equals((45 << 24) | (67 << 16) | (89 << 8) | 12, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{123,45,67,89,12,0,0,0,0,0,0,0,0,0,0}, 0);

        // Test jumping if greater than.
        gen = new ApfGenerator();
        gen.addJumpIfR0GreaterThan(0, gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfR0GreaterThan(0, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if less than.
        gen = new ApfGenerator();
        gen.addJumpIfR0LessThan(0, gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addJumpIfR0LessThan(1, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if any bits set.
        gen = new ApfGenerator();
        gen.addJumpIfR0AnyBitsSet(3, gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfR0AnyBitsSet(3, gen.DROP_LABEL);
        assertDrop(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 3);
        gen.addJumpIfR0AnyBitsSet(3, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if register greater than.
        gen = new ApfGenerator();
        gen.addJumpIfR0GreaterThanR1(gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 2);
        gen.addLoadImmediate(Register.R1, 1);
        gen.addJumpIfR0GreaterThanR1(gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if register less than.
        gen = new ApfGenerator();
        gen.addJumpIfR0LessThanR1(gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1);
        gen.addJumpIfR0LessThanR1(gen.DROP_LABEL);
        assertDrop(gen);

        // Test jumping if any bits set in register.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 3);
        gen.addJumpIfR0AnyBitsSetR1(gen.DROP_LABEL);
        assertPass(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 3);
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfR0AnyBitsSetR1(gen.DROP_LABEL);
        assertDrop(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 3);
        gen.addLoadImmediate(Register.R0, 3);
        gen.addJumpIfR0AnyBitsSetR1(gen.DROP_LABEL);
        assertDrop(gen);

        // Test load from memory.
        gen = new ApfGenerator();
        gen.addLoadFromMemory(Register.R0, 0);
        gen.addJumpIfR0Equals(0, gen.DROP_LABEL);
        assertDrop(gen);

        // Test store to memory.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1234567890);
        gen.addStoreToMemory(Register.R1, 12);
        gen.addLoadFromMemory(Register.R0, 12);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test filter age pre-filled memory.
        gen = new ApfGenerator();
        gen.addLoadFromMemory(Register.R0, gen.FILTER_AGE_MEMORY_SLOT);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen, new byte[MIN_PKT_SIZE], 1234567890);

        // Test packet size pre-filled memory.
        gen = new ApfGenerator();
        gen.addLoadFromMemory(Register.R0, gen.PACKET_SIZE_MEMORY_SLOT);
        gen.addJumpIfR0Equals(MIN_PKT_SIZE, gen.DROP_LABEL);
        assertDrop(gen);

        // Test IPv4 header size pre-filled memory.
        gen = new ApfGenerator();
        gen.addLoadFromMemory(Register.R0, gen.IPV4_HEADER_SIZE_MEMORY_SLOT);
        gen.addJumpIfR0Equals(20, gen.DROP_LABEL);
        assertDrop(gen, new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0x45}, 0);

        // Test not.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addNot(Register.R0);
        gen.addJumpIfR0Equals(~1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test negate.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addNeg(Register.R0);
        gen.addJumpIfR0Equals(-1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test move.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1234567890);
        gen.addMove(Register.R0);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addMove(Register.R1);
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);

        // Test swap.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R1, 1234567890);
        gen.addSwap();
        gen.addJumpIfR0Equals(1234567890, gen.DROP_LABEL);
        assertDrop(gen);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1234567890);
        gen.addSwap();
        gen.addJumpIfR0Equals(0, gen.DROP_LABEL);
        assertDrop(gen);

        // Test jump if bytes not equal.
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfBytesNotEqual(Register.R0, new byte[]{123}, gen.DROP_LABEL);
        program = gen.generate();
        assertEquals(6, program.length);
        assertEquals((13 << 3) | (1 << 1) | 0, program[0]);
        assertEquals(1, program[1]);
        assertEquals(((20 << 3) | (1 << 1) | 0) - 256, program[2]);
        assertEquals(1, program[3]);
        assertEquals(1, program[4]);
        assertEquals(123, program[5]);
        assertDrop(program, new byte[MIN_PKT_SIZE], 0);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfBytesNotEqual(Register.R0, new byte[]{123}, gen.DROP_LABEL);
        byte[] packet123 = new byte[]{0,123,0,0,0,0,0,0,0,0,0,0,0,0,0};
        assertPass(gen, packet123, 0);
        gen = new ApfGenerator();
        gen.addJumpIfBytesNotEqual(Register.R0, new byte[]{123}, gen.DROP_LABEL);
        assertDrop(gen, packet123, 0);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfBytesNotEqual(Register.R0, new byte[]{1,2,30,4,5}, gen.DROP_LABEL);
        byte[] packet12345 = new byte[]{0,1,2,3,4,5,0,0,0,0,0,0,0,0,0};
        assertDrop(gen, packet12345, 0);
        gen = new ApfGenerator();
        gen.addLoadImmediate(Register.R0, 1);
        gen.addJumpIfBytesNotEqual(Register.R0, new byte[]{1,2,3,4,5}, gen.DROP_LABEL);
        assertPass(gen, packet12345, 0);
    }

    /**
     * Generate some BPF programs, translate them to APF, then run APF and BPF programs
     * over packet traces and verify both programs filter out the same packets.
     */
    @LargeTest
    public void testApfAgainstBpf() throws Exception {
        String[] tcpdump_filters = new String[]{ "udp", "tcp", "icmp", "icmp6", "udp port 53",
                "arp", "dst 239.255.255.250", "arp or tcp or udp port 53", "net 192.168.1.0/24",
                "arp or icmp6 or portrange 53-54", "portrange 53-54 or portrange 100-50000",
                "tcp[tcpflags] & (tcp-ack|tcp-fin) != 0 and (ip[2:2] > 57 or icmp)" };
        String pcap_filename = stageFile(R.raw.apf);
        for (String tcpdump_filter : tcpdump_filters) {
            byte[] apf_program = Bpf2Apf.convert(compileToBpf(tcpdump_filter));
            assertTrue("Failed to match for filter: " + tcpdump_filter,
                    compareBpfApf(tcpdump_filter, pcap_filename, apf_program));
        }
    }

    private class MockIpManagerCallback extends IpManager.Callback {
        private final ConditionVariable mGotApfProgram = new ConditionVariable();
        private byte[] mLastApfProgram;

        @Override
        public void installPacketFilter(byte[] filter) {
            mLastApfProgram = filter;
            mGotApfProgram.open();
        }

        public void resetApfProgramWait() {
            mGotApfProgram.close();
        }

        public byte[] getApfProgram() {
            assertTrue(mGotApfProgram.block(TIMEOUT_MS));
            return mLastApfProgram;
        }

        public void assertNoProgramUpdate() {
            assertFalse(mGotApfProgram.block(TIMEOUT_MS));
        }
    }

    private static class TestApfFilter extends ApfFilter {
        public final static byte[] MOCK_MAC_ADDR = new byte[]{1,2,3,4,5,6};
        private FileDescriptor mWriteSocket;

        public TestApfFilter(IpManager.Callback ipManagerCallback, boolean multicastFilter,
                IpConnectivityLog log) throws Exception {
            super(new ApfCapabilities(2, 1536, ARPHRD_ETHER), NetworkInterface.getByName("lo"),
                    ipManagerCallback, multicastFilter, log);
        }

        // Pretend an RA packet has been received and show it to ApfFilter.
        public void pretendPacketReceived(byte[] packet) throws IOException, ErrnoException {
            // ApfFilter's ReceiveThread will be waiting to read this.
            Os.write(mWriteSocket, packet, 0, packet.length);
        }

        @Override
        void maybeStartFilter() {
            mHardwareAddress = MOCK_MAC_ADDR;
            installNewProgramLocked();

            // Create two sockets, "readSocket" and "mWriteSocket" and connect them together.
            FileDescriptor readSocket = new FileDescriptor();
            mWriteSocket = new FileDescriptor();
            try {
                Os.socketpair(AF_UNIX, SOCK_STREAM, 0, mWriteSocket, readSocket);
            } catch (ErrnoException e) {
                fail();
                return;
            }
            // Now pass readSocket to ReceiveThread as if it was setup to read raw RAs.
            // This allows us to pretend RA packets have been recieved via pretendPacketReceived().
            mReceiveThread = new ReceiveThread(readSocket);
            mReceiveThread.start();
        }

        @Override
        public void shutdown() {
            super.shutdown();
            IoUtils.closeQuietly(mWriteSocket);
        }
    }

    private static final int ETH_HEADER_LEN = 14;
    private static final int ETH_DEST_ADDR_OFFSET = 0;
    private static final int ETH_ETHERTYPE_OFFSET = 12;
    private static final byte[] ETH_BROADCAST_MAC_ADDRESS = new byte[]{
        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };

    private static final int IPV4_VERSION_IHL_OFFSET = ETH_HEADER_LEN + 0;
    private static final int IPV4_PROTOCOL_OFFSET = ETH_HEADER_LEN + 9;
    private static final int IPV4_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 16;

    private static final int IPV6_NEXT_HEADER_OFFSET = ETH_HEADER_LEN + 6;
    private static final int IPV6_HEADER_LEN = 40;
    private static final int IPV6_DEST_ADDR_OFFSET = ETH_HEADER_LEN + 24;
    // The IPv6 all nodes address ff02::1
    private static final byte[] IPV6_ALL_NODES_ADDRESS =
            new byte[]{ (byte) 0xff, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 };

    private static final int ICMP6_TYPE_OFFSET = ETH_HEADER_LEN + IPV6_HEADER_LEN;
    private static final int ICMP6_ROUTER_ADVERTISEMENT = 134;
    private static final int ICMP6_NEIGHBOR_ANNOUNCEMENT = 136;

    private static final int ICMP6_RA_HEADER_LEN = 16;
    private static final int ICMP6_RA_ROUTER_LIFETIME_OFFSET =
            ETH_HEADER_LEN + IPV6_HEADER_LEN + 6;
    private static final int ICMP6_RA_CHECKSUM_OFFSET =
            ETH_HEADER_LEN + IPV6_HEADER_LEN + 2;
    private static final int ICMP6_RA_OPTION_OFFSET =
            ETH_HEADER_LEN + IPV6_HEADER_LEN + ICMP6_RA_HEADER_LEN;

    private static final int ICMP6_PREFIX_OPTION_TYPE = 3;
    private static final int ICMP6_PREFIX_OPTION_LEN = 32;
    private static final int ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET = 4;
    private static final int ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET = 8;

    // From RFC6106: Recursive DNS Server option
    private static final int ICMP6_RDNSS_OPTION_TYPE = 25;
    // From RFC6106: DNS Search List option
    private static final int ICMP6_DNSSL_OPTION_TYPE = 31;

    // From RFC4191: Route Information option
    private static final int ICMP6_ROUTE_INFO_OPTION_TYPE = 24;
    // Above three options all have the same format:
    private static final int ICMP6_4_BYTE_OPTION_LEN = 8;
    private static final int ICMP6_4_BYTE_LIFETIME_OFFSET = 4;
    private static final int ICMP6_4_BYTE_LIFETIME_LEN = 4;

    private static final int UDP_HEADER_LEN = 8;
    private static final int UDP_DESTINATION_PORT_OFFSET = ETH_HEADER_LEN + 22;

    private static final int DHCP_CLIENT_PORT = 68;
    private static final int DHCP_CLIENT_MAC_OFFSET = ETH_HEADER_LEN + UDP_HEADER_LEN + 48;

    private static final int ARP_HEADER_OFFSET = ETH_HEADER_LEN;
    private static final byte[] ARP_IPV4_REQUEST_HEADER = new byte[]{
            0, 1, // Hardware type: Ethernet (1)
            8, 0, // Protocol type: IP (0x0800)
            6,    // Hardware size: 6
            4,    // Protocol size: 4
            0, 1  // Opcode: request (1)
    };
    private static final byte[] ARP_IPV4_REPLY_HEADER = new byte[]{
            0, 1, // Hardware type: Ethernet (1)
            8, 0, // Protocol type: IP (0x0800)
            6,    // Hardware size: 6
            4,    // Protocol size: 4
            0, 2  // Opcode: reply (2)
    };
    private static final int ARP_TARGET_IP_ADDRESS_OFFSET = ETH_HEADER_LEN + 24;

    private static final byte[] MOCK_IPV4_ADDR = new byte[]{10, 0, 0, 1};
    private static final byte[] ANOTHER_IPV4_ADDR = new byte[]{10, 0, 0, 2};
    private static final byte[] IPV4_ANY_HOST_ADDR = new byte[]{0, 0, 0, 0};

    @LargeTest
    public void testApfFilterIPv4() throws Exception {
        MockIpManagerCallback ipManagerCallback = new MockIpManagerCallback();
        ApfFilter apfFilter = new TestApfFilter(ipManagerCallback, DROP_MULTICAST, mLog);
        byte[] program = ipManagerCallback.getApfProgram();

        // Verify empty packet of 100 zero bytes is passed
        ByteBuffer packet = ByteBuffer.wrap(new byte[100]);
        assertPass(program, packet.array(), 0);

        // Verify unicast IPv4 packet is passed
        packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IP);
        assertPass(program, packet.array(), 0);

        // Verify broadcast IPv4, not DHCP to us, is dropped
        packet.put(ETH_BROADCAST_MAC_ADDRESS);
        assertDrop(program, packet.array(), 0);
        packet.put(IPV4_VERSION_IHL_OFFSET, (byte)0x45);
        assertDrop(program, packet.array(), 0);
        packet.put(IPV4_PROTOCOL_OFFSET, (byte)IPPROTO_UDP);
        assertDrop(program, packet.array(), 0);
        packet.putShort(UDP_DESTINATION_PORT_OFFSET, (short)DHCP_CLIENT_PORT);
        assertDrop(program, packet.array(), 0);

        // Verify broadcast IPv4 DHCP to us is passed
        packet.position(DHCP_CLIENT_MAC_OFFSET);
        packet.put(TestApfFilter.MOCK_MAC_ADDR);
        assertPass(program, packet.array(), 0);

        apfFilter.shutdown();
    }

    @LargeTest
    public void testApfFilterIPv6() throws Exception {
        MockIpManagerCallback ipManagerCallback = new MockIpManagerCallback();
        ApfFilter apfFilter = new TestApfFilter(ipManagerCallback, ALLOW_MULTICAST, mLog);
        byte[] program = ipManagerCallback.getApfProgram();

        // Verify empty IPv6 packet is passed
        ByteBuffer packet = ByteBuffer.wrap(new byte[100]);
        packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IPV6);
        assertPass(program, packet.array(), 0);

        // Verify empty ICMPv6 packet is passed
        packet.put(IPV6_NEXT_HEADER_OFFSET, (byte)IPPROTO_ICMPV6);
        assertPass(program, packet.array(), 0);

        // Verify empty ICMPv6 NA packet is passed
        packet.put(ICMP6_TYPE_OFFSET, (byte)ICMP6_NEIGHBOR_ANNOUNCEMENT);
        assertPass(program, packet.array(), 0);

        // Verify ICMPv6 NA to ff02::1 is dropped
        packet.position(IPV6_DEST_ADDR_OFFSET);
        packet.put(IPV6_ALL_NODES_ADDRESS);
        assertDrop(program, packet.array(), 0);

        apfFilter.shutdown();
    }

    @LargeTest
    public void testApfFilterMulticast() throws Exception {
        MockIpManagerCallback ipManagerCallback = new MockIpManagerCallback();
        ApfFilter apfFilter = new TestApfFilter(ipManagerCallback, ALLOW_MULTICAST, mLog);
        byte[] program = ipManagerCallback.getApfProgram();

        // Construct IPv4 and IPv6 multicast packets.
        ByteBuffer mcastv4packet = ByteBuffer.wrap(new byte[100]);
        mcastv4packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IP);
        mcastv4packet.position(IPV4_DEST_ADDR_OFFSET);
        mcastv4packet.put(new byte[]{(byte)224,0,0,1});

        ByteBuffer mcastv6packet = ByteBuffer.wrap(new byte[100]);
        mcastv6packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IPV6);
        mcastv6packet.put(IPV6_NEXT_HEADER_OFFSET, (byte)IPPROTO_UDP);
        mcastv6packet.position(IPV6_DEST_ADDR_OFFSET);
        mcastv6packet.put(new byte[]{(byte)0xff,2,0,0,0,0,0,0,0,0,0,0,0,0,0,(byte)0xfb});

        // Construct IPv4 broadcast packet.
        ByteBuffer bcastv4packet = ByteBuffer.wrap(new byte[100]);
        bcastv4packet.put(ETH_BROADCAST_MAC_ADDRESS);
        bcastv4packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IP);
        bcastv4packet.position(IPV4_DEST_ADDR_OFFSET);
        bcastv4packet.put(new byte[]{(byte)192,(byte)0,(byte)2,(byte)63});

        // Verify initially disabled multicast filter is off
        assertPass(program, bcastv4packet.array(), 0);
        assertPass(program, mcastv4packet.array(), 0);
        assertPass(program, mcastv6packet.array(), 0);

        // Turn on multicast filter and verify it works
        ipManagerCallback.resetApfProgramWait();
        apfFilter.setMulticastFilter(true);
        program = ipManagerCallback.getApfProgram();
        assertDrop(program, bcastv4packet.array(), 0);
        assertDrop(program, mcastv4packet.array(), 0);
        assertDrop(program, mcastv6packet.array(), 0);

        // Turn off multicast filter and verify it's off
        ipManagerCallback.resetApfProgramWait();
        apfFilter.setMulticastFilter(false);
        program = ipManagerCallback.getApfProgram();
        assertPass(program, bcastv4packet.array(), 0);
        assertPass(program, mcastv4packet.array(), 0);
        assertPass(program, mcastv6packet.array(), 0);

        // Verify it can be initialized to on
        ipManagerCallback.resetApfProgramWait();
        apfFilter.shutdown();
        apfFilter = new TestApfFilter(ipManagerCallback, DROP_MULTICAST, mLog);
        program = ipManagerCallback.getApfProgram();
        assertDrop(program, bcastv4packet.array(), 0);
        assertDrop(program, mcastv4packet.array(), 0);
        assertDrop(program, mcastv6packet.array(), 0);

        // Verify that ICMPv6 multicast is not dropped.
        mcastv6packet.put(IPV6_NEXT_HEADER_OFFSET, (byte)IPPROTO_ICMPV6);
        assertPass(program, mcastv6packet.array(), 0);

        apfFilter.shutdown();
    }

    private byte[] getProgram(MockIpManagerCallback cb, ApfFilter filter, LinkProperties lp) {
        cb.resetApfProgramWait();
        filter.setLinkProperties(lp);
        return cb.getApfProgram();
    }

    private void verifyArpFilter(byte[] program, int filterResult) {
        // Verify ARP request packet
        assertPass(program, arpRequestBroadcast(MOCK_IPV4_ADDR), 0);
        assertVerdict(filterResult, program, arpRequestBroadcast(ANOTHER_IPV4_ADDR), 0);
        assertDrop(program, arpRequestBroadcast(IPV4_ANY_HOST_ADDR), 0);

        // Verify unicast ARP reply packet is always accepted.
        assertPass(program, arpReplyUnicast(MOCK_IPV4_ADDR), 0);
        assertPass(program, arpReplyUnicast(ANOTHER_IPV4_ADDR), 0);
        assertPass(program, arpReplyUnicast(IPV4_ANY_HOST_ADDR), 0);

        // Verify GARP reply packets are always filtered
        assertDrop(program, garpReply(), 0);
    }

    @LargeTest
    public void testApfFilterArp() throws Exception {
        MockIpManagerCallback ipManagerCallback = new MockIpManagerCallback();
        ApfFilter apfFilter = new TestApfFilter(ipManagerCallback, ALLOW_MULTICAST, mLog);

        // Verify initially ARP request filter is off, and GARP filter is on.
        verifyArpFilter(ipManagerCallback.getApfProgram(), PASS);

        // Inform ApfFilter of our address and verify ARP filtering is on
        LinkAddress linkAddress = new LinkAddress(InetAddress.getByAddress(MOCK_IPV4_ADDR), 24);
        LinkProperties lp = new LinkProperties();
        assertTrue(lp.addLinkAddress(linkAddress));
        verifyArpFilter(getProgram(ipManagerCallback, apfFilter, lp), DROP);

        // Inform ApfFilter of loss of IP and verify ARP filtering is off
        verifyArpFilter(getProgram(ipManagerCallback, apfFilter, new LinkProperties()), PASS);

        apfFilter.shutdown();
    }

    private static byte[] arpRequestBroadcast(byte[] tip) {
        ByteBuffer packet = ByteBuffer.wrap(new byte[100]);
        packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_ARP);
        packet.position(ETH_DEST_ADDR_OFFSET);
        packet.put(ETH_BROADCAST_MAC_ADDRESS);
        packet.position(ARP_HEADER_OFFSET);
        packet.put(ARP_IPV4_REQUEST_HEADER);
        packet.position(ARP_TARGET_IP_ADDRESS_OFFSET);
        packet.put(tip);
        return packet.array();
    }

    private static byte[] arpReplyUnicast(byte[] tip) {
        ByteBuffer packet = ByteBuffer.wrap(new byte[100]);
        packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_ARP);
        packet.position(ARP_HEADER_OFFSET);
        packet.put(ARP_IPV4_REPLY_HEADER);
        packet.position(ARP_TARGET_IP_ADDRESS_OFFSET);
        packet.put(tip);
        return packet.array();
    }

    private static byte[] garpReply() {
        ByteBuffer packet = ByteBuffer.wrap(new byte[100]);
        packet.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_ARP);
        packet.position(ETH_DEST_ADDR_OFFSET);
        packet.put(ETH_BROADCAST_MAC_ADDRESS);
        packet.position(ARP_HEADER_OFFSET);
        packet.put(ARP_IPV4_REPLY_HEADER);
        packet.position(ARP_TARGET_IP_ADDRESS_OFFSET);
        packet.put(IPV4_ANY_HOST_ADDR);
        return packet.array();
    }

    // Verify that the last program pushed to the IpManager.Callback properly filters the
    // given packet for the given lifetime.
    private void verifyRaLifetime(MockIpManagerCallback ipManagerCallback, ByteBuffer packet,
            int lifetime) {
        byte[] program = ipManagerCallback.getApfProgram();

        // Verify new program should drop RA for 1/6th its lifetime
        assertDrop(program, packet.array(), 0);
        assertDrop(program, packet.array(), lifetime/6);
        assertPass(program, packet.array(), lifetime/6 + 1);
        assertPass(program, packet.array(), lifetime);

        // Verify RA checksum is ignored
        packet.putShort(ICMP6_RA_CHECKSUM_OFFSET, (short)12345);
        assertDrop(program, packet.array(), 0);
        packet.putShort(ICMP6_RA_CHECKSUM_OFFSET, (short)-12345);
        assertDrop(program, packet.array(), 0);

        // Verify other changes to RA make it not match filter
        packet.put(0, (byte)-1);
        assertPass(program, packet.array(), 0);
        packet.put(0, (byte)0);
        assertDrop(program, packet.array(), 0);
    }

    // Test that when ApfFilter is shown the given packet, it generates a program to filter it
    // for the given lifetime.
    private void testRaLifetime(TestApfFilter apfFilter, MockIpManagerCallback ipManagerCallback,
            ByteBuffer packet, int lifetime) throws IOException, ErrnoException {
        // Verify new program generated if ApfFilter witnesses RA
        ipManagerCallback.resetApfProgramWait();
        apfFilter.pretendPacketReceived(packet.array());
        ipManagerCallback.getApfProgram();

        verifyRaLifetime(ipManagerCallback, packet, lifetime);
    }

    private void verifyRaEvent(RaEvent expected) {
        ArgumentCaptor<Parcelable> captor = ArgumentCaptor.forClass(Parcelable.class);
        verify(mLog, atLeastOnce()).log(captor.capture());
        RaEvent got = lastRaEvent(captor.getAllValues());
        if (!raEventEquals(expected, got)) {
            assertEquals(expected, got);  // fail for printing an assertion error message.
        }
    }

    private RaEvent lastRaEvent(List<Parcelable> events) {
        RaEvent got = null;
        for (Parcelable ev : events) {
            if (ev instanceof RaEvent) {
                got = (RaEvent) ev;
            }
        }
        return got;
    }

    private boolean raEventEquals(RaEvent ev1, RaEvent ev2) {
        return (ev1 != null) && (ev2 != null)
                && (ev1.routerLifetime == ev2.routerLifetime)
                && (ev1.prefixValidLifetime == ev2.prefixValidLifetime)
                && (ev1.prefixPreferredLifetime == ev2.prefixPreferredLifetime)
                && (ev1.routeInfoLifetime == ev2.routeInfoLifetime)
                && (ev1.rdnssLifetime == ev2.rdnssLifetime)
                && (ev1.dnsslLifetime == ev2.dnsslLifetime);
    }

    private void assertInvalidRa(TestApfFilter apfFilter, MockIpManagerCallback ipManagerCallback,
            ByteBuffer packet) throws IOException, ErrnoException {
        ipManagerCallback.resetApfProgramWait();
        apfFilter.pretendPacketReceived(packet.array());
        ipManagerCallback.assertNoProgramUpdate();
    }

    @LargeTest
    public void testApfFilterRa() throws Exception {
        MockIpManagerCallback ipManagerCallback = new MockIpManagerCallback();
        TestApfFilter apfFilter = new TestApfFilter(ipManagerCallback, DROP_MULTICAST, mLog);
        byte[] program = ipManagerCallback.getApfProgram();

        // Verify RA is passed the first time
        ByteBuffer basePacket = ByteBuffer.wrap(new byte[ICMP6_RA_OPTION_OFFSET]);
        basePacket.putShort(ETH_ETHERTYPE_OFFSET, (short)ETH_P_IPV6);
        basePacket.put(IPV6_NEXT_HEADER_OFFSET, (byte)IPPROTO_ICMPV6);
        basePacket.put(ICMP6_TYPE_OFFSET, (byte)ICMP6_ROUTER_ADVERTISEMENT);
        basePacket.putShort(ICMP6_RA_ROUTER_LIFETIME_OFFSET, (short)1000);
        basePacket.position(IPV6_DEST_ADDR_OFFSET);
        basePacket.put(IPV6_ALL_NODES_ADDRESS);
        assertPass(program, basePacket.array(), 0);

        testRaLifetime(apfFilter, ipManagerCallback, basePacket, 1000);
        verifyRaEvent(new RaEvent(1000, -1, -1, -1, -1, -1));

        // Ensure zero-length options cause the packet to be silently skipped.
        // Do this before we test other packets. http://b/29586253
        ByteBuffer zeroLengthOptionPacket = ByteBuffer.wrap(
                new byte[ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_OPTION_LEN]);
        basePacket.clear();
        zeroLengthOptionPacket.put(basePacket);
        zeroLengthOptionPacket.put((byte)ICMP6_PREFIX_OPTION_TYPE);
        zeroLengthOptionPacket.put((byte)0);
        assertInvalidRa(apfFilter, ipManagerCallback, zeroLengthOptionPacket);

        // Generate several RAs with different options and lifetimes, and verify when
        // ApfFilter is shown these packets, it generates programs to filter them for the
        // appropriate lifetime.
        ByteBuffer prefixOptionPacket = ByteBuffer.wrap(
                new byte[ICMP6_RA_OPTION_OFFSET + ICMP6_PREFIX_OPTION_LEN]);
        basePacket.clear();
        prefixOptionPacket.put(basePacket);
        prefixOptionPacket.put((byte)ICMP6_PREFIX_OPTION_TYPE);
        prefixOptionPacket.put((byte)(ICMP6_PREFIX_OPTION_LEN / 8));
        prefixOptionPacket.putInt(
                ICMP6_RA_OPTION_OFFSET + ICMP6_PREFIX_OPTION_PREFERRED_LIFETIME_OFFSET, 100);
        prefixOptionPacket.putInt(
                ICMP6_RA_OPTION_OFFSET + ICMP6_PREFIX_OPTION_VALID_LIFETIME_OFFSET, 200);
        testRaLifetime(apfFilter, ipManagerCallback, prefixOptionPacket, 100);
        verifyRaEvent(new RaEvent(1000, 200, 100, -1, -1, -1));

        ByteBuffer rdnssOptionPacket = ByteBuffer.wrap(
                new byte[ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_OPTION_LEN]);
        basePacket.clear();
        rdnssOptionPacket.put(basePacket);
        rdnssOptionPacket.put((byte)ICMP6_RDNSS_OPTION_TYPE);
        rdnssOptionPacket.put((byte)(ICMP6_4_BYTE_OPTION_LEN / 8));
        rdnssOptionPacket.putInt(
                ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_LIFETIME_OFFSET, 300);
        testRaLifetime(apfFilter, ipManagerCallback, rdnssOptionPacket, 300);
        verifyRaEvent(new RaEvent(1000, -1, -1, -1, 300, -1));

        ByteBuffer routeInfoOptionPacket = ByteBuffer.wrap(
                new byte[ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_OPTION_LEN]);
        basePacket.clear();
        routeInfoOptionPacket.put(basePacket);
        routeInfoOptionPacket.put((byte)ICMP6_ROUTE_INFO_OPTION_TYPE);
        routeInfoOptionPacket.put((byte)(ICMP6_4_BYTE_OPTION_LEN / 8));
        routeInfoOptionPacket.putInt(
                ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_LIFETIME_OFFSET, 400);
        testRaLifetime(apfFilter, ipManagerCallback, routeInfoOptionPacket, 400);
        verifyRaEvent(new RaEvent(1000, -1, -1, 400, -1, -1));

        ByteBuffer dnsslOptionPacket = ByteBuffer.wrap(
                new byte[ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_OPTION_LEN]);
        basePacket.clear();
        dnsslOptionPacket.put(basePacket);
        dnsslOptionPacket.put((byte)ICMP6_DNSSL_OPTION_TYPE);
        dnsslOptionPacket.put((byte)(ICMP6_4_BYTE_OPTION_LEN / 8));
        dnsslOptionPacket.putInt(
                ICMP6_RA_OPTION_OFFSET + ICMP6_4_BYTE_LIFETIME_OFFSET, 2000);
        // Note that lifetime of 2000 will be ignored in favor of shorter
        // route lifetime of 1000.
        testRaLifetime(apfFilter, ipManagerCallback, dnsslOptionPacket, 1000);
        verifyRaEvent(new RaEvent(1000, -1, -1, -1, -1, 2000));

        // Verify that current program filters all five RAs:
        verifyRaLifetime(ipManagerCallback, basePacket, 1000);
        verifyRaLifetime(ipManagerCallback, prefixOptionPacket, 100);
        verifyRaLifetime(ipManagerCallback, rdnssOptionPacket, 300);
        verifyRaLifetime(ipManagerCallback, routeInfoOptionPacket, 400);
        verifyRaLifetime(ipManagerCallback, dnsslOptionPacket, 1000);

        apfFilter.shutdown();
    }

    /**
     * Stage a file for testing, i.e. make it native accessible. Given a resource ID,
     * copy that resource into the app's data directory and return the path to it.
     */
    private String stageFile(int rawId) throws Exception {
        File file = new File(getContext().getFilesDir(), "staged_file");
        new File(file.getParent()).mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContext().getResources().openRawResource(rawId);
            out = new FileOutputStream(file);
            Streams.copy(in, out);
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
        }
        return file.getAbsolutePath();
    }

    /**
     * Call the APF interpreter the run {@code program} on {@code packet} pretending the
     * filter was installed {@code filter_age} seconds ago.
     */
    private native static int apfSimulate(byte[] program, byte[] packet, int filter_age);

    /**
     * Compile a tcpdump human-readable filter (e.g. "icmp" or "tcp port 54") into a BPF
     * prorgam and return a human-readable dump of the BPF program identical to "tcpdump -d".
     */
    private native static String compileToBpf(String filter);

    /**
     * Open packet capture file {@code pcap_filename} and filter the packets using tcpdump
     * human-readable filter (e.g. "icmp" or "tcp port 54") compiled to a BPF program and
     * at the same time using APF program {@code apf_program}.  Return {@code true} if
     * both APF and BPF programs filter out exactly the same packets.
     */
    private native static boolean compareBpfApf(String filter, String pcap_filename,
            byte[] apf_program);
}
