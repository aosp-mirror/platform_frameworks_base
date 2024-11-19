/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * High performance Integer expression evaluator
 *
 * <p>The evaluation is based on int opMask, int[]exp exp[i] is an operator if (opMask*(1 << i) !=
 * 0)
 */
public class IntegerExpressionEvaluator {
    @NonNull static IntMap<String> sNames = new IntMap<>();
    public static final int OFFSET = 0x10000;
    // add, sub, mul,div,mod,min,max, shl, shr, ushr, OR, AND , XOR, COPY_SIGN
    public static final int I_ADD = OFFSET + 1;
    public static final int I_SUB = OFFSET + 2;
    public static final int I_MUL = OFFSET + 3;
    public static final int I_DIV = OFFSET + 4;
    public static final int I_MOD = OFFSET + 5;
    public static final int I_SHL = OFFSET + 6;
    public static final int I_SHR = OFFSET + 7;
    public static final int I_USHR = OFFSET + 8;
    public static final int I_OR = OFFSET + 9;
    public static final int I_AND = OFFSET + 10;
    public static final int I_XOR = OFFSET + 11;
    public static final int I_COPY_SIGN = OFFSET + 12;
    public static final int I_MIN = OFFSET + 13;
    public static final int I_MAX = OFFSET + 14;

    public static final int I_NEG = OFFSET + 15;
    public static final int I_ABS = OFFSET + 16;
    public static final int I_INCR = OFFSET + 17;
    public static final int I_DECR = OFFSET + 18;
    public static final int I_NOT = OFFSET + 19;
    public static final int I_SIGN = OFFSET + 20;

    public static final int I_CLAMP = OFFSET + 21;
    public static final int I_IFELSE = OFFSET + 22;
    public static final int I_MAD = OFFSET + 23;

    public static final float LAST_OP = 25;

    public static final int I_VAR1 = OFFSET + 24;
    public static final int I_VAR2 = OFFSET + 25;

    @NonNull int[] mStack = new int[0];
    @NonNull int[] mLocalStack = new int[128];
    @NonNull int[] mVar = new int[0];

    interface Op {
        int eval(int sp);
    }

    /**
     * Evaluate an integer expression
     *
     * @param mask bits that are operators
     * @param exp rpn sequence of values and operators
     * @param var variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int eval(int mask, @NonNull int[] exp, @NonNull int... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < mStack.length; i++) {
            int v = mStack[i];
            if (((1 << i) & mask) != 0) {
                sp = mOps[v - OFFSET].eval(sp);
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a integer expression
     *
     * @param mask bits that are operators
     * @param exp rpn sequence of values and operators
     * @param len the number of values in the expression
     * @param var variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int eval(int mask, @NonNull int[] exp, int len, @NonNull int... var) {
        System.arraycopy(exp, 0, mLocalStack, 0, len);
        mStack = mLocalStack;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < len; i++) {
            int v = mStack[i];
            if (((1 << i) & mask) != 0) {
                sp = mOps[v - OFFSET].eval(sp);
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a int expression
     *
     * @param opMask bits that are operators
     * @param exp rpn sequence of values and operators
     * @param var variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int evalDB(int opMask, @NonNull int[] exp, @NonNull int... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < exp.length; i++) {
            int v = mStack[i];
            if (((1 << i) & opMask) != 0) {
                sp = mOps[v - OFFSET].eval(sp);
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    @NonNull Op[] mOps;

    {
        Op mADD =
                (sp) -> { // ADD
                    mStack[sp - 1] = mStack[sp - 1] + mStack[sp];
                    return sp - 1;
                };
        Op mSUB =
                (sp) -> { // SUB
                    mStack[sp - 1] = mStack[sp - 1] - mStack[sp];
                    return sp - 1;
                };
        Op mMUL =
                (sp) -> { // MUL
                    mStack[sp - 1] = mStack[sp - 1] * mStack[sp];
                    return sp - 1;
                };
        Op mDIV =
                (sp) -> { // DIV
                    mStack[sp - 1] = mStack[sp - 1] / mStack[sp];
                    return sp - 1;
                };
        Op mMOD =
                (sp) -> { // MOD
                    mStack[sp - 1] = mStack[sp - 1] % mStack[sp];
                    return sp - 1;
                };
        Op mSHL =
                (sp) -> { // SHL
                    mStack[sp - 1] = mStack[sp - 1] << mStack[sp];
                    return sp - 1;
                };
        Op mSHR =
                (sp) -> { // SHR
                    mStack[sp - 1] = mStack[sp - 1] >> mStack[sp];
                    return sp - 1;
                };
        Op mUSHR =
                (sp) -> { // USHR
                    mStack[sp - 1] = mStack[sp - 1] >>> mStack[sp];
                    return sp - 1;
                };
        Op mOR =
                (sp) -> { // OR
                    mStack[sp - 1] = mStack[sp - 1] | mStack[sp];
                    return sp - 1;
                };
        Op mAND =
                (sp) -> { // AND
                    mStack[sp - 1] = mStack[sp - 1] & mStack[sp];
                    return sp - 1;
                };
        Op mXOR =
                (sp) -> { // XOR
                    mStack[sp - 1] = mStack[sp - 1] ^ mStack[sp];
                    return sp - 1;
                };
        Op mCOPY_SIGN =
                (sp) -> { // COPY_SIGN copy the sign via bit manipulation
                    mStack[sp - 1] = (mStack[sp - 1] ^ (mStack[sp] >> 31)) - (mStack[sp] >> 31);
                    return sp - 1;
                };
        Op mMIN =
                (sp) -> { // MIN
                    mStack[sp - 1] = Math.min(mStack[sp - 1], mStack[sp]);
                    return sp - 1;
                };
        Op mMAX =
                (sp) -> { // MAX
                    mStack[sp - 1] = Math.max(mStack[sp - 1], mStack[sp]);
                    return sp - 1;
                };
        Op mNEG =
                (sp) -> { // NEG
                    mStack[sp] = -mStack[sp];
                    return sp;
                };
        Op mABS =
                (sp) -> { // ABS
                    mStack[sp] = Math.abs(mStack[sp]);
                    return sp;
                };
        Op mINCR =
                (sp) -> { // INCR
                    mStack[sp] = mStack[sp] + 1;
                    return sp;
                };
        Op mDECR =
                (sp) -> { // DECR
                    mStack[sp] = mStack[sp] - 1;
                    return sp;
                };
        Op mNOT =
                (sp) -> { // NOT
                    mStack[sp] = ~mStack[sp];
                    return sp;
                };
        Op mSIGN =
                (sp) -> { // SIGN x<0 = -1,x==0 =  0 , x>0 = 1
                    mStack[sp] = (mStack[sp] >> 31) | (-mStack[sp] >>> 31);
                    return sp;
                };
        Op mCLAMP =
                (sp) -> { // CLAMP(min,max, val)
                    mStack[sp - 2] = Math.min(Math.max(mStack[sp - 2], mStack[sp]), mStack[sp - 1]);
                    return sp - 2;
                };
        Op mTERNARY_CONDITIONAL =
                (sp) -> { // TERNARY_CONDITIONAL
                    mStack[sp - 2] = (mStack[sp] > 0) ? mStack[sp - 1] : mStack[sp - 2];
                    return sp - 2;
                };
        Op mMAD =
                (sp) -> { // MAD
                    mStack[sp - 2] = mStack[sp] + mStack[sp - 1] * mStack[sp - 2];
                    return sp - 2;
                };
        Op mFIRST_VAR =
                (sp) -> { // FIRST_VAR
                    mStack[sp] = mVar[0];
                    return sp;
                };
        Op mSECOND_VAR =
                (sp) -> { // SECOND_VAR
                    mStack[sp] = mVar[1];
                    return sp;
                };
        Op mTHIRD_VAR =
                (sp) -> { // THIRD_VAR
                    mStack[sp] = mVar[2];
                    return sp;
                };

        Op[] ops = {
            null,
            mADD,
            mSUB,
            mMUL,
            mDIV,
            mMOD,
            mSHL,
            mSHR,
            mUSHR,
            mOR,
            mAND,
            mXOR,
            mCOPY_SIGN,
            mMIN,
            mMAX,
            mNEG,
            mABS,
            mINCR,
            mDECR,
            mNOT,
            mSIGN,
            mCLAMP,
            mTERNARY_CONDITIONAL,
            mMAD,
            mFIRST_VAR,
            mSECOND_VAR,
            mTHIRD_VAR,
        };

        mOps = ops;
    }

    static {
        int k = 0;
        sNames.put(k++, "NOP");
        sNames.put(k++, "+");
        sNames.put(k++, "-");
        sNames.put(k++, "*");
        sNames.put(k++, "/");
        sNames.put(k++, "%");
        sNames.put(k++, "<<");
        sNames.put(k++, ">>");
        sNames.put(k++, ">>>");
        sNames.put(k++, "|");
        sNames.put(k++, "&");
        sNames.put(k++, "^");
        sNames.put(k++, "copySign");
        sNames.put(k++, "min");
        sNames.put(k++, "max");
        sNames.put(k++, "neg");
        sNames.put(k++, "abs");
        sNames.put(k++, "incr");
        sNames.put(k++, "decr");
        sNames.put(k++, "not");
        sNames.put(k++, "sign");
        sNames.put(k++, "clamp");
        sNames.put(k++, "ifElse");
        sNames.put(k++, "mad");
        sNames.put(k++, "ceil");
        sNames.put(k++, "a[0]");
        sNames.put(k++, "a[1]");
        sNames.put(k++, "a[2]");
    }

    /**
     * given a int command return its math name (e.g sin, cos etc.)
     *
     * @param f the numerical value of the function + offset
     * @return the math name of the function
     */
    @Nullable
    public static String toMathName(int f) {
        int id = f - OFFSET;
        return sNames.get(id);
    }

    /**
     * Convert an expression encoded as an array of ints int to a string
     *
     * @param opMask bits that are operators
     * @param exp rpn sequence of values and operators
     * @param labels String that represent the variable names
     * @return
     */
    @NonNull
    public static String toString(int opMask, @NonNull int[] exp, @NonNull String[] labels) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < exp.length; i++) {
            int v = exp[i];

            if (((1 << i) & opMask) != 0) {
                if (v < OFFSET) {
                    s.append(toMathName(v));
                } else {
                    s.append("[");
                    s.append(v);
                    s.append("]");
                }
            } else {
                if (labels[i] != null) {
                    s.append(labels[i]);
                }
                s.append(v);
            }
            s.append(" ");
        }
        return s.toString();
    }

    /**
     * Convert an expression encoded as an array of ints int ot a string
     *
     * @param opMask bit mask of operators vs commands
     * @param exp rpn sequence of values and operators
     * @return string representation of the expression
     */
    @NonNull
    public static String toString(int opMask, @NonNull int[] exp) {
        StringBuilder s = new StringBuilder();
        s.append(Integer.toBinaryString(opMask));
        s.append(" : ");
        for (int i = 0; i < exp.length; i++) {
            int v = exp[i];

            if (((1 << i) & opMask) != 0) {
                if (v > OFFSET) {
                    s.append(" ");
                    s.append(toMathName(v));
                    s.append(" ");

                } else {
                    s.append("[");
                    s.append(v);
                    s.append("]");
                }
            }
            s.append(" " + v);
        }
        return s.toString();
    }

    /**
     * This creates an infix string expression
     *
     * @param opMask The bits that are operators
     * @param exp the array of expressions
     * @return infix string
     */
    @NonNull
    public static String toStringInfix(int opMask, @NonNull int[] exp) {
        return toString(opMask, exp, exp.length - 1);
    }

    @NonNull
    static String toString(int mask, int[] exp, int sp) {
        if (((1 << sp) & mask) != 0) {
            int id = exp[sp] - OFFSET;
            switch (NO_OF_OPS[id]) {
                case -1:
                    return "nop";
                case 1:
                    return sNames.get(id) + "(" + toString(mask, exp, sp - 1) + ") ";
                case 2:
                    if (infix(id)) {
                        return "("
                                + toString(mask, exp, sp - 2)
                                + " "
                                + sNames.get(id)
                                + " "
                                + toString(mask, exp, sp - 1)
                                + ") ";
                    } else {
                        return sNames.get(id)
                                + "("
                                + toString(mask, exp, sp - 2)
                                + ", "
                                + toString(mask, exp, sp - 1)
                                + ")";
                    }
                case 3:
                    if (infix(id)) {
                        return "(("
                                + toString(mask, exp, sp + 3)
                                + ") ? "
                                + toString(mask, exp, sp - 2)
                                + ":"
                                + toString(mask, exp, sp - 1)
                                + ")";
                    } else {
                        return sNames.get(id)
                                + "("
                                + toString(mask, exp, sp - 3)
                                + ", "
                                + toString(mask, exp, sp - 2)
                                + ", "
                                + toString(mask, exp, sp - 1)
                                + ")";
                    }
            }
        }
        return Integer.toString(exp[sp]);
    }

    static final int[] NO_OF_OPS = {
        -1, // no op
        2,
        2,
        2,
        2,
        2, // + - * / %
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2,
        2, // <<, >> , >>> , | , &, ^, min max
        1,
        1,
        1,
        1,
        1,
        1, // neg, abs, ++, -- , not , sign
        3,
        3,
        3, // clamp, ifElse, mad,
        0,
        0,
        0 // mad, ?:,
        // a[0],a[1],a[2]
    };

    /**
     * to be used by parser to determine if command is infix
     *
     * @param n the operator (minus the offset)
     * @return true if the operator is infix
     */
    static boolean infix(int n) {
        return n < 12;
    }

    /**
     * is it an id or operation
     *
     * @param opMask the bits that mark elements as an operation
     * @param i the bit to check
     * @return true if the bit is 1
     */
    public static boolean isOperation(int opMask, int i) {
        return ((1 << i) & opMask) != 0;
    }
}
