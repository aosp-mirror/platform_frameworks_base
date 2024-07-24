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

import static com.android.internal.util.function.pooled.PooledLambdaImpl.acquire;
import static com.android.internal.util.function.pooled.PooledLambdaImpl.acquireConstSupplier;

import android.os.Message;

import com.android.internal.util.function.DecConsumer;
import com.android.internal.util.function.DodecConsumer;
import com.android.internal.util.function.HeptConsumer;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.NonaConsumer;
import com.android.internal.util.function.OctConsumer;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuadPredicate;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.QuintPredicate;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.TriPredicate;
import com.android.internal.util.function.UndecConsumer;
import com.android.internal.util.function.pooled.PooledLambdaImpl.LambdaType.ReturnType;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A recyclable anonymous function.
 * Allows obtaining {@link Function}s/{@link Runnable}s/{@link Supplier}s/etc. without allocating a
 * new instance each time
 *
 * This exploits the mechanic that stateless lambdas (such as plain/non-bound method references)
 * get translated into a singleton instance, making it possible to create a recyclable container
 * ({@link PooledLambdaImpl}) holding a reference to such a singleton function, as well as
 * (possibly partial) arguments required for its invocation.
 *
 * To obtain an instance, use one of the factory methods in this class.
 *
 * You can call {@link #recycleOnUse} to make the instance automatically recycled upon invocation,
 * making if effectively <b>one-time use</b>.
 * This is often the behavior you want, as it allows to not worry about manual recycling.
 * Some notable examples: {@link android.os.Handler#post(Runnable)},
 * {@link android.app.Activity#runOnUiThread(Runnable)}, {@link android.view.View#post(Runnable)}
 *
 * For factories of functions that take further arguments, the corresponding 'missing' argument's
 * position is marked by an argument of type {@link ArgumentPlaceholder} with the type parameter
 * corresponding to missing argument's type.
 * You can fill the 'missing argument' spot with {@link #__()}
 * (which is the factory function for {@link ArgumentPlaceholder})
 *
 * NOTE: It is highly recommended to <b>only</b> use {@code ClassName::methodName}
 * (aka unbounded method references) as the 1st argument for any of the
 * factories ({@code obtain*(...)}) to avoid unwanted allocations.
 * This means <b>not</b> using:
 * <ul>
 *     <li>{@code someVar::methodName} or {@code this::methodName} as it captures the reference
 *     on the left of {@code ::}, resulting in an allocation on each evaluation of such
 *     bounded method references</li>
 *
 *     <li>A lambda expression, e.g. {@code () -> toString()} due to how easy it is to accidentally
 *     capture state from outside. In the above lambda expression for example, no variable from
 *     outer scope is explicitly mentioned, yet one is still captured due to {@code toString()}
 *     being an equivalent of {@code this.toString()}</li>
 * </ul>
 *
 * @hide
 */
@SuppressWarnings({"unchecked", "unused", "WeakerAccess"})
public interface PooledLambda {

    /**
     * Recycles this instance. No-op if already recycled.
     */
    void recycle();

    /**
     * Makes this instance automatically {@link #recycle} itself after the first call.
     *
     * @return this instance for convenience
     */
    PooledLambda recycleOnUse();


    // Factories

    /**
     * @return {@link ArgumentPlaceholder} with the inferred type parameter value
     */
    static <R> ArgumentPlaceholder<R> __() {
        return (ArgumentPlaceholder<R>) ArgumentPlaceholder.INSTANCE;
    }

    /**
     * @param typeHint the explicitly specified type of the missing argument
     * @return {@link ArgumentPlaceholder} with the specified type parameter value
     */
    static <R> ArgumentPlaceholder<R> __(Class<R> typeHint) {
        return __();
    }

    /**
     * Wraps the given value into a {@link PooledSupplier}
     *
     * @param value a value to wrap
     * @return a pooled supplier of {@code value}
     */
    static <R> PooledSupplier<R> obtainSupplier(R value) {
        PooledLambdaImpl r = acquireConstSupplier(ReturnType.OBJECT);
        r.mFunc = value;
        return r;
    }

    /**
     * Wraps the given value into a {@link PooledSupplier}
     *
     * @param value a value to wrap
     * @return a pooled supplier of {@code value}
     */
    static PooledSupplier.OfInt obtainSupplier(int value) {
        PooledLambdaImpl r = acquireConstSupplier(ReturnType.INT);
        r.mConstValue = value;
        return r;
    }

    /**
     * Wraps the given value into a {@link PooledSupplier}
     *
     * @param value a value to wrap
     * @return a pooled supplier of {@code value}
     */
    static PooledSupplier.OfLong obtainSupplier(long value) {
        PooledLambdaImpl r = acquireConstSupplier(ReturnType.LONG);
        r.mConstValue = value;
        return r;
    }

    /**
     * Wraps the given value into a {@link PooledSupplier}
     *
     * @param value a value to wrap
     * @return a pooled supplier of {@code value}
     */
    static PooledSupplier.OfDouble obtainSupplier(double value) {
        PooledLambdaImpl r = acquireConstSupplier(ReturnType.DOUBLE);
        r.mConstValue = Double.doubleToRawLongBits(value);
        return r;
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1) }
     */
    static <A> PooledRunnable obtainRunnable(
            Consumer<? super A> function,
            A arg1) {
        return acquire(PooledLambdaImpl.sPool,
                function, 1, 0, ReturnType.VOID, arg1, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(Consumer, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1) } when handled
     */
    static <A> Message obtainMessage(
            Consumer<? super A> function,
            A arg1) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 1, 0, ReturnType.VOID, arg1, null, null, null, null, null, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2) }
     */
    static <A, B> PooledRunnable obtainRunnable(
            BiConsumer<? super A, ? super B> function,
            A arg1, B arg2) {
        return acquire(PooledLambdaImpl.sPool,
                function, 2, 0, ReturnType.VOID, arg1, arg2, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link PooledPredicate} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 placeholder for a missing argument. Use {@link #__} to get one
     * @param arg2 parameter supplied to {@code function} on call
     * @return a {@link PooledPredicate}, equivalent to lambda:
     *         {@code (arg1) -> function(arg1, arg2) }
     */
    static <A, B> PooledPredicate<A> obtainPredicate(
            BiPredicate<? super A, ? super B> function,
            ArgumentPlaceholder<A> arg1, B arg2) {
        return acquire(PooledLambdaImpl.sPool,
                function, 2, 1, ReturnType.BOOLEAN, arg1, arg2, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link PooledPredicate} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 placeholder for a missing argument. Use {@link #__} to get one
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @return a {@link PooledPredicate}, equivalent to lambda:
     *         {@code (arg1) -> function(arg1, arg2, arg3) }
     */
    static <A, B, C> PooledPredicate<A> obtainPredicate(
            TriPredicate<? super A, ? super B, ? super C> function,
            ArgumentPlaceholder<A> arg1, B arg2, C arg3) {
        return acquire(PooledLambdaImpl.sPool,
                function, 3, 1, ReturnType.BOOLEAN, arg1, arg2, arg3, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link PooledPredicate} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 placeholder for a missing argument. Use {@link #__} to get one
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @return a {@link PooledPredicate}, equivalent to lambda:
     *         {@code (arg1) -> function(arg1, arg2, arg3, arg4) }
     */
    static <A, B, C, D> PooledPredicate<A> obtainPredicate(
            QuadPredicate<? super A, ? super B, ? super C, ? super D> function,
            ArgumentPlaceholder<A> arg1, B arg2, C arg3, D arg4) {
        return acquire(PooledLambdaImpl.sPool,
                function, 4, 1, ReturnType.BOOLEAN, arg1, arg2, arg3, arg4, null, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link PooledPredicate} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 placeholder for a missing argument. Use {@link #__} to get one
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @return a {@link PooledPredicate}, equivalent to lambda:
     *         {@code (arg1) -> function(arg1, arg2, arg3, arg4, arg5) }
     */
    static <A, B, C, D, E> PooledPredicate<A> obtainPredicate(
            QuintPredicate<? super A, ? super B, ? super C, ? super D, ? super E> function,
            ArgumentPlaceholder<A> arg1, B arg2, C arg3, D arg4, E arg5) {
        return acquire(PooledLambdaImpl.sPool,
                function, 5, 1, ReturnType.BOOLEAN, arg1, arg2, arg3, arg4, arg5, null, null, null,
                null, null, null, null);
    }

    /**
     * {@link PooledPredicate} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 placeholder for a missing argument. Use {@link #__} to get one
     * @return a {@link PooledPredicate}, equivalent to lambda:
     *         {@code (arg2) -> function(arg1, arg2) }
     */
    static <A, B> PooledPredicate<B> obtainPredicate(
            BiPredicate<? super A, ? super B> function,
            A arg1, ArgumentPlaceholder<B> arg2) {
        return acquire(PooledLambdaImpl.sPool,
                function, 2, 1, ReturnType.BOOLEAN, arg1, arg2, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(BiConsumer, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2) } when handled
     */
    static <A, B> Message obtainMessage(
            BiConsumer<? super A, ? super B> function,
            A arg1, B arg2) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 2, 0, ReturnType.VOID, arg1, arg2, null, null, null, null, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3) }
     */
    static <A, B, C> PooledRunnable obtainRunnable(
            TriConsumer<? super A, ? super B, ? super C> function,
            A arg1, B arg2, C arg3) {
        return acquire(PooledLambdaImpl.sPool,
                function, 3, 0, ReturnType.VOID, arg1, arg2, arg3, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(TriConsumer, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3) } when handled
     */
    static <A, B, C> Message obtainMessage(
            TriConsumer<? super A, ? super B, ? super C> function,
            A arg1, B arg2, C arg3) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 3, 0, ReturnType.VOID, arg1, arg2, arg3, null, null, null, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4) }
     */
    static <A, B, C, D> PooledRunnable obtainRunnable(
            QuadConsumer<? super A, ? super B, ? super C, ? super D> function,
            A arg1, B arg2, C arg3, D arg4) {
        return acquire(PooledLambdaImpl.sPool,
                function, 4, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuadConsumer, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4) } when handled
     */
    static <A, B, C, D> Message obtainMessage(
            QuadConsumer<? super A, ? super B, ? super C, ? super D> function,
            A arg1, B arg2, C arg3, D arg4) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 4, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, null, null, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5) }
     */
    static <A, B, C, D, E> PooledRunnable obtainRunnable(
            QuintConsumer<? super A, ? super B, ? super C, ? super D, ? super E> function,
            A arg1, B arg2, C arg3, D arg4, E arg5) {
        return acquire(PooledLambdaImpl.sPool,
                function, 5, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, null, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5) } when
     *         handled
     */
    static <A, B, C, D, E> Message obtainMessage(
            QuintConsumer<? super A, ? super B, ? super C, ? super D, ? super E> function,
            A arg1, B arg2, C arg3, D arg4, E arg5) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 5, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, null, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6) }
     */
    static <A, B, C, D, E, F> PooledRunnable obtainRunnable(
            HexConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F> function,
            A arg1, B arg2, C arg3, D arg4, E arg5, F arg6) {
        return acquire(PooledLambdaImpl.sPool,
                function, 6, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, null, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6) }
     *         when handled
     */
    static <A, B, C, D, E, F> Message obtainMessage(
            HexConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F> function,
            A arg1, B arg2, C arg3, D arg4, E arg5, F arg6) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 6, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, null, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7) }
     */
    static <A, B, C, D, E, F, G> PooledRunnable obtainRunnable(
            HeptConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G> function, A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7) {
        return acquire(PooledLambdaImpl.sPool,
                function, 7, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, null,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7) } when handled
     */
    static <A, B, C, D, E, F, G> Message obtainMessage(
            HeptConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G> function, A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 7, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, null,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8) }
     */
    static <A, B, C, D, E, F, G, H> PooledRunnable obtainRunnable(
            OctConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? super G,
                    ? super H> function, A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7,
            H arg8) {
        return acquire(PooledLambdaImpl.sPool,
                function, 8, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                null, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7, arg8) } when handled
     */
    static <A, B, C, D, E, F, G, H> Message obtainMessage(
            OctConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F, ? super G,
                    ? super H> function, A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7,
            H arg8) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 8, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                    null, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9) }
     */
    static <A, B, C, D, E, F, G, H, I> PooledRunnable obtainRunnable(
            NonaConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I> function, A arg1, B arg2, C arg3, D arg4,
            E arg5, F arg6, G arg7, H arg8, I arg9) {
        return acquire(PooledLambdaImpl.sPool,
                function, 9, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, null, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7, arg8, arg9) } when handled
     */
    static <A, B, C, D, E, F, G, H, I> Message obtainMessage(
            NonaConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I> function, A arg1, B arg2, C arg3, D arg4,
            E arg5, F arg6, G arg7, H arg8, I arg9) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 9, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                    arg9, null, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10) }
     */
    static <A, B, C, D, E, F, G, H, I, J> PooledRunnable obtainRunnable(
            DecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I, ? super J> function, A arg1, B arg2, C arg3,
            D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10) {
        return acquire(PooledLambdaImpl.sPool,
                function, 10, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, null, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7, arg8, arg9, arg10) } when handled
     */
    static <A, B, C, D, E, F, G, H, I, J> Message obtainMessage(
            DecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I, ? super J> function, A arg1, B arg2, C arg3,
            D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 10, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, null, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @param arg11 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10,
     *         arg11) }
     */
    static <A, B, C, D, E, F, G, H, I, J, K> PooledRunnable obtainRunnable(
            UndecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I, ? super J, ? super K> function, A arg1, B arg2,
            C arg3, D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10, K arg11) {
        return acquire(PooledLambdaImpl.sPool,
                function, 11, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, null);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @param arg11 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7, arg8, arg9, arg10, arg11) } when handled
     */
    static <A, B, C, D, E, F, G, H, I, J, K> Message obtainMessage(
            UndecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I, ? super J, ? super K> function, A arg1, B arg2,
            C arg3, D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10, K arg11) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 11, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, null);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }

    /**
     * {@link PooledRunnable} factory
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @param arg11 parameter supplied to {@code function} on call
     * @param arg12 parameter supplied to {@code function} on call
     * @return a {@link PooledRunnable}, equivalent to lambda:
     *         {@code () -> function(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10,
     *         arg11, arg12) }
     */
    static <A, B, C, D, E, F, G, H, I, J, K, L> PooledRunnable obtainRunnable(
            DodecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                                ? super G, ? super H, ? super I, ? super J, ? super K,
                                ? super L> function,
            A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10,
            K arg11, L arg12) {
        return acquire(PooledLambdaImpl.sPool,
                function, 12, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8,
                arg9, arg10, arg11, arg12);
    }

    /**
     * Factory of {@link Message}s that contain an
     * ({@link PooledLambda#recycleOnUse auto-recycling}) {@link PooledRunnable} as its
     * {@link Message#getCallback internal callback}.
     *
     * The callback is equivalent to one obtainable via
     * {@link #obtainRunnable(QuintConsumer, Object, Object, Object, Object, Object)}
     *
     * Note that using this method with {@link android.os.Handler#handleMessage}
     * is more efficient than the alternative of {@link android.os.Handler#post}
     * with a {@link PooledRunnable} due to the lack of 2 separate synchronization points
     * when obtaining {@link Message} and {@link PooledRunnable} from pools separately
     *
     * You may optionally set a {@link Message#what} for the message if you want to be
     * able to cancel it via {@link android.os.Handler#removeMessages}, but otherwise
     * there's no need to do so
     *
     * @param function non-capturing lambda(typically an unbounded method reference)
     *                 to be invoked on call
     * @param arg1 parameter supplied to {@code function} on call
     * @param arg2 parameter supplied to {@code function} on call
     * @param arg3 parameter supplied to {@code function} on call
     * @param arg4 parameter supplied to {@code function} on call
     * @param arg5 parameter supplied to {@code function} on call
     * @param arg6 parameter supplied to {@code function} on call
     * @param arg7 parameter supplied to {@code function} on call
     * @param arg8 parameter supplied to {@code function} on call
     * @param arg9 parameter supplied to {@code function} on call
     * @param arg10 parameter supplied to {@code function} on call
     * @param arg11 parameter supplied to {@code function} on call
     * @param arg12 parameter supplied to {@code function} on call
     * @return a {@link Message} invoking {@code function(arg1, arg2, arg3, arg4, arg5, arg6,
     * arg7, arg8, arg9, arg10, arg11) } when handled
     */
    static <A, B, C, D, E, F, G, H, I, J, K, L> Message obtainMessage(
            DodecConsumer<? super A, ? super B, ? super C, ? super D, ? super E, ? super F,
                    ? super G, ? super H, ? super I, ? super J, ? super K, ? super L> function,
            A arg1, B arg2, C arg3, D arg4, E arg5, F arg6, G arg7, H arg8, I arg9, J arg10,
            K arg11, L arg12) {
        synchronized (Message.sPoolSync) {
            PooledRunnable callback = acquire(PooledLambdaImpl.sMessageCallbacksPool,
                    function, 12, 0, ReturnType.VOID, arg1, arg2, arg3, arg4, arg5, arg6, arg7,
                    arg8, arg9, arg10, arg11, arg12);
            return Message.obtain().setCallback(callback.recycleOnUse());
        }
    }
}
