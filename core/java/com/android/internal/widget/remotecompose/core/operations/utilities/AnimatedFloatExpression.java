/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * high performance floating point expression evaluator used in animation
 */
public class AnimatedFloatExpression {
    static IntMap<String> sNames = new IntMap<>();
    public static final int OFFSET = 0x100;
    public static final float ADD = asNan(OFFSET + 1);
    public static final float SUB = asNan(OFFSET + 2);
    public static final float MUL = asNan(OFFSET + 3);
    public static final float DIV = asNan(OFFSET + 4);
    public static final float MOD = asNan(OFFSET + 5);
    public static final float MIN = asNan(OFFSET + 6);
    public static final float MAX = asNan(OFFSET + 7);
    public static final float POW = asNan(OFFSET + 8);
    public static final float SQRT = asNan(OFFSET + 9);
    public static final float ABS = asNan(OFFSET + 10);
    public static final float SIGN = asNan(OFFSET + 11);
    public static final float COPY_SIGN = asNan(OFFSET + 12);
    public static final float EXP = asNan(OFFSET + 13);
    public static final float FLOOR = asNan(OFFSET + 14);
    public static final float LOG = asNan(OFFSET + 15);
    public static final float LN = asNan(OFFSET + 16);
    public static final float ROUND = asNan(OFFSET + 17);
    public static final float SIN = asNan(OFFSET + 18);
    public static final float COS = asNan(OFFSET + 19);
    public static final float TAN = asNan(OFFSET + 20);
    public static final float ASIN = asNan(OFFSET + 21);
    public static final float ACOS = asNan(OFFSET + 22);

    public static final float ATAN = asNan(OFFSET + 23);

    public static final float ATAN2 = asNan(OFFSET + 24);
    public static final float MAD = asNan(OFFSET + 25);
    public static final float IFELSE = asNan(OFFSET + 26);

    public static final float CLAMP = asNan(OFFSET + 27);
    public static final float CBRT = asNan(OFFSET + 28);
    public static final float DEG = asNan(OFFSET + 29);
    public static final float RAD = asNan(OFFSET + 30);
    public static final float CEIL = asNan(OFFSET + 31);


    public static final float LAST_OP = 31;


    public static final float VAR1 = asNan(OFFSET + 27);
    public static final float VAR2 = asNan(OFFSET + 28);

    // TODO CLAMP, CBRT, DEG, RAD, EXPM1, CEIL, FLOOR
    private static final float FP_PI = (float) Math.PI;
    private static final float FP_TO_RAD = 57.29577951f; // 180/PI
    private static final float FP_TO_DEG = 0.01745329252f; // 180/PI

    float[] mStack;
    float[] mLocalStack = new float[128];
    float[] mVar;

    /**
     * is float a math operator
     * @param v
     * @return
     */
    public static boolean isMathOperator(float v) {
        if (Float.isNaN(v)) {
            int pos = fromNaN(v);
            return pos > OFFSET && pos <= OFFSET + LAST_OP;
        }
        return false;
    }

    interface Op {
        int eval(int sp);
    }

    /**
     * Evaluate a float expression
     * @param exp
     * @param var
     * @return
     */
    public float eval(float[] exp, float... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < mStack.length; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                sp = mOps[fromNaN(v) - OFFSET].eval(sp);
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a float expression
     * @param exp
     * @param len
     * @param var
     * @return
     */
    public float eval(float[] exp, int len, float... var) {
        System.arraycopy(exp, 0, mLocalStack, 0, len);
        mStack = mLocalStack;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < len; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                sp = mOps[fromNaN(v) - OFFSET].eval(sp);
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a float expression
     * @param exp
     * @param var
     * @return
     */
    public float evalDB(float[] exp, float... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (float v : exp) {
            if (Float.isNaN(v)) {
                System.out.print(" " + sNames.get((fromNaN(v) - OFFSET)));
                sp = mOps[fromNaN(v) - OFFSET].eval(sp);
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
            (sp) -> {  // DIV
                mStack[sp - 1] = mStack[sp - 1] / mStack[sp];
                return sp - 1;
            },
            (sp) -> {  // MOD
                mStack[sp - 1] = mStack[sp - 1] % mStack[sp];
                return sp - 1;
            },
            (sp) -> { // MIN
                mStack[sp - 1] = (float) Math.min(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // MAX
                mStack[sp - 1] = (float) Math.max(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // POW
                mStack[sp - 1] = (float) Math.pow(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // SQRT
                mStack[sp] = (float) Math.sqrt(mStack[sp]);
                return sp;
            },
            (sp) -> { // ABS
                mStack[sp] = (float) Math.abs(mStack[sp]);
                return sp;
            },
            (sp) -> { // SIGN
                mStack[sp] = (float) Math.signum(mStack[sp]);
                return sp;
            },
            (sp) -> { // copySign
                mStack[sp - 1] = (float) Math.copySign(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // EXP
                mStack[sp] = (float) Math.exp(mStack[sp]);
                return sp;
            },
            (sp) -> { // FLOOR
                mStack[sp] = (float) Math.floor(mStack[sp]);
                return sp;
            },
            (sp) -> { // LOG
                mStack[sp] = (float) Math.log10(mStack[sp]);
                return sp;
            },
            (sp) -> { // LN
                mStack[sp] = (float) Math.log(mStack[sp]);
                return sp;
            },
            (sp) -> { // ROUND
                mStack[sp] = (float) Math.round(mStack[sp]);
                return sp;
            },
            (sp) -> { // SIN
                mStack[sp] = (float) Math.sin(mStack[sp]);
                return sp;
            },
            (sp) -> { // COS
                mStack[sp] = (float) Math.cos(mStack[sp]);
                return sp;
            },
            (sp) -> { // TAN
                mStack[sp] = (float) Math.tan(mStack[sp]);
                return sp;
            },
            (sp) -> { // ASIN
                mStack[sp] = (float) Math.asin(mStack[sp]);
                return sp;
            },
            (sp) -> { // ACOS
                mStack[sp] = (float) Math.acos(mStack[sp]);
                return sp;
            },
            (sp) -> { // ATAN
                mStack[sp] = (float) Math.atan(mStack[sp]);
                return sp;
            },
            (sp) -> { // ATAN2
                mStack[sp - 1] = (float) Math.atan2(mStack[sp - 1], mStack[sp]);
                return sp - 1;
            },
            (sp) -> { // MAD
                mStack[sp - 2] = mStack[sp] + mStack[sp - 1] * mStack[sp - 2];
                return sp - 2;
            },
            (sp) -> { // Ternary conditional
                mStack[sp - 2] = (mStack[sp] > 0)
                        ? mStack[sp - 1] : mStack[sp - 2];
                return sp - 2;
            },
            (sp) -> { // CLAMP(min,max, val)
                mStack[sp - 2] = Math.min(Math.max(mStack[sp - 2], mStack[sp]),
                        mStack[sp - 1]);
                return sp - 2;
            },
            (sp) -> { // CBRT cuberoot
                mStack[sp] = (float) Math.pow(mStack[sp], 1 / 3.);
                return sp;
            },
            (sp) -> { // DEG
                mStack[sp] = mStack[sp] * FP_TO_RAD;
                return sp;
            },
            (sp) -> { // RAD
                mStack[sp] = mStack[sp] * FP_TO_DEG;
                return sp;
            },
            (sp) -> { // CEIL
                mStack[sp] = (float) Math.ceil(mStack[sp]);
                return sp;
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
        sNames.put(k++, "min");
        sNames.put(k++, "max");
        sNames.put(k++, "pow");
        sNames.put(k++, "sqrt");
        sNames.put(k++, "abs");
        sNames.put(k++, "sign");
        sNames.put(k++, "copySign");
        sNames.put(k++, "exp");
        sNames.put(k++, "floor");
        sNames.put(k++, "log");
        sNames.put(k++, "ln");
        sNames.put(k++, "round");
        sNames.put(k++, "sin");
        sNames.put(k++, "cos");
        sNames.put(k++, "tan");
        sNames.put(k++, "asin");
        sNames.put(k++, "acos");
        sNames.put(k++, "atan");
        sNames.put(k++, "atan2");
        sNames.put(k++, "mad");
        sNames.put(k++, "ifElse");
        sNames.put(k++, "clamp");
        sNames.put(k++, "cbrt");
        sNames.put(k++, "deg");
        sNames.put(k++, "rad");
        sNames.put(k++, "ceil");
        sNames.put(k++, "a[0]");
        sNames.put(k++, "a[1]");
        sNames.put(k++, "a[2]");
    }

    /**
     * given a float command return its math name (e.g sin, cos etc.)
     * @param f
     * @return
     */
    public static String toMathName(float f) {
        int id = fromNaN(f) - OFFSET;
        return sNames.get(id);
    }

    /**
     * Convert an expression encoded as an array of floats int ot a string
     * @param exp
     * @param labels
     * @return
     */
    public static String toString(float[] exp, String[] labels) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < exp.length; i++) {
            float v = exp[i];
            if (Float.isNaN(v)) {
                if (isMathOperator(v)) {
                    s.append(toMathName(v));
                } else {
                    s.append("[");
                    s.append(fromNaN(v));
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

    static String toString(float[] exp, int sp) {
        String[] str = new String[exp.length];
        if (Float.isNaN(exp[sp])) {
            int id = fromNaN(exp[sp]) - OFFSET;
            switch (NO_OF_OPS[id]) {
                case -1:
                    return "nop";
                case 1:
                    return sNames.get(id) + "(" + toString(exp, sp + 1) + ") ";
                case 2:
                    if (infix(id)) {
                        return "(" + toString(exp, sp + 1)
                                + sNames.get(id) + " "
                                + toString(exp, sp + 2) + ") ";
                    } else {
                        return sNames.get(id) + "("
                                + toString(exp, sp + 1) + ", "
                                + toString(exp, sp + 2) + ")";
                    }
                case 3:
                    if (infix(id)) {
                        return "((" + toString(exp, sp + 1) + ") ? "
                                + toString(exp, sp + 2) + ":"
                                + toString(exp, sp + 3) + ")";
                    } else {
                        return sNames.get(id)
                                + "(" + toString(exp, sp + 1)
                                + ", " + toString(exp, sp + 2)
                                + ", " + toString(exp, sp + 3) + ")";
                    }
            }
        }
        return Float.toString(exp[sp]);
    }

    static final int[] NO_OF_OPS = {
            -1, // no op
            2, 2, 2, 2, 2, // + - * / %
            2, 2, 2,  // min max, power
            1, 1, 1, 1, 1, 1, 1, 1,  //sqrt,abs,CopySign,exp,floor,log,ln
            1, 1, 1, 1, 1, 1, 1, 2,  // round,sin,cos,tan,asin,acos,atan,atan2
            3, 3, 3, 1, 1, 1, 1,
            0, 0, 0 // mad, ?:,
            // a[0],a[1],a[2]
    };

    /**
     * to be used by parser to determine if command is infix
     * @param n
     * @return
     */
    static boolean infix(int n) {
        return ((n < 6) || (n == 25) || (n == 26));
    }

    /**
     * Convert an id into a NaN object
     * @param v
     * @return
     */
    public static float asNan(int v) {
        return Float.intBitsToFloat(v | -0x800000);
    }

    /**
     * Get ID from a NaN float
     * @param v
     * @return
     */
    public static int fromNaN(float v) {
        int b = Float.floatToRawIntBits(v);
        return b & 0xFFFFF;
    }

}
