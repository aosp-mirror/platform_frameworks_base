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

import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.FunctionalUtils.ThrowingSupplier;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.QuadFunction;
import com.android.internal.util.function.QuintConsumer;
import com.android.internal.util.function.QuintFunction;
import com.android.internal.util.function.TriConsumer;
import com.android.internal.util.function.TriFunction;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * An interface implementing all supported function interfaces, delegating each to {@link #invoke}
 *
 * @hide
 */
abstract class OmniFunction<A, B, C, D, E, F, R> implements
        PooledFunction<A, R>, BiFunction<A, B, R>, TriFunction<A, B, C, R>,
        QuadFunction<A, B, C, D, R>, QuintFunction<A, B, C, D, E, R>,
        HexFunction<A, B, C, D, E, F, R>, PooledConsumer<A>, BiConsumer<A, B>,
        TriConsumer<A, B, C>, QuadConsumer<A, B, C, D>, QuintConsumer<A, B, C, D, E>,
        HexConsumer<A, B, C, D, E, F>, PooledPredicate<A>, BiPredicate<A, B>,
        PooledSupplier<R>, PooledRunnable, ThrowingRunnable, ThrowingSupplier<R>,
        PooledSupplier.OfInt, PooledSupplier.OfLong, PooledSupplier.OfDouble {

    abstract R invoke(A a, B b, C c, D d, E e, F f);

    @Override
    public R apply(A o, B o2) {
        return invoke(o, o2, null, null, null, null);
    }

    @Override
    public R apply(A o) {
        return invoke(o, null, null, null, null, null);
    }

    abstract public <V> OmniFunction<A, B, C, D, E, F, V> andThen(
            Function<? super R, ? extends V> after);
    abstract public OmniFunction<A, B, C, D, E, F, R> negate();

    @Override
    public void accept(A o, B o2) {
        invoke(o, o2, null, null, null, null);
    }

    @Override
    public void accept(A o) {
        invoke(o, null, null, null, null, null);
    }

    @Override
    public void run() {
        invoke(null, null, null, null, null, null);
    }

    @Override
    public R get() {
        return invoke(null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o, B o2) {
        return (Boolean) invoke(o, o2, null, null, null, null);
    }

    @Override
    public boolean test(A o) {
        return (Boolean) invoke(o, null, null, null, null, null);
    }

    @Override
    public PooledRunnable asRunnable() {
        return this;
    }

    @Override
    public PooledConsumer<A> asConsumer() {
        return this;
    }

    @Override
    public R apply(A a, B b, C c) {
        return invoke(a, b, c, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c) {
        invoke(a, b, c, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d) {
        return invoke(a, b, c, d, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e) {
        return invoke(a, b, c, d, e, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f) {
        return invoke(a, b, c, d, e, f);
    }

    @Override
    public void accept(A a, B b, C c, D d) {
        invoke(a, b, c, d, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e) {
        invoke(a, b, c, d, e, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f) {
        invoke(a, b, c, d, e, f);
    }

    @Override
    public void runOrThrow() throws Exception {
        run();
    }

    @Override
    public R getOrThrow() throws Exception {
        return get();
    }

    @Override
    abstract public OmniFunction<A, B, C, D, E, F, R> recycleOnUse();
}
