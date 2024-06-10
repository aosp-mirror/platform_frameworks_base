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
 * limitations under the License
 */

package com.android.systemui.util.kotlin

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class Utils {
    companion object {
        fun <A, B, C> toTriple(a: A, bc: Pair<B, C>) = Triple(a, bc.first, bc.second)
        fun <A, B, C> toTriple(ab: Pair<A, B>, c: C) = Triple(ab.first, ab.second, c)

        fun <A, B, C, D> toQuad(a: A, b: B, c: C, d: D) = Quad(a, b, c, d)
        fun <A, B, C, D> toQuad(a: A, bcd: Triple<B, C, D>) =
            Quad(a, bcd.first, bcd.second, bcd.third)
        fun <A, B, C, D> toQuad(abc: Triple<A, B, C>, d: D) =
            Quad(abc.first, abc.second, abc.third, d)

        fun <A, B, C, D, E> toQuint(a: A, b: B, c: C, d: D, e: E) = Quint(a, b, c, d, e)
        fun <A, B, C, D, E> toQuint(a: A, bcde: Quad<B, C, D, E>) =
            Quint(a, bcde.first, bcde.second, bcde.third, bcde.fourth)

        fun <A, B, C, D, E, F> toSextuple(a: A, bcdef: Quint<B, C, D, E, F>) =
            Sextuple(a, bcdef.first, bcdef.second, bcdef.third, bcdef.fourth, bcdef.fifth)

        fun <A, B, C, D, E, F, G> toSeptuple(a: A, bcdefg: Sextuple<B, C, D, E, F, G>) =
            Septuple(
                a,
                bcdefg.first,
                bcdefg.second,
                bcdefg.third,
                bcdefg.fourth,
                bcdefg.fifth,
                bcdefg.sixth
            )

        /**
         * Samples the provided flow, performs a filter on the sampled value, then returns the
         * original value.
         */
        fun <A, B> Flow<A>.sampleFilter(b: Flow<B>, predicate: (B) -> Boolean): Flow<A> {
            return this.sample(b, ::Pair).filter { (_, b) -> predicate(b) }.map { (a, _) -> a }
        }

        /**
         * Samples the provided flows, emitting a tuple of the original flow's value as well as each
         * of the combined flows' values.
         *
         * Flow<A>.sample(Flow<B>, Flow<C>) -> (A, B, C)
         */
        fun <A, B, C> Flow<A>.sample(b: Flow<B>, c: Flow<C>): Flow<Triple<A, B, C>> {
            return this.sample(combine(b, c, ::Pair), ::toTriple)
        }

        /**
         * Samples the provided flows, emitting a tuple of the original flow's value as well as each
         * of the combined flows' values.
         *
         * Flow<A>.sample(Flow<B>, Flow<C>, Flow<D>) -> (A, B, C, D)
         */
        fun <A, B, C, D> Flow<A>.sample(
            b: Flow<B>,
            c: Flow<C>,
            d: Flow<D>
        ): Flow<Quad<A, B, C, D>> {
            return this.sample(combine(b, c, d, ::Triple), ::toQuad)
        }

        /**
         * Samples the provided flows, emitting a tuple of the original flow's value as well as each
         * of the combined flows' values.
         *
         * Flow<A>.sample(Flow<B>, Flow<C>, Flow<D>, Flow<E>) -> (A, B, C, D, E)
         */
        fun <A, B, C, D, E> Flow<A>.sample(
            b: Flow<B>,
            c: Flow<C>,
            d: Flow<D>,
            e: Flow<E>,
        ): Flow<Quint<A, B, C, D, E>> {
            return this.sample(combine(b, c, d, e, ::Quad), ::toQuint)
        }

        /**
         * Samples the provided flows, emitting a tuple of the original flow's value as well as each
         * of the combined flows' values.
         *
         * Flow<A>.sample(Flow<B>, Flow<C>, Flow<D>, Flow<E>, Flow<F>) -> (A, B, C, D, E, F)
         */
        fun <A, B, C, D, E, F> Flow<A>.sample(
            b: Flow<B>,
            c: Flow<C>,
            d: Flow<D>,
            e: Flow<E>,
            f: Flow<F>,
        ): Flow<Sextuple<A, B, C, D, E, F>> {
            return this.sample(combine(b, c, d, e, f, ::Quint), ::toSextuple)
        }

        /**
         * Samples the provided flows, emitting a tuple of the original flow's value as well as each
         * of the combined flows' values.
         *
         * Flow<A>.sample(Flow<B>, Flow<C>, Flow<D>, Flow<E>, Flow<F>, Flow<G>) -> (A, B, C, D, E,
         * F, G)
         */
        fun <A, B, C, D, E, F, G> Flow<A>.sample(
            b: Flow<B>,
            c: Flow<C>,
            d: Flow<D>,
            e: Flow<E>,
            f: Flow<F>,
            g: Flow<G>,
        ): Flow<Septuple<A, B, C, D, E, F, G>> {
            return this.sample(combine(b, c, d, e, f, g, ::Sextuple), ::toSeptuple)
        }
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class Quint<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

data class Sextuple<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
)

data class Septuple<A, B, C, D, E, F, G>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
    val seventh: G,
)

fun Int.toPx(context: Context): Int {
    return (this * context.resources.displayMetrics.density).toInt()
}

fun Int.toDp(context: Context): Int {
    return (this / context.resources.displayMetrics.density).toInt()
}
