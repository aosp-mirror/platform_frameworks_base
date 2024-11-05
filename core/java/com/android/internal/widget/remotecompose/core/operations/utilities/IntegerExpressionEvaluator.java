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

/**
 * High performance Integer expression evaluator
 *
 * <p>The evaluation is based on int opMask, int[]exp exp[i] is an operator if (opMask*(1 << i) !=
 * 0)
 */
public class IntegerExpressionEvaluator {
    static IntMap<String> sNames = new IntMap<>();
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

    int[] mStack;
    int[] mLocalStack = new int[128];
    int[] mVar;

    interface Op {
        int eval(int sp);
    }

    /**
     * Evaluate an integer expression
     *
     * @param mask bits that are operators
     * @param exp  rpn sequence of values and operators
     * @param var  variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int eval(int mask, int[] exp, int... var) {
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
     * @param exp  rpn sequence of values and operators
     * @param len  the number of values in the expression
     * @param var  variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int eval(int mask, int[] exp, int len, int... var) {
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
     * @param exp    rpn sequence of values and operators
     * @param var    variables if the expression is a function
     * @return return the results of evaluating the expression
     */
    public int evalDB(int opMask, int[] exp, int... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < exp.length; i++) {
            int v = mStack[i];
            if (((1 << i) & opMask) != 0) {
                System.out.print(" " + sNames.get((v - OFFSET)));
                sp = mOps[v - OFFSET].eval(sp);
            } else {
                System.out.print(" " + v);
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    Op[] mOps = {
            null,
            (sp) -> { // ADD
                mStack[sp - 1] = mStack[sp - 1] + mStack[sp];
                return sp - 1;
            },
            (sp) -> { // SUB
                mStack[sp - 1] = mStack[sp - 1] - mStack[sp];
                return sp - 1;
            },
            (sp) -> { // MUL
                mStack[sp - 1] = mStack[sp - 1] * mStack[sp];
                return sp - 1;
            },
            (sp) -> { // DIV
                mStack[sp - 1] = mStack[sp - 1] / mStack[sp];
                return sp - 1;
            },
            (sp) -> { // MOD
                mStack[sp - 1] = mStack[sp - 1] % mStack[sp];
                return sp - 1;
            },
            (sp) -> { // SHL shift left
                mStack[sp - 1] = mStack[sp - 1] << mStack[sp];
                return sp - 1;
            },
            (sp) -> { // SHR shift right
                mStack[sp - 1] = mStack[sp - 1] >> mStack[sp];
                return sp - 1;
            },
            (sp) -> { // USHR unsigned shift right
                mStack[sp - 1] = mStack[sp - 1] >>> mStack[sp];
                return sp - 1;
            },
            (sp) -> { // OR operator
                mStack[sp - 1] = mStack[sp - 1] | mStack[sp];
                return sp - 1;
            },
            (sp) -> { // AND operator
                mStack[sp - 1] = mStack[sp - 1] & mStack[sp];
                return sp - 1;
            },
            (sp) -> { // XOR xor operator
                mStack[sp - 1] = mStack[sp - 1] ^ mStack[sp];
                return sp - 1;
            },
            (sp) -> { // COPY_SIGN copy the sing of (using bit magic)
                mStack[sp - 1] = (mStack[sp - 1] ^ (mStack[sp] >> 31)) - (mStack[sp] >> 31);
                return sp - 1;
            },
            (sp) -> { // MIN
                mStack[sp - 1] = Math.min(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // MAX
                mStack[sp - 1] = Math.max(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // NEG
                mStack[sp] = -mStack[sp];
                return sp;
            },
            (sp) -> { // ABS
                mStack[sp] = Math.abs(mStack[sp]);
                return sp;
            },
            (sp) -> { // INCR increment
                mStack[sp] = mStack[sp] + 1;
                return sp;
            },
            (sp) -> { // DECR decrement
                mStack[sp] = mStack[sp] - 1;
                return sp;
            },
            (sp) -> { // NOT Bit invert
                mStack[sp] = ~mStack[sp];
                return sp;
            },
            (sp) -> { // SIGN x<0 = -1,x==0 =  0 , x>0 = 1
                mStack[sp] = (mStack[sp] >> 31) | (-mStack[sp] >>> 31);
                return sp;
            },
            (sp) -> { // CLAMP(min,max, val)
                mStack[sp - 2] = Math.min(Math.max(mStack[sp - 2], mStack[sp]), mStack[sp - 1]);
                return sp - 2;
            },
            (sp) -> { // Ternary conditional
                mStack[sp - 2] = (mStack[sp] > 0) ? mStack[sp - 1] : mStack[sp - 2];
                return sp - 2;
            },
            (sp) -> { // MAD
                mStack[sp - 2] = mStack[sp] + mStack[sp - 1] * mStack[sp - 2];
                return sp - 2;
            },
            (sp) -> { // first var =
                mStack[sp] = mVar[0];
                return sp;
            },
            (sp) -> { // second var y?
                mStack[sp] = mVar[1];
                return sp;
            },
            (sp) -> { // 3rd var z?
                mStack[sp] = mVar[2];
                return sp;
            },
    };

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
    public static String toMathName(int f) {
        int id = f - OFFSET;
        return sNames.get(id);
    }

    /**
     * Convert an expression encoded as an array of ints int to a string
     *
     * @param opMask bits that are operators
     * @param exp    rpn sequence of values and operators
     * @param labels String that represent the variable names
     * @return
     */
    public static String toString(int opMask, int[] exp, String[] labels) {
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
     * @param exp    rpn sequence of values and operators
     * @return string representation of the expression
     */
    public static String toString(int opMask, int[] exp) {
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
     * @param exp    the array of expressions
     * @return infix string
     */
    public static String toStringInfix(int opMask, int[] exp) {
        return toString(opMask, exp, exp.length - 1);
    }

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
     * @param i      the bit to check
     * @return true if the bit is 1
     */
    public static boolean isOperation(int opMask, int i) {
        return ((1 << i) & opMask) != 0;
    }
}
