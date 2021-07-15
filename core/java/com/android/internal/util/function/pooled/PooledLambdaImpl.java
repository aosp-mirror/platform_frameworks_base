/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.util.function.pooled;

import android.annotation.Nullable;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pools;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.function.DecConsumer;
import com.android.internal.util.function.DecFunction;
import com.android.internal.util.function.DecPredicate;
import com.android.internal.util.function.DodecConsumer;
import com.android.internal.util.function.DodecFunction;
import com.android.internal.util.function.DodecPredicate;
import com.android.internal.util.function.HeptConsumer;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HeptPredicate;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.HexPredicate;
import com.android.internal.util.function.NonaConsumer;
import com.android.internal.util.function.NonaFunction;
import com.android.internal.util.function.NonaPredicate;
import com.android.internal.util.function.OctConsumer;
import com.android.internal.util.function.OctFunction;
import com.android.internal.util.function.OctPredicate;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.QuadPredicate;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.QuintFunction;
import com.android.internal.util.function.QuintPredicate;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.TriFunction;
import com.android.internal.util.function.TriPredicate;
import com.android.internal.util.function.UndecConsumer;
import com.android.internal.util.function.UndecFunction;
import com.android.internal.util.function.UndecPredicate;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * @see PooledLambda
 * @hide
 */
final class PooledLambdaImpl<R> extends OmniFunction<Object,
        Object, Object, Object, Object, Object, Object, Object, Object, Object, Object, R> {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PooledLambdaImpl";

    private static final int MAX_ARGS = 11;

    private static final int MAX_POOL_SIZE = 50;

    static class Pool extends Pools.SynchronizedPool<PooledLambdaImpl> {

        public Pool(Object lock) {
            super(MAX_POOL_SIZE, lock);
        }
    }

    static final Pool sPool = new Pool(new Object());
    static final Pool sMessageCallbacksPool = new Pool(Message.sPoolSync);

    private PooledLambdaImpl() {}

    /**
     * The function reference to be invoked
     *
     * May be the return value itself in case when an immediate result constant is provided instead
     */
    Object mFunc;

    /**
     * A primitive result value to be immediately returned on invocation instead of calling
     * {@link #mFunc}
     */
    long mConstValue;

    /**
     * Arguments for {@link #mFunc}
     */
    @Nullable Object[] mArgs = null;

    /**
     * Flag for {@link #mFlags}
     *
     * Indicates whether this instance is recycled
     */
    private static final int FLAG_RECYCLED = 1 << MAX_ARGS;

    /**
     * Flag for {@link #mFlags}
     *
     * Indicates whether this instance should be immediately recycled on invocation
     * (as requested via {@link PooledLambda#recycleOnUse()}) or not(default)
     */
    private static final int FLAG_RECYCLE_ON_USE = 1 << (MAX_ARGS + 1);

    /**
     * Flag for {@link #mFlags}
     *
     * Indicates that this instance was acquired from {@link #sMessageCallbacksPool} as opposed to
     * {@link #sPool}
     */
    private static final int FLAG_ACQUIRED_FROM_MESSAGE_CALLBACKS_POOL = 1 << (MAX_ARGS + 2);

    /** @see #mFlags */
    static final int MASK_EXPOSED_AS = LambdaType.MASK << (MAX_ARGS + 3);

    /** @see #mFlags */
    static final int MASK_FUNC_TYPE = LambdaType.MASK <<
            (MAX_ARGS + 3 + LambdaType.MASK_BIT_COUNT);

    /**
     * Bit schema:
     * AAAAAAAAAAABCDEEEEEEFFFFFF
     *
     * Where:
     * A - whether {@link #mArgs arg} at corresponding index was specified at
     * {@link #acquire creation time} (0) or {@link #invoke invocation time} (1)
     * B - {@link #FLAG_RECYCLED}
     * C - {@link #FLAG_RECYCLE_ON_USE}
     * D - {@link #FLAG_ACQUIRED_FROM_MESSAGE_CALLBACKS_POOL}
     * E - {@link LambdaType} representing the type of the lambda returned to the caller from a
     * factory method
     * F - {@link LambdaType} of {@link #mFunc} as resolved when calling a factory method
     */
    int mFlags = 0;


    @Override
    public void recycle() {
        if (DEBUG) Log.i(LOG_TAG, this + ".recycle()");
        if (!isRecycled()) doRecycle();
    }

    private void doRecycle() {
        if (DEBUG) Log.i(LOG_TAG, this + ".doRecycle()");
        Pool pool = (mFlags & FLAG_ACQUIRED_FROM_MESSAGE_CALLBACKS_POOL) != 0
                ? PooledLambdaImpl.sMessageCallbacksPool
                : PooledLambdaImpl.sPool;

        mFunc = null;
        if (mArgs != null) Arrays.fill(mArgs, null);
        mFlags = FLAG_RECYCLED;
        mConstValue = 0L;

        pool.release(this);
    }

    @Override
    R invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7,
            Object a8, Object a9, Object a10, Object a11) {
        checkNotRecycled();
        if (DEBUG) {
            Log.i(LOG_TAG, this + ".invoke("
                    + commaSeparateFirstN(
                            new Object[] { a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11 },
                            LambdaType.decodeArgCount(getFlags(MASK_EXPOSED_AS)))
                    + ")");
        }
        final boolean notUsed = fillInArg(a1) && fillInArg(a2) && fillInArg(a3) && fillInArg(a4)
                && fillInArg(a5) && fillInArg(a6) && fillInArg(a7) && fillInArg(a8)
                && fillInArg(a9) && fillInArg(a10) && fillInArg(a11);
        int argCount = LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE));
        if (argCount != LambdaType.MASK_ARG_COUNT) {
            for (int i = 0; i < argCount; i++) {
                if (mArgs[i] == ArgumentPlaceholder.INSTANCE) {
                    throw new IllegalStateException("Missing argument #" + i + " among "
                            + Arrays.toString(mArgs));
                }
            }
        }
        try {
            return doInvoke();
        } finally {
            if (isRecycleOnUse()) {
                doRecycle();
            } else if (!isRecycled()) {
                int argsSize = ArrayUtils.size(mArgs);
                for (int i = 0; i < argsSize; i++) {
                    popArg(i);
                }
            }
        }
    }

    private boolean fillInArg(Object invocationArg) {
        int argsSize = ArrayUtils.size(mArgs);
        for (int i = 0; i < argsSize; i++) {
            if (mArgs[i] == ArgumentPlaceholder.INSTANCE) {
                mArgs[i] = invocationArg;
                mFlags |= BitUtils.bitAt(i);
                return true;
            }
        }
        if (invocationArg != null && invocationArg != ArgumentPlaceholder.INSTANCE) {
            throw new IllegalStateException("No more arguments expected for provided arg "
                    + invocationArg + " among " + Arrays.toString(mArgs));
        }
        return false;
    }

    private void checkNotRecycled() {
        if (isRecycled()) throw new IllegalStateException("Instance is recycled: " + this);
    }

    @SuppressWarnings("unchecked")
    private R doInvoke() {
        final int funcType = getFlags(MASK_FUNC_TYPE);
        final int argCount = LambdaType.decodeArgCount(funcType);
        final int returnType = LambdaType.decodeReturnType(funcType);

        switch (argCount) {
            case LambdaType.MASK_ARG_COUNT: {
                switch (returnType) {
                    case LambdaType.ReturnType.INT: return (R) (Integer) getAsInt();
                    case LambdaType.ReturnType.LONG: return (R) (Long) getAsLong();
                    case LambdaType.ReturnType.DOUBLE: return (R) (Double) getAsDouble();
                    default: return (R) mFunc;
                }
            }
            case 0: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((Runnable) mFunc).run();
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN:
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((Supplier) mFunc).get();
                    }
                }
            } break;
            case 1: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((Consumer) mFunc).accept(popArg(0));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((Predicate) mFunc).test(popArg(0));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((Function) mFunc).apply(popArg(0));
                    }
                }
            } break;
            case 2: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((BiConsumer) mFunc).accept(popArg(0), popArg(1));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((BiPredicate) mFunc).test(popArg(0), popArg(1));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((BiFunction) mFunc).apply(popArg(0), popArg(1));
                    }
                }
            } break;
            case 3: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((TriConsumer) mFunc).accept(popArg(0), popArg(1), popArg(2));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((TriPredicate) mFunc).test(
                                popArg(0), popArg(1), popArg(2));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((TriFunction) mFunc).apply(popArg(0), popArg(1), popArg(2));
                    }
                }
            } break;
            case 4: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((QuadConsumer) mFunc).accept(popArg(0), popArg(1), popArg(2), popArg(3));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((QuadPredicate) mFunc).test(
                                popArg(0), popArg(1), popArg(2), popArg(3));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((QuadFunction) mFunc).apply(
                                popArg(0), popArg(1), popArg(2), popArg(3));
                    }
                }
            } break;

            case 5: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((QuintConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((QuintPredicate) mFunc).test(
                                popArg(0), popArg(1), popArg(2), popArg(3), popArg(4));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((QuintFunction) mFunc).apply(
                                popArg(0), popArg(1), popArg(2), popArg(3),  popArg(4));
                    }
                }
            } break;

            case 6: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((HexConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((HexPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3), popArg(4), popArg(5));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((HexFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5));
                    }
                }
            } break;

            case 7: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((HeptConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((HeptPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3),
                                popArg(4), popArg(5), popArg(6));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((HeptFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6));
                    }
                }
            } break;

            case 8: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((OctConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((OctPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3),
                                popArg(4), popArg(5), popArg(6), popArg(7));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((OctFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7));
                    }
                }
            } break;

            case 9: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((NonaConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((NonaPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7), popArg(8));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((NonaFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8));
                    }
                }
            } break;

            case 10: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((DecConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((DecPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7), popArg(8), popArg(9));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((DecFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9));
                    }
                }
            } break;

            case 11: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((UndecConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9), popArg(10));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((UndecPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7), popArg(8), popArg(9), popArg(10));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((UndecFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9), popArg(10));
                    }
                }
            } break;

            case 12: {
                switch (returnType) {
                    case LambdaType.ReturnType.VOID: {
                        ((DodecConsumer) mFunc).accept(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9), popArg(10), popArg(11));
                        return null;
                    }
                    case LambdaType.ReturnType.BOOLEAN: {
                        return (R) (Object) ((DodecPredicate) mFunc).test(popArg(0),
                                popArg(1), popArg(2), popArg(3), popArg(4),
                                popArg(5), popArg(6), popArg(7), popArg(8), popArg(9), popArg(10),
                                popArg(11));
                    }
                    case LambdaType.ReturnType.OBJECT: {
                        return (R) ((DodecFunction) mFunc).apply(popArg(0), popArg(1),
                                popArg(2), popArg(3), popArg(4), popArg(5),
                                popArg(6), popArg(7), popArg(8), popArg(9), popArg(10), popArg(11));
                    }
                }
            } break;
        }
        throw new IllegalStateException("Unknown function type: " + LambdaType.toString(funcType));
    }

    private boolean isConstSupplier() {
        return LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE)) == LambdaType.MASK_ARG_COUNT;
    }

    private Object popArg(int index) {
        Object result = mArgs[index];
        if (isInvocationArgAtIndex(index)) {
            mArgs[index] = ArgumentPlaceholder.INSTANCE;
            mFlags &= ~BitUtils.bitAt(index);
        }
        return result;
    }

    @Override
    public String toString() {
        if (isRecycled()) return "<recycled PooledLambda@" + hashCodeHex(this) + ">";

        StringBuilder sb = new StringBuilder();
        if (isConstSupplier()) {
            sb.append(getFuncTypeAsString()).append("(").append(doInvoke()).append(")");
        } else {
            Object func = mFunc;
            if (func instanceof PooledLambdaImpl) {
                sb.append(func);
            } else {
                sb.append(getFuncTypeAsString()).append("@").append(hashCodeHex(func));
            }
            sb.append("(");
            sb.append(commaSeparateFirstN(mArgs,
                    LambdaType.decodeArgCount(getFlags(MASK_FUNC_TYPE))));
            sb.append(")");
        }
        return sb.toString();
    }

    private String commaSeparateFirstN(@Nullable Object[] arr, int n) {
        if (arr == null) return "";
        return TextUtils.join(",", Arrays.copyOf(arr, n));
    }

    private static String hashCodeHex(Object o) {
        return Integer.toHexString(Objects.hashCode(o));
    }

    private String getFuncTypeAsString() {
        if (isRecycled()) return "<recycled>";
        if (isConstSupplier()) return "supplier";
        String name = LambdaType.toString(getFlags(MASK_EXPOSED_AS));
        if (name.endsWith("Consumer")) return "consumer";
        if (name.endsWith("Function")) return "function";
        if (name.endsWith("Predicate")) return "predicate";
        if (name.endsWith("Supplier")) return "supplier";
        if (name.endsWith("Runnable")) return "runnable";
        return name;
    }

    /**
     * Internal non-typesafe factory method for {@link PooledLambdaImpl}
     */
    static <E extends PooledLambda> E acquire(Pool pool, Object func,
            int fNumArgs, int numPlaceholders, int fReturnType, Object a, Object b, Object c,
            Object d, Object e, Object f, Object g, Object h, Object i, Object j, Object k,
            Object l) {
        PooledLambdaImpl r = acquire(pool);
        if (DEBUG) {
            Log.i(LOG_TAG,
                    "acquire(this = @" + hashCodeHex(r)
                            + ", func = " + func
                            + ", fNumArgs = " + fNumArgs
                            + ", numPlaceholders = " + numPlaceholders
                            + ", fReturnType = " + LambdaType.ReturnType.toString(fReturnType)
                            + ", a = " + a
                            + ", b = " + b
                            + ", c = " + c
                            + ", d = " + d
                            + ", e = " + e
                            + ", f = " + f
                            + ", g = " + g
                            + ", h = " + h
                            + ", i = " + i
                            + ", j = " + j
                            + ", k = " + k
                            + ", l = " + l
                            + ")");
        }
        r.mFunc = Objects.requireNonNull(func);
        r.setFlags(MASK_FUNC_TYPE, LambdaType.encode(fNumArgs, fReturnType));
        r.setFlags(MASK_EXPOSED_AS, LambdaType.encode(numPlaceholders, fReturnType));
        if (ArrayUtils.size(r.mArgs) < fNumArgs) r.mArgs = new Object[fNumArgs];
        setIfInBounds(r.mArgs, 0, a);
        setIfInBounds(r.mArgs, 1, b);
        setIfInBounds(r.mArgs, 2, c);
        setIfInBounds(r.mArgs, 3, d);
        setIfInBounds(r.mArgs, 4, e);
        setIfInBounds(r.mArgs, 5, f);
        setIfInBounds(r.mArgs, 6, g);
        setIfInBounds(r.mArgs, 7, h);
        setIfInBounds(r.mArgs, 8, i);
        setIfInBounds(r.mArgs, 9, j);
        setIfInBounds(r.mArgs, 10, k);
        setIfInBounds(r.mArgs, 11, l);
        return (E) r;
    }

    static PooledLambdaImpl acquireConstSupplier(int type) {
        PooledLambdaImpl r = acquire(PooledLambdaImpl.sPool);
        int lambdaType = LambdaType.encode(LambdaType.MASK_ARG_COUNT, type);
        r.setFlags(PooledLambdaImpl.MASK_FUNC_TYPE, lambdaType);
        r.setFlags(PooledLambdaImpl.MASK_EXPOSED_AS, lambdaType);
        return r;
    }

    static PooledLambdaImpl acquire(Pool pool) {
        PooledLambdaImpl r = pool.acquire();
        if (r == null) r = new PooledLambdaImpl();
        r.mFlags &= ~FLAG_RECYCLED;
        r.setFlags(FLAG_ACQUIRED_FROM_MESSAGE_CALLBACKS_POOL,
                pool == sMessageCallbacksPool ? 1 : 0);
        return r;
    }

    private static void setIfInBounds(Object[] array, int i, Object a) {
        if (i < ArrayUtils.size(array)) array[i] = a;
    }

    @Override
    public OmniFunction<Object, Object, Object, Object, Object, Object, Object, Object, Object,
            Object, Object, R> negate() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> OmniFunction<Object, Object, Object, Object, Object, Object, Object, Object, Object,
            Object, Object, V> andThen(Function<? super R, ? extends V> after) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double getAsDouble() {
        return Double.longBitsToDouble(mConstValue);
    }

    @Override
    public int getAsInt() {
        return (int) mConstValue;
    }

    @Override
    public long getAsLong() {
        return mConstValue;
    }

    @Override
    public OmniFunction<Object, Object, Object, Object, Object, Object, Object, Object, Object,
            Object, Object, R> recycleOnUse() {
        if (DEBUG) Log.i(LOG_TAG, this + ".recycleOnUse()");
        mFlags |= FLAG_RECYCLE_ON_USE;
        return this;
    }

    @Override
    public String getTraceName() {
        return FunctionalUtils.getLambdaName(mFunc);
    }

    private boolean isRecycled() {
        return (mFlags & FLAG_RECYCLED) != 0;
    }

    private boolean isRecycleOnUse() {
        return (mFlags & FLAG_RECYCLE_ON_USE) != 0;
    }

    private boolean isInvocationArgAtIndex(int argIndex) {
        return (mFlags & (1 << argIndex)) != 0;
    }

    int getFlags(int mask) {
        return unmask(mask, mFlags);
    }

    void setFlags(int mask, int value) {
        mFlags &= ~mask;
        mFlags |= mask(mask, value);
    }

    /**
     * 0xFF000, 0xAB -> 0xAB000
     */
    private static int mask(int mask, int value) {
        return (value << Integer.numberOfTrailingZeros(mask)) & mask;
    }

    /**
     * 0xFF000, 0xAB123 -> 0xAB
     */
    private static int unmask(int mask, int bits) {
        return (bits & mask) / (1 << Integer.numberOfTrailingZeros(mask));
    }

    /**
     * Contract for encoding a supported lambda type in {@link #MASK_BIT_COUNT} bits
     */
    static class LambdaType {
        public static final int MASK_ARG_COUNT = 0b1111;
        public static final int MASK_RETURN_TYPE = 0b1110000;
        public static final int MASK = MASK_ARG_COUNT | MASK_RETURN_TYPE;
        public static final int MASK_BIT_COUNT = 7;

        static int encode(int argCount, int returnType) {
            return mask(MASK_ARG_COUNT, argCount) | mask(MASK_RETURN_TYPE, returnType);
        }

        static int decodeArgCount(int type) {
            return type & MASK_ARG_COUNT;
        }

        static int decodeReturnType(int type) {
            return unmask(MASK_RETURN_TYPE, type);
        }

        static String toString(int type) {
            int argCount = decodeArgCount(type);
            int returnType = decodeReturnType(type);
            if (argCount == 0) {
                if (returnType == ReturnType.VOID) return "Runnable";
                if (returnType == ReturnType.OBJECT || returnType == ReturnType.BOOLEAN) {
                    return "Supplier";
                }
            }
            return argCountPrefix(argCount) + ReturnType.lambdaSuffix(returnType);
        }

        private static String argCountPrefix(int argCount) {
            switch (argCount) {
                case MASK_ARG_COUNT: return "";
                case 0: return "";
                case 1: return "";
                case 2: return "Bi";
                case 3: return "Tri";
                case 4: return "Quad";
                case 5: return "Quint";
                case 6: return "Hex";
                case 7: return "Hept";
                case 8: return "Oct";
                case 9: return "Nona";
                case 10: return "Dec";
                case 11: return "Undec";
                default: return "" + argCount + "arg";
            }
        }

        static class ReturnType {
            public static final int VOID = 1;
            public static final int BOOLEAN = 2;
            public static final int OBJECT = 3;
            public static final int INT = 4;
            public static final int LONG = 5;
            public static final int DOUBLE = 6;

            static String toString(int returnType) {
                switch (returnType) {
                    case VOID: return "VOID";
                    case BOOLEAN: return "BOOLEAN";
                    case OBJECT: return "OBJECT";
                    case INT: return "INT";
                    case LONG: return "LONG";
                    case DOUBLE: return "DOUBLE";
                    default: return "" + returnType;
                }
            }

            static String lambdaSuffix(int type) {
                return prefix(type) + suffix(type);
            }

            private static String prefix(int type) {
                switch (type) {
                    case INT: return "Int";
                    case LONG: return "Long";
                    case DOUBLE: return "Double";
                    default: return "";
                }
            }

            private static String suffix(int type) {
                switch (type) {
                    case VOID: return "Consumer";
                    case BOOLEAN: return "Predicate";
                    case OBJECT: return "Function";
                    default: return "Supplier";
                }
            }
        }
    }
}
