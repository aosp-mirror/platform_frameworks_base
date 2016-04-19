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

package com.android.server;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.frameworks.servicestests.R;
import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import libcore.io.IoUtils;
import libcore.io.Streams;

/**
 * Tests for APF program generator and interpreter.
 *
 * Build, install and run with:
 *  runtest frameworks-services -c com.android.server.ApfTest
 */
public class ApfTest extends AndroidTestCase {
    @Override
    public void setUp() throws Exception {
        super.setUp();
        // Load up native shared library containing APF interpreter exposed via JNI.
        System.loadLibrary("servicestestsjni");
    }

    // Expected return codes from APF interpreter.
    private final static int PASS = 1;
    private final static int DROP = 0;
    // Interpreter will just accept packets without link layer headers, so pad fake packet to at
    // least the minimum packet size.
    private final static int MIN_PKT_SIZE = 15;

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
