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

package com.android.systemui.kairos.util

import com.android.systemui.kairos.util.Maybe.Present

/** Contains at least one of two potential values. */
sealed class These<out A, out B> {
    /** A [These] that contains a [First] value. */
    class First<A, B> internal constructor(val value: A) : These<A, B>()

    /** A [These] that contains a [Second] value. */
    class Second<A, B> internal constructor(val value: B) : These<A, B>()

    /** A [These] that contains [Both] a [first] and [second] value. */
    class Both<A, B> internal constructor(val first: A, val second: B) : These<A, B>()

    companion object {
        /** Constructs a [These] containing the first possibility. */
        fun <A> first(value: A): These<A, Nothing> = First(value)

        /** Constructs a [These] containing the second possibility. */
        fun <B> second(value: B): These<Nothing, B> = Second(value)

        /** Constructs a [These] containing both possibilities. */
        fun <A, B> both(first: A, second: B): These<A, B> = Both(first, second)
    }
}

/**
 * Returns a single value from this [These]; either the single value held within, or the result of
 * applying [f] to both values.
 */
inline fun <A> These<A, A>.merge(f: (A, A) -> A): A =
    when (this) {
        is These.First -> value
        is These.Second -> value
        is These.Both -> f(first, second)
    }

/** Returns the [These.First] [value][These.First.value] present in this [These] as a [Maybe]. */
fun <A> These<A, *>.maybeFirst(): Maybe<A> =
    when (this) {
        is These.Both -> Maybe.present(first)
        is These.Second -> Maybe.absent
        is These.First -> Maybe.present(value)
    }

/**
 * Returns the [These.First] [value][These.First.value] present in this [These], or `null` if not
 * present.
 */
fun <A : Any> These<A, *>.firstOrNull(): A? =
    when (this) {
        is These.Both -> first
        is These.Second -> null
        is These.First -> value
    }

/** Returns the [These.Second] [value][These.Second.value] present in this [These] as a [Maybe]. */
fun <A> These<*, A>.maybeSecond(): Maybe<A> =
    when (this) {
        is These.Both -> Maybe.present(second)
        is These.Second -> Maybe.present(value)
        is These.First -> Maybe.absent
    }

/**
 * Returns the [These.Second] [value][These.Second.value] present in this [These], or `null` if not
 * present.
 */
fun <A : Any> These<*, A>.secondOrNull(): A? =
    when (this) {
        is These.Both -> second
        is These.Second -> value
        is These.First -> null
    }

/** Returns [These.Both] values present in this [These] as a [Maybe]. */
fun <A, B> These<A, B>.maybeBoth(): Maybe<Pair<A, B>> =
    when (this) {
        is These.Both -> Maybe.present(first to second)
        else -> Maybe.absent
    }

/** Returns a [These] containing [first] and/or [second] if they are present. */
fun <A, B> these(first: Maybe<A>, second: Maybe<B>): Maybe<These<A, B>> =
    when (first) {
        is Present ->
            Maybe.present(
                when (second) {
                    is Present -> These.both(first.value, second.value)
                    else -> These.first(first.value)
                }
            )

        else ->
            when (second) {
                is Present -> Maybe.present(These.second(second.value))
                else -> Maybe.absent
            }
    }

/**
 * Returns a [These] containing [first] and/or [second] if they are non-null, or `null` if both are
 * `null`.
 */
fun <A : Any, B : Any> theseNotNull(first: A?, second: B?): These<A, B>? =
    first?.let { second?.let { These.both(first, second) } ?: These.first(first) }
        ?: second?.let { These.second(second) }

/**
 * Returns two maps, with [Pair.first] containing all [These.First] values and [Pair.second]
 * containing all [These.Second] values.
 *
 * If the value is [These.Both], then the associated key with appear in both output maps, bound to
 * [These.Both.first] and [These.Both.second] in each respective output.
 */
fun <K, A, B> Map<K, These<A, B>>.partitionThese(): Pair<Map<K, A>, Map<K, B>> {
    val a = mutableMapOf<K, A>()
    val b = mutableMapOf<K, B>()
    for ((k, t) in this) {
        when (t) {
            is These.Both -> {
                a[k] = t.first
                b[k] = t.second
            }
            is These.Second -> {
                b[k] = t.value
            }
            is These.First -> {
                a[k] = t.value
            }
        }
    }
    return a to b
}
