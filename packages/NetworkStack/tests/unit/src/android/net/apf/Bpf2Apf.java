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

package android.net.apf;

import android.net.apf.ApfGenerator;
import android.net.apf.ApfGenerator.IllegalInstructionException;
import android.net.apf.ApfGenerator.Register;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * BPF to APF translator.
 *
 * Note: This is for testing purposes only and is not guaranteed to support
 *       translation of all BPF programs.
 *
 * Example usage:
 *   javac net/java/android/net/apf/ApfGenerator.java \
 *         tests/servicestests/src/android/net/apf/Bpf2Apf.java
 *   sudo tcpdump -i em1 -d icmp | java -classpath tests/servicestests/src:net/java \
 *                                      android.net.apf.Bpf2Apf
 */
public class Bpf2Apf {
    private static int parseImm(String line, String arg) {
        if (!arg.startsWith("#0x")) {
            throw new IllegalArgumentException("Unhandled instruction: " + line);
        }
        final long val_long = Long.parseLong(arg.substring(3), 16);
        if (val_long < 0 || val_long > Long.parseLong("ffffffff", 16)) {
            throw new IllegalArgumentException("Unhandled instruction: " + line);
        }
        return new Long((val_long << 32) >> 32).intValue();
    }

    /**
     * Convert a single line of "tcpdump -d" (human readable BPF program dump) {@code line} into
     * APF instruction(s) and append them to {@code gen}. Here's an example line:
     * (001) jeq      #0x86dd          jt 2    jf 7
     */
    private static void convertLine(String line, ApfGenerator gen)
            throws IllegalInstructionException {
        if (line.indexOf("(") != 0 || line.indexOf(")") != 4 || line.indexOf(" ") != 5) {
            throw new IllegalArgumentException("Unhandled instruction: " + line);
        }
        int label = Integer.parseInt(line.substring(1, 4));
        gen.defineLabel(Integer.toString(label));
        String opcode = line.substring(6, 10).trim();
        String arg = line.substring(15, Math.min(32, line.length())).trim();
        switch (opcode) {
            case "ld":
            case "ldh":
            case "ldb":
            case "ldx":
            case "ldxb":
            case "ldxh":
                Register dest = opcode.contains("x") ? Register.R1 : Register.R0;
                if (arg.equals("4*([14]&0xf)")) {
                    if (!opcode.equals("ldxb")) {
                        throw new IllegalArgumentException("Unhandled instruction: " + line);
                    }
                    gen.addLoadFromMemory(dest, gen.IPV4_HEADER_SIZE_MEMORY_SLOT);
                    break;
                }
                if (arg.equals("#pktlen")) {
                    if (!opcode.equals("ld")) {
                        throw new IllegalArgumentException("Unhandled instruction: " + line);
                    }
                    gen.addLoadFromMemory(dest, gen.PACKET_SIZE_MEMORY_SLOT);
                    break;
                }
                if (arg.startsWith("#0x")) {
                    if (!opcode.equals("ld")) {
                        throw new IllegalArgumentException("Unhandled instruction: " + line);
                    }
                    gen.addLoadImmediate(dest, parseImm(line, arg));
                    break;
                }
                if (arg.startsWith("M[")) {
                    if (!opcode.startsWith("ld")) {
                        throw new IllegalArgumentException("Unhandled instruction: " + line);
                    }
                    int memory_slot = Integer.parseInt(arg.substring(2, arg.length() - 1));
                    if (memory_slot < 0 || memory_slot >= gen.MEMORY_SLOTS ||
                            // Disallow use of pre-filled slots as BPF programs might
                            // wrongfully assume they're initialized to 0.
                            (memory_slot >= gen.FIRST_PREFILLED_MEMORY_SLOT &&
                                    memory_slot <= gen.LAST_PREFILLED_MEMORY_SLOT)) {
                        throw new IllegalArgumentException("Unhandled instruction: " + line);
                    }
                    gen.addLoadFromMemory(dest, memory_slot);
                    break;
                }
                if (arg.startsWith("[x + ")) {
                    int offset = Integer.parseInt(arg.substring(5, arg.length() - 1));
                    switch (opcode) {
                        case "ld":
                        case "ldx":
                            gen.addLoad32Indexed(dest, offset);
                            break;
                        case "ldh":
                        case "ldxh":
                            gen.addLoad16Indexed(dest, offset);
                            break;
                        case "ldb":
                        case "ldxb":
                            gen.addLoad8Indexed(dest, offset);
                            break;
                    }
                } else {
                    int offset = Integer.parseInt(arg.substring(1, arg.length() - 1));
                    switch (opcode) {
                        case "ld":
                        case "ldx":
                            gen.addLoad32(dest, offset);
                            break;
                        case "ldh":
                        case "ldxh":
                            gen.addLoad16(dest, offset);
                            break;
                        case "ldb":
                        case "ldxb":
                            gen.addLoad8(dest, offset);
                            break;
                    }
                }
                break;
            case "st":
            case "stx":
                Register src = opcode.contains("x") ? Register.R1 : Register.R0;
                if (!arg.startsWith("M[")) {
                    throw new IllegalArgumentException("Unhandled instruction: " + line);
                }
                int memory_slot = Integer.parseInt(arg.substring(2, arg.length() - 1));
                if (memory_slot < 0 || memory_slot >= gen.MEMORY_SLOTS ||
                        // Disallow overwriting pre-filled slots
                        (memory_slot >= gen.FIRST_PREFILLED_MEMORY_SLOT &&
                                memory_slot <= gen.LAST_PREFILLED_MEMORY_SLOT)) {
                    throw new IllegalArgumentException("Unhandled instruction: " + line);
                }
                gen.addStoreToMemory(src, memory_slot);
                break;
            case "add":
            case "and":
            case "or":
            case "sub":
                if (arg.equals("x")) {
                    switch(opcode) {
                        case "add":
                            gen.addAddR1();
                            break;
                        case "and":
                            gen.addAndR1();
                            break;
                        case "or":
                            gen.addOrR1();
                            break;
                        case "sub":
                            gen.addNeg(Register.R1);
                            gen.addAddR1();
                            gen.addNeg(Register.R1);
                            break;
                    }
                } else {
                    int imm = parseImm(line, arg);
                    switch(opcode) {
                        case "add":
                            gen.addAdd(imm);
                            break;
                        case "and":
                            gen.addAnd(imm);
                            break;
                        case "or":
                            gen.addOr(imm);
                            break;
                        case "sub":
                            gen.addAdd(-imm);
                            break;
                    }
                }
                break;
            case "jeq":
            case "jset":
            case "jgt":
            case "jge":
                int val = 0;
                boolean reg_compare;
                if (arg.startsWith("x")) {
                    reg_compare = true;
                } else {
                    reg_compare = false;
                    val = parseImm(line, arg);
                }
                int jt_offset = line.indexOf("jt");
                int jf_offset = line.indexOf("jf");
                String true_label = line.substring(jt_offset + 2, jf_offset).trim();
                String false_label = line.substring(jf_offset + 2).trim();
                boolean true_label_is_fallthrough = Integer.parseInt(true_label) == label + 1;
                boolean false_label_is_fallthrough = Integer.parseInt(false_label) == label + 1;
                if (true_label_is_fallthrough && false_label_is_fallthrough)
                    break;
                switch (opcode) {
                    case "jeq":
                        if (!true_label_is_fallthrough) {
                            if (reg_compare) {
                                gen.addJumpIfR0EqualsR1(true_label);
                            } else {
                                gen.addJumpIfR0Equals(val, true_label);
                            }
                        }
                        if (!false_label_is_fallthrough) {
                            if (!true_label_is_fallthrough) {
                                gen.addJump(false_label);
                            } else if (reg_compare) {
                                gen.addJumpIfR0NotEqualsR1(false_label);
                            } else {
                                gen.addJumpIfR0NotEquals(val, false_label);
                            }
                        }
                        break;
                    case "jset":
                        if (reg_compare) {
                            gen.addJumpIfR0AnyBitsSetR1(true_label);
                        } else {
                            gen.addJumpIfR0AnyBitsSet(val, true_label);
                        }
                        if (!false_label_is_fallthrough) {
                            gen.addJump(false_label);
                        }
                        break;
                    case "jgt":
                        if (!true_label_is_fallthrough ||
                                // We have no less-than-or-equal-to register to register
                                // comparison instruction, so in this case we'll jump
                                // around an unconditional jump.
                                (!false_label_is_fallthrough && reg_compare)) {
                            if (reg_compare) {
                                gen.addJumpIfR0GreaterThanR1(true_label);
                            } else {
                                gen.addJumpIfR0GreaterThan(val, true_label);
                            }
                        }
                        if (!false_label_is_fallthrough) {
                            if (!true_label_is_fallthrough || reg_compare) {
                                gen.addJump(false_label);
                            } else {
                                gen.addJumpIfR0LessThan(val + 1, false_label);
                            }
                        }
                        break;
                    case "jge":
                        if (!false_label_is_fallthrough ||
                                // We have no greater-than-or-equal-to register to register
                                // comparison instruction, so in this case we'll jump
                                // around an unconditional jump.
                                (!true_label_is_fallthrough && reg_compare)) {
                            if (reg_compare) {
                                gen.addJumpIfR0LessThanR1(false_label);
                            } else {
                                gen.addJumpIfR0LessThan(val, false_label);
                            }
                        }
                        if (!true_label_is_fallthrough) {
                            if (!false_label_is_fallthrough || reg_compare) {
                                gen.addJump(true_label);
                            } else {
                                gen.addJumpIfR0GreaterThan(val - 1, true_label);
                            }
                        }
                        break;
                }
                break;
            case "ret":
                if (arg.equals("#0")) {
                    gen.addJump(gen.DROP_LABEL);
                } else {
                    gen.addJump(gen.PASS_LABEL);
                }
                break;
            case "tax":
                gen.addMove(Register.R1);
                break;
            case "txa":
                gen.addMove(Register.R0);
                break;
            default:
                throw new IllegalArgumentException("Unhandled instruction: " + line);
        }
    }

    /**
     * Convert the output of "tcpdump -d" (human readable BPF program dump) {@code bpf} into an APF
     * program and return it.
     */
    public static byte[] convert(String bpf) throws IllegalInstructionException {
        ApfGenerator gen = new ApfGenerator(3);
        for (String line : bpf.split("\\n")) convertLine(line, gen);
        return gen.generate();
    }

    /**
     * Convert the output of "tcpdump -d" (human readable BPF program dump) piped in stdin into an
     * APF program and output it via stdout.
     */
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line = null;
        StringBuilder responseData = new StringBuilder();
        ApfGenerator gen = new ApfGenerator(3);
        while ((line = in.readLine()) != null) convertLine(line, gen);
        System.out.write(gen.generate());
    }
}
