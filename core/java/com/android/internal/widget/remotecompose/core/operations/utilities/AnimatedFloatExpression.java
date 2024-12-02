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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.operations.utilities.easing.MonotonicSpline;

import java.util.Random;

/** high performance floating point expression evaluator used in animation */
public class AnimatedFloatExpression {
    @NonNull static IntMap<String> sNames = new IntMap<>();

    /** The START POINT in the float NaN space for operators */
    public static final int OFFSET = 0x310_000;

    /** ADD operator */
    public static final float ADD = asNan(OFFSET + 1);

    /** SUB operator */
    public static final float SUB = asNan(OFFSET + 2);

    /** MUL operator */
    public static final float MUL = asNan(OFFSET + 3);

    /** DIV operator */
    public static final float DIV = asNan(OFFSET + 4);

    /** MOD operator */
    public static final float MOD = asNan(OFFSET + 5);

    /** MIN operator */
    public static final float MIN = asNan(OFFSET + 6);

    /** MAX operator */
    public static final float MAX = asNan(OFFSET + 7);

    /** POW operator */
    public static final float POW = asNan(OFFSET + 8);

    /** SQRT operator */
    public static final float SQRT = asNan(OFFSET + 9);

    /** ABS operator */
    public static final float ABS = asNan(OFFSET + 10);

    /** SIGN operator */
    public static final float SIGN = asNan(OFFSET + 11);

    /** COPY_SIGN operator */
    public static final float COPY_SIGN = asNan(OFFSET + 12);

    /** EXP operator */
    public static final float EXP = asNan(OFFSET + 13);

    /** FLOOR operator */
    public static final float FLOOR = asNan(OFFSET + 14);

    /** LOG operator */
    public static final float LOG = asNan(OFFSET + 15);

    /** LN operator */
    public static final float LN = asNan(OFFSET + 16);

    /** ROUND operator */
    public static final float ROUND = asNan(OFFSET + 17);

    /** SIN operator */
    public static final float SIN = asNan(OFFSET + 18);

    /** COS operator */
    public static final float COS = asNan(OFFSET + 19);

    /** TAN operator */
    public static final float TAN = asNan(OFFSET + 20);

    /** ASIN operator */
    public static final float ASIN = asNan(OFFSET + 21);

    /** ACOS operator */
    public static final float ACOS = asNan(OFFSET + 22);

    /** ATAN operator */
    public static final float ATAN = asNan(OFFSET + 23);

    /** ATAN2 operator */
    public static final float ATAN2 = asNan(OFFSET + 24);

    /** MAD operator */
    public static final float MAD = asNan(OFFSET + 25);

    /** IFELSE operator */
    public static final float IFELSE = asNan(OFFSET + 26);

    /** CLAMP operator */
    public static final float CLAMP = asNan(OFFSET + 27);

    /** CBRT operator */
    public static final float CBRT = asNan(OFFSET + 28);

    /** DEG operator */
    public static final float DEG = asNan(OFFSET + 29);

    /** RAD operator */
    public static final float RAD = asNan(OFFSET + 30);

    /** CEIL operator */
    public static final float CEIL = asNan(OFFSET + 31);

    // Array ops
    /** A DEREF operator */
    public static final float A_DEREF = asNan(OFFSET + 32);

    /** Array MAX operator */
    public static final float A_MAX = asNan(OFFSET + 33);

    /** Array MIN operator */
    public static final float A_MIN = asNan(OFFSET + 34);

    /** A_SUM operator */
    public static final float A_SUM = asNan(OFFSET + 35);

    /** A_AVG operator */
    public static final float A_AVG = asNan(OFFSET + 36);

    /** A_LEN operator */
    public static final float A_LEN = asNan(OFFSET + 37);

    /** A_SPLINE operator */
    public static final float A_SPLINE = asNan(OFFSET + 38);

    /** RAND Random number 0..1 */
    public static final float RAND = asNan(OFFSET + 39);

    /** RAND_SEED operator */
    public static final float RAND_SEED = asNan(OFFSET + 40);

    /** LAST valid operator */
    public static final int LAST_OP = OFFSET + 40;

    /** VAR1 operator */
    public static final float VAR1 = asNan(OFFSET + 41);

    /** VAR2 operator */
    public static final float VAR2 = asNan(OFFSET + 42);

    /** VAR2 operator */
    public static final float VAR3 = asNan(OFFSET + 43);

    // TODO CLAMP, CBRT, DEG, RAD, EXPM1, CEIL, FLOOR
    //    private static final float FP_PI = (float) Math.PI;
    private static final float FP_TO_RAD = 57.29578f; // 180/PI
    private static final float FP_TO_DEG = 0.017453292f; // 180/PI

    @NonNull float[] mStack = new float[0];
    @NonNull float[] mLocalStack = new float[128];
    @NonNull float[] mVar = new float[0];
    @Nullable CollectionsAccess mCollectionsAccess;
    IntMap<MonotonicSpline> mSplineMap = new IntMap<>();
    private Random mRandom;

    private float getSplineValue(int arrayId, float pos) {
        MonotonicSpline fit = mSplineMap.get(arrayId);
        float[] f = mCollectionsAccess.getFloats(arrayId);
        if (fit != null) {
            if (fit.getArray() == f) { // the array has not changed.
                return fit.getPos(pos);
            }
        }

        fit = new MonotonicSpline(null, f);
        mSplineMap.put(arrayId, fit);
        return fit.getPos(pos);
    }

    /**
     * is float a math operator
     *
     * @param v
     * @return
     */
    public static boolean isMathOperator(float v) {
        if (Float.isNaN(v)) {
            int pos = fromNaN(v);
            // a data variable is a type of math operator for expressions
            // it dereference to a value
            if (NanMap.isDataVariable(v)) {
                return false;
            }
            return pos > OFFSET && pos <= LAST_OP;
        }
        return false;
    }

    interface Op {
        int eval(int sp);
    }

    /**
     * Evaluate a float expression This system works by processing an Array of float (float[]) in
     * reverse polish notation (rpn) Within that array some floats are commands they are encoded
     * within an NaN. After processing the array the last item on the array is returned. The system
     * supports variables allowing expressions like. sin(sqrt(x*x+y*y))/sqrt(x*x+y*y) Where x & y
     * are passe as parameters Examples: (1+2) (1, 2, ADD) adds two numbers returns 3 eval(new
     * float[]{ Var1, Var * }
     *
     * @param exp
     * @param var
     * @return
     */
    public float eval(@NonNull float[] exp, @NonNull float... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;
        for (int i = 0; i < mStack.length; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                sp = opEval(sp, fromNaN(v));
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a float expression
     *
     * @param ca Access to float array collections
     * @param exp the expressions
     * @param len the length of the expression array
     * @param var variables if the expression contains VAR tags
     * @return the value the expression evaluated to
     */
    public float eval(
            @NonNull CollectionsAccess ca, @NonNull float[] exp, int len, @NonNull float... var) {
        System.arraycopy(exp, 0, mLocalStack, 0, len);
        mStack = mLocalStack;
        mVar = var;
        mCollectionsAccess = ca;
        int sp = -1;

        for (int i = 0; i < mStack.length; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                int id = fromNaN(v);
                if ((id & NanMap.ID_REGION_MASK) != NanMap.ID_REGION_ARRAY) {
                    sp = opEval(sp, id);
                } else {
                    mStack[++sp] = v;
                }
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a float expression
     *
     * @param ca The access to float arrays
     * @param exp the expression
     * @param len the length of the expression sections
     * @return the value the expression evaluated to
     */
    public float eval(@NonNull CollectionsAccess ca, @NonNull float[] exp, int len) {
        System.arraycopy(exp, 0, mLocalStack, 0, len);
        mStack = mLocalStack;
        mCollectionsAccess = ca;
        int sp = -1;

        for (int i = 0; i < len; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                int id = fromNaN(v);
                if ((id & NanMap.ID_REGION_MASK) != NanMap.ID_REGION_ARRAY) {
                    sp = opEval(sp, id);
                } else {
                    mStack[++sp] = v;
                }
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    private int dereference(@NonNull CollectionsAccess ca, int id, int sp) {
        mStack[sp] = ca.getFloatValue(id, (int) (mStack[sp]));
        return sp;
    }

    /**
     * Evaluate a float expression
     *
     * @param exp
     * @param len
     * @param var
     * @return
     */
    public float eval(@NonNull float[] exp, int len, @NonNull float... var) {
        System.arraycopy(exp, 0, mLocalStack, 0, len);
        mStack = mLocalStack;
        mVar = var;
        int sp = -1;

        for (int i = 0; i < len; i++) {
            float v = mStack[i];
            if (Float.isNaN(v)) {
                sp = opEval(sp, fromNaN(v));
            } else {
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

    /**
     * Evaluate a float expression
     *
     * @param exp
     * @param var
     * @return
     */
    public float evalDB(@NonNull float[] exp, @NonNull float... var) {
        mStack = exp;
        mVar = var;
        int sp = -1;

        for (float v : exp) {
            if (Float.isNaN(v)) {
                sp = opEval(sp, fromNaN(v));
            } else {
                System.out.print(" " + v);
                mStack[++sp] = v;
            }
        }
        return mStack[sp];
    }

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

        sNames.put(k++, "A_DEREF");
        sNames.put(k++, "A_MAX");
        sNames.put(k++, "A_MIN");
        sNames.put(k++, "A_SUM");
        sNames.put(k++, "A_AVG");
        sNames.put(k++, "A_LEN");
        sNames.put(k++, "A_SPLINE");
        sNames.put(k++, "RAND");
        sNames.put(k++, "RAND_SEED");

        sNames.put(k++, "a[0]");
        sNames.put(k++, "a[1]");
        sNames.put(k++, "a[2]");
    }

    /**
     * given a float command return its math name (e.g sin, cos etc.)
     *
     * @param f
     * @return
     */
    @Nullable
    public static String toMathName(float f) {
        int id = fromNaN(f) - OFFSET;
        return sNames.get(id);
    }

    /**
     * Convert an expression encoded as an array of floats int ot a string
     *
     * @param exp
     * @param labels
     * @return
     */
    @NonNull
    public static String toString(@NonNull float[] exp, @Nullable String[] labels) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < exp.length; i++) {
            float v = exp[i];
            if (Float.isNaN(v)) {
                if (isMathOperator(v)) {
                    s.append(toMathName(v));
                } else {
                    int id = fromNaN(v);
                    String idString =
                            (id > NanMap.ID_REGION_ARRAY) ? ("A_" + (id & 0xFFFFF)) : "" + id;
                    s.append("[");
                    s.append(idString);
                    s.append("]");
                }
            } else {
                if (labels != null && labels[i] != null) {
                    s.append(labels[i]);
                    if (!labels[i].contains("_")) {
                        s.append(v);
                    }
                } else {
                    s.append(v);
                }
            }
            s.append(" ");
        }
        return s.toString();
    }

    static String toString(@NonNull float[] exp, int sp) {
        //        String[] str = new String[exp.length];
        if (Float.isNaN(exp[sp])) {
            int id = fromNaN(exp[sp]) - OFFSET;
            switch (NO_OF_OPS[id]) {
                case -1:
                    return "nop";
                case 1:
                    return sNames.get(id) + "(" + toString(exp, sp + 1) + ") ";
                case 2:
                    if (infix(id)) {
                        return "("
                                + toString(exp, sp + 1)
                                + sNames.get(id)
                                + " "
                                + toString(exp, sp + 2)
                                + ") ";
                    } else {
                        return sNames.get(id)
                                + "("
                                + toString(exp, sp + 1)
                                + ", "
                                + toString(exp, sp + 2)
                                + ")";
                    }
                case 3:
                    if (infix(id)) {
                        return "(("
                                + toString(exp, sp + 1)
                                + ") ? "
                                + toString(exp, sp + 2)
                                + ":"
                                + toString(exp, sp + 3)
                                + ")";
                    } else {
                        return sNames.get(id)
                                + "("
                                + toString(exp, sp + 1)
                                + ", "
                                + toString(exp, sp + 2)
                                + ", "
                                + toString(exp, sp + 3)
                                + ")";
                    }
            }
        }
        return Float.toString(exp[sp]);
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
        2, // min max, power
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        1, // sqrt,abs,CopySign,exp,floor,log,ln
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        2, // round,sin,cos,tan,asin,acos,atan,atan2
        3,
        3,
        3,
        1,
        1,
        1,
        1,
        0,
        0,
        0 // mad, ?:,
        // a[0],a[1],a[2]
    };

    /**
     * to be used by parser to determine if command is infix
     *
     * @param n
     * @return
     */
    static boolean infix(int n) {
        return ((n < 6) || (n == 25) || (n == 26));
    }

    /**
     * Convert an id into a NaN object
     *
     * @param v
     * @return
     */
    public static float asNan(int v) {
        return Float.intBitsToFloat(v | -0x800000);
    }

    /**
     * Get ID from a NaN float
     *
     * @param v
     * @return
     */
    public static int fromNaN(float v) {
        int b = Float.floatToRawIntBits(v);
        return b & 0x7FFFFF;
    }

    // ================= New approach ========
    private static final int OP_ADD = OFFSET + 1;
    private static final int OP_SUB = OFFSET + 2;
    private static final int OP_MUL = OFFSET + 3;
    private static final int OP_DIV = OFFSET + 4;
    private static final int OP_MOD = OFFSET + 5;
    private static final int OP_MIN = OFFSET + 6;
    private static final int OP_MAX = OFFSET + 7;
    private static final int OP_POW = OFFSET + 8;
    private static final int OP_SQRT = OFFSET + 9;
    private static final int OP_ABS = OFFSET + 10;
    private static final int OP_SIGN = OFFSET + 11;
    private static final int OP_COPY_SIGN = OFFSET + 12;
    private static final int OP_EXP = OFFSET + 13;
    private static final int OP_FLOOR = OFFSET + 14;
    private static final int OP_LOG = OFFSET + 15;
    private static final int OP_LN = OFFSET + 16;
    private static final int OP_ROUND = OFFSET + 17;
    private static final int OP_SIN = OFFSET + 18;
    private static final int OP_COS = OFFSET + 19;
    private static final int OP_TAN = OFFSET + 20;
    private static final int OP_ASIN = OFFSET + 21;
    private static final int OP_ACOS = OFFSET + 22;
    private static final int OP_ATAN = OFFSET + 23;
    private static final int OP_ATAN2 = OFFSET + 24;
    private static final int OP_MAD = OFFSET + 25;
    private static final int OP_TERNARY_CONDITIONAL = OFFSET + 26;
    private static final int OP_CLAMP = OFFSET + 27;
    private static final int OP_CBRT = OFFSET + 28;
    private static final int OP_DEG = OFFSET + 29;
    private static final int OP_RAD = OFFSET + 30;
    private static final int OP_CEIL = OFFSET + 31;
    private static final int OP_A_DEREF = OFFSET + 32;
    private static final int OP_A_MAX = OFFSET + 33;
    private static final int OP_A_MIN = OFFSET + 34;
    private static final int OP_A_SUM = OFFSET + 35;
    private static final int OP_A_AVG = OFFSET + 36;
    private static final int OP_A_LEN = OFFSET + 37;
    private static final int OP_A_SPLINE = OFFSET + 38;
    private static final int OP_RAND = OFFSET + 39;
    private static final int OP_RAND_SEED = OFFSET + 40;

    private static final int OP_FIRST_VAR = OFFSET + 41;
    private static final int OP_SECOND_VAR = OFFSET + 42;
    private static final int OP_THIRD_VAR = OFFSET + 43;

    int opEval(int sp, int id) {
        float[] array;

        switch (id) {
            case OP_ADD:
                mStack[sp - 1] = mStack[sp - 1] + mStack[sp];
                return sp - 1;

            case OP_SUB:
                mStack[sp - 1] = mStack[sp - 1] - mStack[sp];
                return sp - 1;

            case OP_MUL:
                mStack[sp - 1] = mStack[sp - 1] * mStack[sp];
                return sp - 1;

            case OP_DIV:
                mStack[sp - 1] = mStack[sp - 1] / mStack[sp];
                return sp - 1;

            case OP_MOD:
                mStack[sp - 1] = mStack[sp - 1] % mStack[sp];
                return sp - 1;

            case OP_MIN:
                mStack[sp - 1] = (float) Math.min(mStack[sp - 1], mStack[sp]);
                return sp - 1;

            case OP_MAX:
                mStack[sp - 1] = (float) Math.max(mStack[sp - 1], mStack[sp]);
                return sp - 1;

            case OP_POW:
                mStack[sp - 1] = (float) Math.pow(mStack[sp - 1], mStack[sp]);
                return sp - 1;

            case OP_SQRT:
                mStack[sp] = (float) Math.sqrt(mStack[sp]);
                return sp;

            case OP_ABS:
                mStack[sp] = (float) Math.abs(mStack[sp]);
                return sp;

            case OP_SIGN:
                mStack[sp] = (float) Math.signum(mStack[sp]);
                return sp;

            case OP_COPY_SIGN:
                mStack[sp - 1] = (float) Math.copySign(mStack[sp - 1], mStack[sp]);
                return sp - 1;

            case OP_EXP:
                mStack[sp] = (float) Math.exp(mStack[sp]);
                return sp;

            case OP_FLOOR:
                mStack[sp] = (float) Math.floor(mStack[sp]);
                return sp;

            case OP_LOG:
                mStack[sp] = (float) Math.log10(mStack[sp]);
                return sp;

            case OP_LN:
                mStack[sp] = (float) Math.log(mStack[sp]);
                return sp;

            case OP_ROUND:
                mStack[sp] = (float) Math.round(mStack[sp]);
                return sp;

            case OP_SIN:
                mStack[sp] = (float) Math.sin(mStack[sp]);
                return sp;

            case OP_COS:
                mStack[sp] = (float) Math.cos(mStack[sp]);
                return sp;

            case OP_TAN:
                mStack[sp] = (float) Math.tan(mStack[sp]);
                return sp;

            case OP_ASIN:
                mStack[sp] = (float) Math.asin(mStack[sp]);
                return sp;

            case OP_ACOS:
                mStack[sp] = (float) Math.acos(mStack[sp]);
                return sp;

            case OP_ATAN:
                mStack[sp] = (float) Math.atan(mStack[sp]);
                return sp;

            case OP_ATAN2:
                mStack[sp - 1] = (float) Math.atan2(mStack[sp - 1], mStack[sp]);
                return sp - 1;

            case OP_MAD:
                mStack[sp - 2] = mStack[sp] + mStack[sp - 1] * mStack[sp - 2];
                return sp - 2;

            case OP_TERNARY_CONDITIONAL:
                mStack[sp - 2] = (mStack[sp] > 0) ? mStack[sp - 1] : mStack[sp - 2];
                return sp - 2;

            case OP_CLAMP:
                mStack[sp - 2] = Math.min(Math.max(mStack[sp - 2], mStack[sp]), mStack[sp - 1]);
                return sp - 2;

            case OP_CBRT:
                mStack[sp] = (float) Math.pow(mStack[sp], 1 / 3.);
                return sp;

            case OP_DEG:
                mStack[sp] = mStack[sp] * FP_TO_RAD;
                return sp;

            case OP_RAD:
                mStack[sp] = mStack[sp] * FP_TO_DEG;
                return sp;

            case OP_CEIL:
                mStack[sp] = (float) Math.ceil(mStack[sp]);
                return sp;

            case OP_A_DEREF:
                id = fromNaN(mStack[sp - 1]);
                mStack[sp - 1] = mCollectionsAccess.getFloatValue(id, (int) mStack[sp]);
                return sp - 1;

            case OP_A_MAX:
                id = fromNaN(mStack[sp]);
                array = mCollectionsAccess.getFloats(id);
                float max = array[0];
                for (int i = 1; i < array.length; i++) {
                    max = Math.max(max, array[i]);
                }
                mStack[sp] = max;
                return sp;

            case OP_A_MIN:
                id = fromNaN(mStack[sp]);
                array = mCollectionsAccess.getFloats(id);
                if (array.length == 0) {
                    return sp;
                }
                float min = array[0];
                for (int i = 1; i < array.length; i++) {
                    min = Math.min(min, array[i]);
                }
                mStack[sp] = min;
                return sp;

            case OP_A_SUM:
                id = fromNaN(mStack[sp]);
                array = mCollectionsAccess.getFloats(id);
                float sum = 0;
                for (int i = 0; i < array.length; i++) {
                    sum += array[i];
                }
                mStack[sp] = sum;
                return sp;

            case OP_A_AVG:
                id = fromNaN(mStack[sp]);
                array = mCollectionsAccess.getFloats(id);
                sum = 0;
                for (int i = 0; i < array.length; i++) {
                    sum += array[i];
                }
                mStack[sp] = sum / array.length;
                return sp;

            case OP_A_LEN:
                id = fromNaN(mStack[sp]);
                mStack[sp] = mCollectionsAccess.getListLength(id);
                return sp;

            case OP_A_SPLINE:
                id = fromNaN(mStack[sp - 1]);
                mStack[sp - 1] = getSplineValue(id, mStack[sp]);
                return sp - 1;

            case OP_RAND:
                if (mRandom == null) {
                    mRandom = new Random();
                }
                mStack[sp + 1] = mRandom.nextFloat();
                return sp + 1;

            case OP_RAND_SEED:
                float seed = mStack[sp];
                if (seed == 0) {
                    mRandom = new Random();
                } else {
                    if (mRandom == null) {
                        mRandom = new Random(Float.floatToRawIntBits(seed));
                    } else {
                        mRandom.setSeed(Float.floatToRawIntBits(seed));
                    }
                }
                return sp - 1;

            case OP_FIRST_VAR:
                mStack[sp] = mVar[0];
                return sp;

            case OP_SECOND_VAR:
                mStack[sp] = mVar[1];
                return sp;

            case OP_THIRD_VAR:
                mStack[sp] = mVar[2];
                return sp;
        }
        return sp;
    }
}
