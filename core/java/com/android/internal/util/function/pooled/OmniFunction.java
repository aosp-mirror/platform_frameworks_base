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
import com.android.internal.util.function.DecConsumer;
import com.android.internal.util.function.DecFunction;
import com.android.internal.util.function.HeptConsumer;
import com.android.internal.util.function.HeptFunction;
import com.android.internal.util.function.HexConsumer;
import com.android.internal.util.function.HexFunction;
import com.android.internal.util.function.NonaConsumer;
import com.android.internal.util.function.NonaFunction;
import com.android.internal.util.function.OctConsumer;
import com.android.internal.util.function.OctFunction;
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

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * An interface implementing all supported function interfaces, delegating each to {@link #invoke}
 *
 * @hide
 */
abstract class OmniFunction<A, B, C, D, E, F, G, H, I, J, K, R> implements
        BiFunction<A, B, R>, TriFunction<A, B, C, R>,
        QuadFunction<A, B, C, D, R>, QuintFunction<A, B, C, D, E, R>,
        HexFunction<A, B, C, D, E, F, R>, HeptFunction<A, B, C, D, E, F, G, R>,
        OctFunction<A, B, C, D, E, F, G, H, R>, NonaFunction<A, B, C, D, E, F, G, H, I, R>,
        DecFunction<A, B, C, D, E, F, G, H, I, J, R>,
        UndecFunction<A, B, C, D, E, F, G, H, I, J, K, R>,
        BiConsumer<A, B>, TriConsumer<A, B, C>, QuadConsumer<A, B, C, D>,
        QuintConsumer<A, B, C, D, E>, HexConsumer<A, B, C, D, E, F>,
        HeptConsumer<A, B, C, D, E, F, G>, OctConsumer<A, B, C, D, E, F, G, H>,
        NonaConsumer<A, B, C, D, E, F, G, H, I>, DecConsumer<A, B, C, D, E, F, G, H, I, J>,
        UndecConsumer<A, B, C, D, E, F, G, H, I, J, K>,
        PooledPredicate<A>, BiPredicate<A, B>, TriPredicate<A, B, C>, QuadPredicate<A, B, C, D>,
        QuintPredicate<A, B, C, D, E>, PooledSupplier<R>, PooledRunnable, ThrowingRunnable,
        ThrowingSupplier<R>, PooledSupplier.OfInt, PooledSupplier.OfLong, PooledSupplier.OfDouble {

    abstract R invoke(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k);

    @Override
    public R apply(A o, B o2) {
        return invoke(o, o2, null, null, null, null, null, null, null, null, null);
    }

    public abstract <V> OmniFunction<A, B, C, D, E, F, G, H, I, J, K, V> andThen(
            Function<? super R, ? extends V> after);
    public abstract OmniFunction<A, B, C, D, E, F, G, H, I, J, K, R> negate();

    @Override
    public void accept(A o, B o2) {
        invoke(o, o2, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public void run() {
        invoke(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public R get() {
        return invoke(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o, B o2, C o3, D o4, E o5) {
        return (Boolean) invoke(o, o2, o3, o4, o5, null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o, B o2, C o3, D o4) {
        return (Boolean) invoke(o, o2, o3, o4, null, null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o, B o2, C o3) {
        return (Boolean) invoke(o, o2, o3, null, null, null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o, B o2) {
        return (Boolean) invoke(o, o2, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public boolean test(A o) {
        return (Boolean) invoke(o, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public PooledRunnable asRunnable() {
        return this;
    }

    @Override
    public R apply(A a, B b, C c) {
        return invoke(a, b, c, null, null, null, null, null, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c) {
        invoke(a, b, c, null, null, null, null, null, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d) {
        return invoke(a, b, c, d, null, null, null, null, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e) {
        return invoke(a, b, c, d, e, null, null, null, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f) {
        return invoke(a, b, c, d, e, f, null, null, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f, G g) {
        return invoke(a, b, c, d, e, f, g, null, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f, G g, H h) {
        return invoke(a, b, c, d, e, f, g, h, null, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i) {
        return invoke(a, b, c, d, e, f, g, h, i, null, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j) {
        return invoke(a, b, c, d, e, f, g, h, i, j, null);
    }

    @Override
    public R apply(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k) {
        return invoke(a, b, c, d, e, f, g, h, i, j, k);
    }

    @Override
    public void accept(A a, B b, C c, D d) {
        invoke(a, b, c, d, null, null, null, null, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e) {
        invoke(a, b, c, d, e, null, null, null, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f) {
        invoke(a, b, c, d, e, f, null, null, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f, G g) {
        invoke(a, b, c, d, e, f, g, null, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f, G g, H h) {
        invoke(a, b, c, d, e, f, g, h, null, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f, G g, H h, I i) {
        invoke(a, b, c, d, e, f, g, h, i, null, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j) {
        invoke(a, b, c, d, e, f, g, h, i, j, null);
    }

    @Override
    public void accept(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k) {
        invoke(a, b, c, d, e, f, g, h, i, j, k);
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
    public abstract OmniFunction<A, B, C, D, E, F, G, H, I, J, K, R> recycleOnUse();
}
