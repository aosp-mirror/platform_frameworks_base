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

import java.util.ArrayList;
import java.util.HashMap;

/**
 * APF assembler/generator.  A tool for generating an APF program.
 *
 * Call add*() functions to add instructions to the program, then call
 * {@link generate} to get the APF bytecode for the program.
 *
 * @hide
 */
public class ApfGenerator {
    /**
     * This exception is thrown when an attempt is made to generate an illegal instruction.
     */
    public static class IllegalInstructionException extends Exception {
        IllegalInstructionException(String msg) {
            super(msg);
        }
    }
    private enum Opcodes {
        LABEL(-1),
        LDB(1),    // Load 1 byte from immediate offset, e.g. "ldb R0, [5]"
        LDH(2),    // Load 2 bytes from immediate offset, e.g. "ldh R0, [5]"
        LDW(3),    // Load 4 bytes from immediate offset, e.g. "ldw R0, [5]"
        LDBX(4),   // Load 1 byte from immediate offset plus register, e.g. "ldbx R0, [5]R0"
        LDHX(5),   // Load 2 byte from immediate offset plus register, e.g. "ldhx R0, [5]R0"
        LDWX(6),   // Load 4 byte from immediate offset plus register, e.g. "ldwx R0, [5]R0"
        ADD(7),    // Add, e.g. "add R0,5"
        MUL(8),    // Multiply, e.g. "mul R0,5"
        DIV(9),    // Divide, e.g. "div R0,5"
        AND(10),   // And, e.g. "and R0,5"
        OR(11),    // Or, e.g. "or R0,5"
        SH(12),    // Left shift, e.g, "sh R0, 5" or "sh R0, -5" (shifts right)
        LI(13),    // Load immediate, e.g. "li R0,5" (immediate encoded as signed value)
        JMP(14),   // Jump, e.g. "jmp label"
        JEQ(15),   // Compare equal and branch, e.g. "jeq R0,5,label"
        JNE(16),   // Compare not equal and branch, e.g. "jne R0,5,label"
        JGT(17),   // Compare greater than and branch, e.g. "jgt R0,5,label"
        JLT(18),   // Compare less than and branch, e.g. "jlt R0,5,label"
        JSET(19),  // Compare any bits set and branch, e.g. "jset R0,5,label"
        JNEBS(20), // Compare not equal byte sequence, e.g. "jnebs R0,5,label,0x1122334455"
        EXT(21),   // Followed by immediate indicating ExtendedOpcodes.
        LDDW(22),  // Load 4 bytes from data memory address (register + immediate): "lddw R0, [5]R1"
        STDW(23);  // Store 4 bytes to data memory address (register + immediate): "stdw R0, [5]R1"

        final int value;

        private Opcodes(int value) {
            this.value = value;
        }
    }
    // Extended opcodes. Primary opcode is Opcodes.EXT. ExtendedOpcodes are encoded in the immediate
    // field.
    private enum ExtendedOpcodes {
        LDM(0),   // Load from memory, e.g. "ldm R0,5"
        STM(16),  // Store to memory, e.g. "stm R0,5"
        NOT(32),  // Not, e.g. "not R0"
        NEG(33),  // Negate, e.g. "neg R0"
        SWAP(34), // Swap, e.g. "swap R0,R1"
        MOVE(35);  // Move, e.g. "move R0,R1"

        final int value;

        private ExtendedOpcodes(int value) {
            this.value = value;
        }
    }
    public enum Register {
        R0(0),
        R1(1);

        final int value;

        private Register(int value) {
            this.value = value;
        }
    }
    private class Instruction {
        private final byte mOpcode;   // A "Opcode" value.
        private final byte mRegister; // A "Register" value.
        private boolean mHasImm;
        private byte mImmSize;
        private boolean mImmSigned;
        private int mImm;
        // When mOpcode is a jump:
        private byte mTargetLabelSize;
        private String mTargetLabel;
        // When mOpcode == Opcodes.LABEL:
        private String mLabel;
        // When mOpcode == Opcodes.JNEBS:
        private byte[] mCompareBytes;
        // Offset in bytes from the begining of this program. Set by {@link ApfGenerator#generate}.
        int offset;

        Instruction(Opcodes opcode, Register register) {
            mOpcode = (byte)opcode.value;
            mRegister = (byte)register.value;
        }

        Instruction(Opcodes opcode) {
            this(opcode, Register.R0);
        }

        void setImm(int imm, boolean signed) {
            mHasImm = true;
            mImm = imm;
            mImmSigned = signed;
            mImmSize = calculateImmSize(imm, signed);
        }

        void setUnsignedImm(int imm) {
            setImm(imm, false);
        }

        void setSignedImm(int imm) {
            setImm(imm, true);
        }

        void setLabel(String label) throws IllegalInstructionException {
            if (mLabels.containsKey(label)) {
                throw new IllegalInstructionException("duplicate label " + label);
            }
            if (mOpcode != Opcodes.LABEL.value) {
                throw new IllegalStateException("adding label to non-label instruction");
            }
            mLabel = label;
            mLabels.put(label, this);
        }

        void setTargetLabel(String label) {
            mTargetLabel = label;
            mTargetLabelSize = 4; // May shrink later on in generate().
        }

        void setCompareBytes(byte[] bytes) {
            if (mOpcode != Opcodes.JNEBS.value) {
                throw new IllegalStateException("adding compare bytes to non-JNEBS instruction");
            }
            mCompareBytes = bytes;
        }

        /**
         * @return size of instruction in bytes.
         */
        int size() {
            if (mOpcode == Opcodes.LABEL.value) {
                return 0;
            }
            int size = 1;
            if (mHasImm) {
                size += generatedImmSize();
            }
            if (mTargetLabel != null) {
                size += generatedImmSize();
            }
            if (mCompareBytes != null) {
                size += mCompareBytes.length;
            }
            return size;
        }

        /**
         * Resize immediate value field so that it's only as big as required to
         * contain the offset of the jump destination.
         * @return {@code true} if shrunk.
         */
        boolean shrink() throws IllegalInstructionException {
            if (mTargetLabel == null) {
                return false;
            }
            int oldSize = size();
            int oldTargetLabelSize = mTargetLabelSize;
            mTargetLabelSize = calculateImmSize(calculateTargetLabelOffset(), false);
            if (mTargetLabelSize > oldTargetLabelSize) {
                throw new IllegalStateException("instruction grew");
            }
            return size() < oldSize;
        }

        /**
         * Assemble value for instruction size field.
         */
        private byte generateImmSizeField() {
            byte immSize = generatedImmSize();
            // Encode size field to fit in 2 bits: 0->0, 1->1, 2->2, 3->4.
            return immSize == 4 ? 3 : immSize;
        }

        /**
         * Assemble first byte of generated instruction.
         */
        private byte generateInstructionByte() {
            byte sizeField = generateImmSizeField();
            return (byte)((mOpcode << 3) | (sizeField << 1) | mRegister);
        }

        /**
         * Write {@code value} at offset {@code writingOffset} into {@code bytecode}.
         * {@link generatedImmSize} bytes are written. {@code value} is truncated to
         * {@code generatedImmSize} bytes. {@code value} is treated simply as a
         * 32-bit value, so unsigned values should be zero extended and the truncation
         * should simply throw away their zero-ed upper bits, and signed values should
         * be sign extended and the truncation should simply throw away their signed
         * upper bits.
         */
        private int writeValue(int value, byte[] bytecode, int writingOffset) {
            for (int i = generatedImmSize() - 1; i >= 0; i--) {
                bytecode[writingOffset++] = (byte)((value >> (i * 8)) & 255);
            }
            return writingOffset;
        }

        /**
         * Generate bytecode for this instruction at offset {@link offset}.
         */
        void generate(byte[] bytecode) throws IllegalInstructionException {
            if (mOpcode == Opcodes.LABEL.value) {
                return;
            }
            int writingOffset = offset;
            bytecode[writingOffset++] = generateInstructionByte();
            if (mTargetLabel != null) {
                writingOffset = writeValue(calculateTargetLabelOffset(), bytecode, writingOffset);
            }
            if (mHasImm) {
                writingOffset = writeValue(mImm, bytecode, writingOffset);
            }
            if (mCompareBytes != null) {
                System.arraycopy(mCompareBytes, 0, bytecode, writingOffset, mCompareBytes.length);
                writingOffset += mCompareBytes.length;
            }
            if ((writingOffset - offset) != size()) {
                throw new IllegalStateException("wrote " + (writingOffset - offset) +
                        " but should have written " + size());
            }
        }

        /**
         * Calculate the size of either the immediate field or the target label field, if either is
         * present. Most instructions have either an immediate or a target label field, but for the
         * instructions that have both, the size of the target label field must be the same as the
         * size of the immediate field, because there is only one length field in the instruction
         * byte, hence why this function simply takes the maximum of the two sizes, so neither is
         * truncated.
         */
        private byte generatedImmSize() {
            return mImmSize > mTargetLabelSize ? mImmSize : mTargetLabelSize;
        }

        private int calculateTargetLabelOffset() throws IllegalInstructionException {
            Instruction targetLabelInstruction;
            if (mTargetLabel == DROP_LABEL) {
                targetLabelInstruction = mDropLabel;
            } else if (mTargetLabel == PASS_LABEL) {
                targetLabelInstruction = mPassLabel;
            } else {
                targetLabelInstruction = mLabels.get(mTargetLabel);
            }
            if (targetLabelInstruction == null) {
                throw new IllegalInstructionException("label not found: " + mTargetLabel);
            }
            // Calculate distance from end of this instruction to instruction.offset.
            final int targetLabelOffset = targetLabelInstruction.offset - (offset + size());
            if (targetLabelOffset < 0) {
                throw new IllegalInstructionException("backward branches disallowed; label: " +
                        mTargetLabel);
            }
            return targetLabelOffset;
        }

        private byte calculateImmSize(int imm, boolean signed) {
            if (imm == 0) {
                return 0;
            }
            if (signed && (imm >= -128 && imm <= 127) ||
                    !signed && (imm >= 0 && imm <= 255)) {
                return 1;
            }
            if (signed && (imm >= -32768 && imm <= 32767) ||
                    !signed && (imm >= 0 && imm <= 65535)) {
                return 2;
            }
            return 4;
        }
    }

    /**
     * Jump to this label to terminate the program and indicate the packet
     * should be dropped.
     */
    public static final String DROP_LABEL = "__DROP__";

    /**
     * Jump to this label to terminate the program and indicate the packet
     * should be passed to the AP.
     */
    public static final String PASS_LABEL = "__PASS__";

    /**
     * Number of memory slots available for access via APF stores to memory and loads from memory.
     * The memory slots are numbered 0 to {@code MEMORY_SLOTS} - 1. This must be kept in sync with
     * the APF interpreter.
     */
    public static final int MEMORY_SLOTS = 16;

    /**
     * Memory slot number that is prefilled with the IPv4 header length.
     * Note that this memory slot may be overwritten by a program that
     * executes stores to this memory slot. This must be kept in sync with
     * the APF interpreter.
     */
    public static final int IPV4_HEADER_SIZE_MEMORY_SLOT = 13;

    /**
     * Memory slot number that is prefilled with the size of the packet being filtered in bytes.
     * Note that this memory slot may be overwritten by a program that
     * executes stores to this memory slot. This must be kept in sync with the APF interpreter.
     */
    public static final int PACKET_SIZE_MEMORY_SLOT = 14;

    /**
     * Memory slot number that is prefilled with the age of the filter in seconds. The age of the
     * filter is the time since the filter was installed until now.
     * Note that this memory slot may be overwritten by a program that
     * executes stores to this memory slot. This must be kept in sync with the APF interpreter.
     */
    public static final int FILTER_AGE_MEMORY_SLOT = 15;

    /**
     * First memory slot containing prefilled values. Can be used in range comparisons to determine
     * if memory slot index is within prefilled slots.
     */
    public static final int FIRST_PREFILLED_MEMORY_SLOT = IPV4_HEADER_SIZE_MEMORY_SLOT;

    /**
     * Last memory slot containing prefilled values. Can be used in range comparisons to determine
     * if memory slot index is within prefilled slots.
     */
    public static final int LAST_PREFILLED_MEMORY_SLOT = FILTER_AGE_MEMORY_SLOT;

    // This version number syncs up with APF_VERSION in hardware/google/apf/apf_interpreter.h
    private static final int MIN_APF_VERSION = 2;

    private final ArrayList<Instruction> mInstructions = new ArrayList<Instruction>();
    private final HashMap<String, Instruction> mLabels = new HashMap<String, Instruction>();
    private final Instruction mDropLabel = new Instruction(Opcodes.LABEL);
    private final Instruction mPassLabel = new Instruction(Opcodes.LABEL);
    private final int mVersion;
    private boolean mGenerated;

    /**
     * Creates an ApfGenerator instance which is able to emit instructions for the specified
     * {@code version} of the APF interpreter. Throws {@code IllegalInstructionException} if
     * the requested version is unsupported.
     */
    ApfGenerator(int version) throws IllegalInstructionException {
        mVersion = version;
        requireApfVersion(MIN_APF_VERSION);
    }

    /**
     * Returns true if the ApfGenerator supports the specified {@code version}, otherwise false.
     */
    public static boolean supportsVersion(int version) {
        return version >= MIN_APF_VERSION;
    }

    private void requireApfVersion(int minimumVersion) throws IllegalInstructionException {
        if (mVersion < minimumVersion) {
            throw new IllegalInstructionException("Requires APF >= " + minimumVersion);
        }
    }

    private void addInstruction(Instruction instruction) {
        if (mGenerated) {
            throw new IllegalStateException("Program already generated");
        }
        mInstructions.add(instruction);
    }

    /**
     * Define a label at the current end of the program. Jumps can jump to this label. Labels are
     * their own separate instructions, though with size 0. This facilitates having labels with
     * no corresponding code to execute, for example a label at the end of a program. For example
     * an {@link ApfGenerator} might be passed to a function that adds a filter like so:
     * <pre>
     *   load from packet
     *   compare loaded data, jump if not equal to "next_filter"
     *   load from packet
     *   compare loaded data, jump if not equal to "next_filter"
     *   jump to drop label
     *   define "next_filter" here
     * </pre>
     * In this case "next_filter" may not have any generated code associated with it.
     */
    public ApfGenerator defineLabel(String name) throws IllegalInstructionException {
        Instruction instruction = new Instruction(Opcodes.LABEL);
        instruction.setLabel(name);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an unconditional jump instruction to the end of the program.
     */
    public ApfGenerator addJump(String target) {
        Instruction instruction = new Instruction(Opcodes.JMP);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load the byte at offset {@code offset}
     * bytes from the begining of the packet into {@code register}.
     */
    public ApfGenerator addLoad8(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDB, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load 16-bits at offset {@code offset}
     * bytes from the begining of the packet into {@code register}.
     */
    public ApfGenerator addLoad16(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDH, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load 32-bits at offset {@code offset}
     * bytes from the begining of the packet into {@code register}.
     */
    public ApfGenerator addLoad32(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDW, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load a byte from the packet into
     * {@code register}. The offset of the loaded byte from the begining of the packet is
     * the sum of {@code offset} and the value in register R1.
     */
    public ApfGenerator addLoad8Indexed(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDBX, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load 16-bits from the packet into
     * {@code register}. The offset of the loaded 16-bits from the begining of the packet is
     * the sum of {@code offset} and the value in register R1.
     */
    public ApfGenerator addLoad16Indexed(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDHX, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load 32-bits from the packet into
     * {@code register}. The offset of the loaded 32-bits from the begining of the packet is
     * the sum of {@code offset} and the value in register R1.
     */
    public ApfGenerator addLoad32Indexed(Register register, int offset) {
        Instruction instruction = new Instruction(Opcodes.LDWX, register);
        instruction.setUnsignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to add {@code value} to register R0.
     */
    public ApfGenerator addAdd(int value) {
        Instruction instruction = new Instruction(Opcodes.ADD);
        instruction.setSignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to multiply register R0 by {@code value}.
     */
    public ApfGenerator addMul(int value) {
        Instruction instruction = new Instruction(Opcodes.MUL);
        instruction.setSignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to divide register R0 by {@code value}.
     */
    public ApfGenerator addDiv(int value) {
        Instruction instruction = new Instruction(Opcodes.DIV);
        instruction.setSignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to logically and register R0 with {@code value}.
     */
    public ApfGenerator addAnd(int value) {
        Instruction instruction = new Instruction(Opcodes.AND);
        instruction.setUnsignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to logically or register R0 with {@code value}.
     */
    public ApfGenerator addOr(int value) {
        Instruction instruction = new Instruction(Opcodes.OR);
        instruction.setUnsignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to shift left register R0 by {@code value} bits.
     */
    public ApfGenerator addLeftShift(int value) {
        Instruction instruction = new Instruction(Opcodes.SH);
        instruction.setSignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to shift right register R0 by {@code value}
     * bits.
     */
    public ApfGenerator addRightShift(int value) {
        Instruction instruction = new Instruction(Opcodes.SH);
        instruction.setSignedImm(-value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to add register R1 to register R0.
     */
    public ApfGenerator addAddR1() {
        Instruction instruction = new Instruction(Opcodes.ADD, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to multiply register R0 by register R1.
     */
    public ApfGenerator addMulR1() {
        Instruction instruction = new Instruction(Opcodes.MUL, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to divide register R0 by register R1.
     */
    public ApfGenerator addDivR1() {
        Instruction instruction = new Instruction(Opcodes.DIV, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to logically and register R0 with register R1
     * and store the result back into register R0.
     */
    public ApfGenerator addAndR1() {
        Instruction instruction = new Instruction(Opcodes.AND, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to logically or register R0 with register R1
     * and store the result back into register R0.
     */
    public ApfGenerator addOrR1() {
        Instruction instruction = new Instruction(Opcodes.OR, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to shift register R0 left by the value in
     * register R1.
     */
    public ApfGenerator addLeftShiftR1() {
        Instruction instruction = new Instruction(Opcodes.SH, Register.R1);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to move {@code value} into {@code register}.
     */
    public ApfGenerator addLoadImmediate(Register register, int value) {
        Instruction instruction = new Instruction(Opcodes.LI, register);
        instruction.setSignedImm(value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value equals {@code value}.
     */
    public ApfGenerator addJumpIfR0Equals(int value, String target) {
        Instruction instruction = new Instruction(Opcodes.JEQ);
        instruction.setUnsignedImm(value);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value does not equal {@code value}.
     */
    public ApfGenerator addJumpIfR0NotEquals(int value, String target) {
        Instruction instruction = new Instruction(Opcodes.JNE);
        instruction.setUnsignedImm(value);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value is greater than {@code value}.
     */
    public ApfGenerator addJumpIfR0GreaterThan(int value, String target) {
        Instruction instruction = new Instruction(Opcodes.JGT);
        instruction.setUnsignedImm(value);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value is less than {@code value}.
     */
    public ApfGenerator addJumpIfR0LessThan(int value, String target) {
        Instruction instruction = new Instruction(Opcodes.JLT);
        instruction.setUnsignedImm(value);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value has any bits set that are also set in {@code value}.
     */
    public ApfGenerator addJumpIfR0AnyBitsSet(int value, String target) {
        Instruction instruction = new Instruction(Opcodes.JSET);
        instruction.setUnsignedImm(value);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }
    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value equals register R1's value.
     */
    public ApfGenerator addJumpIfR0EqualsR1(String target) {
        Instruction instruction = new Instruction(Opcodes.JEQ, Register.R1);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value does not equal register R1's value.
     */
    public ApfGenerator addJumpIfR0NotEqualsR1(String target) {
        Instruction instruction = new Instruction(Opcodes.JNE, Register.R1);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value is greater than register R1's value.
     */
    public ApfGenerator addJumpIfR0GreaterThanR1(String target) {
        Instruction instruction = new Instruction(Opcodes.JGT, Register.R1);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value is less than register R1's value.
     */
    public ApfGenerator addJumpIfR0LessThanR1(String target) {
        Instruction instruction = new Instruction(Opcodes.JLT, Register.R1);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if register R0's
     * value has any bits set that are also set in R1's value.
     */
    public ApfGenerator addJumpIfR0AnyBitsSetR1(String target) {
        Instruction instruction = new Instruction(Opcodes.JSET, Register.R1);
        instruction.setTargetLabel(target);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to jump to {@code target} if the bytes of the
     * packet at an offset specified by {@code register} match {@code bytes}.
     */
    public ApfGenerator addJumpIfBytesNotEqual(Register register, byte[] bytes, String target)
            throws IllegalInstructionException {
        if (register == Register.R1) {
            throw new IllegalInstructionException("JNEBS fails with R1");
        }
        Instruction instruction = new Instruction(Opcodes.JNEBS, register);
        instruction.setUnsignedImm(bytes.length);
        instruction.setTargetLabel(target);
        instruction.setCompareBytes(bytes);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load memory slot {@code slot} into
     * {@code register}.
     */
    public ApfGenerator addLoadFromMemory(Register register, int slot)
            throws IllegalInstructionException {
        if (slot < 0 || slot > (MEMORY_SLOTS - 1)) {
            throw new IllegalInstructionException("illegal memory slot number: " + slot);
        }
        Instruction instruction = new Instruction(Opcodes.EXT, register);
        instruction.setUnsignedImm(ExtendedOpcodes.LDM.value + slot);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to store {@code register} into memory slot
     * {@code slot}.
     */
    public ApfGenerator addStoreToMemory(Register register, int slot)
            throws IllegalInstructionException {
        if (slot < 0 || slot > (MEMORY_SLOTS - 1)) {
            throw new IllegalInstructionException("illegal memory slot number: " + slot);
        }
        Instruction instruction = new Instruction(Opcodes.EXT, register);
        instruction.setUnsignedImm(ExtendedOpcodes.STM.value + slot);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to logically not {@code register}.
     */
    public ApfGenerator addNot(Register register) {
        Instruction instruction = new Instruction(Opcodes.EXT, register);
        instruction.setUnsignedImm(ExtendedOpcodes.NOT.value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to negate {@code register}.
     */
    public ApfGenerator addNeg(Register register) {
        Instruction instruction = new Instruction(Opcodes.EXT, register);
        instruction.setUnsignedImm(ExtendedOpcodes.NEG.value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to swap the values in register R0 and register R1.
     */
    public ApfGenerator addSwap() {
        Instruction instruction = new Instruction(Opcodes.EXT);
        instruction.setUnsignedImm(ExtendedOpcodes.SWAP.value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to move the value into
     * {@code register} from the other register.
     */
    public ApfGenerator addMove(Register register) {
        Instruction instruction = new Instruction(Opcodes.EXT, register);
        instruction.setUnsignedImm(ExtendedOpcodes.MOVE.value);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to load 32 bits from the data memory into
     * {@code register}. The source address is computed by adding the signed immediate
     * @{code offset} to the other register.
     * Requires APF v3 or greater.
     */
    public ApfGenerator addLoadData(Register destinationRegister, int offset)
            throws IllegalInstructionException {
        requireApfVersion(3);
        Instruction instruction = new Instruction(Opcodes.LDDW, destinationRegister);
        instruction.setSignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Add an instruction to the end of the program to store 32 bits from {@code register} into the
     * data memory. The destination address is computed by adding the signed immediate
     * @{code offset} to the other register.
     * Requires APF v3 or greater.
     */
    public ApfGenerator addStoreData(Register sourceRegister, int offset)
            throws IllegalInstructionException {
        requireApfVersion(3);
        Instruction instruction = new Instruction(Opcodes.STDW, sourceRegister);
        instruction.setSignedImm(offset);
        addInstruction(instruction);
        return this;
    }

    /**
     * Updates instruction offset fields using latest instruction sizes.
     * @return current program length in bytes.
     */
    private int updateInstructionOffsets() {
        int offset = 0;
        for (Instruction instruction : mInstructions) {
            instruction.offset = offset;
            offset += instruction.size();
        }
        return offset;
    }

    /**
     * Returns an overestimate of the size of the generated program. {@link #generate} may return
     * a program that is smaller.
     */
    public int programLengthOverEstimate() {
        return updateInstructionOffsets();
    }

    /**
     * Generate the bytecode for the APF program.
     * @return the bytecode.
     * @throws IllegalStateException if a label is referenced but not defined.
     */
    public byte[] generate() throws IllegalInstructionException {
        // Enforce that we can only generate once because we cannot unshrink instructions and
        // PASS/DROP labels may move further away requiring unshrinking if we add further
        // instructions.
        if (mGenerated) {
            throw new IllegalStateException("Can only generate() once!");
        }
        mGenerated = true;
        int total_size;
        boolean shrunk;
        // Shrink the immediate value fields of instructions.
        // As we shrink the instructions some branch offset
        // fields may shrink also, thereby shrinking the
        // instructions further. Loop until we've reached the
        // minimum size. Rarely will this loop more than a few times.
        // Limit iterations to avoid O(n^2) behavior.
        int iterations_remaining = 10;
        do {
            total_size = updateInstructionOffsets();
            // Update drop and pass label offsets.
            mDropLabel.offset = total_size + 1;
            mPassLabel.offset = total_size;
            // Limit run-time in aberant circumstances.
            if (iterations_remaining-- == 0) break;
            // Attempt to shrink instructions.
            shrunk = false;
            for (Instruction instruction : mInstructions) {
                if (instruction.shrink()) {
                    shrunk = true;
                }
            }
        } while (shrunk);
        // Generate bytecode for instructions.
        byte[] bytecode = new byte[total_size];
        for (Instruction instruction : mInstructions) {
            instruction.generate(bytecode);
        }
        return bytecode;
    }
}

