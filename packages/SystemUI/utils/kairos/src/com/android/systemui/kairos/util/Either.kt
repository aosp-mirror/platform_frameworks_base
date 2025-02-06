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

@file:Suppress("NOTHING_TO_INLINE")

package com.android.systemui.kairos.util

import com.android.systemui.kairos.util.Either.First
import com.android.systemui.kairos.util.Either.Second

/**
 * Contains a value of two possibilities: `First<A>` or `Second<B>`
 *
 * [Either] generalizes sealed classes the same way that [Pair] generalizes data classes; if a
 * [Pair] is effectively an anonymous grouping of two instances, then an [Either] is an anonymous
 * set of two options.
 */
sealed interface Either<out A, out B> {
    /** An [Either] that contains a [First] value. */
    @JvmInline value class First<out A>(val value: A) : Either<A, Nothing>

    /** An [Either] that contains a [Second] value. */
    @JvmInline value class Second<out B>(val value: B) : Either<Nothing, B>

    companion object {
        /** Constructs an [Either] containing the first possibility. */
        fun <A> first(value: A): Either<A, Nothing> = First(value)

        /** Constructs a [Either] containing the second possibility. */
        fun <B> second(value: B): Either<Nothing, B> = Second(value)
    }
}

/**
 * Returns an [Either] containing the result of applying [transform] to the [First] value, or the
 * [Second] value unchanged.
 */
inline fun <A, B, C> Either<A, C>.mapFirst(transform: (A) -> B): Either<B, C> =
    when (this) {
        is First -> First(transform(value))
        is Second -> this
    }

/**
 * Returns an [Either] containing the result of applying [transform] to the [Second] value, or the
 * [First] value unchanged.
 */
inline fun <A, B, C> Either<A, B>.mapSecond(transform: (B) -> C): Either<A, C> =
    when (this) {
        is First -> this
        is Second -> Second(transform(value))
    }

/** Returns a [Maybe] containing the [First] value held by this [Either], if present. */
inline fun <A> Either<A, *>.firstMaybe(): Maybe<A> =
    when (this) {
        is First -> Maybe.present(value)
        else -> Maybe.absent
    }

/** Returns the [First] value held by this [Either], or `null` if this is a [Second] value. */
inline fun <A> Either<A, *>.firstOrNull(): A? =
    when (this) {
        is First -> value
        else -> null
    }

/** Returns a [Maybe] containing the [Second] value held by this [Either], if present. */
inline fun <B> Either<*, B>.secondMaybe(): Maybe<B> =
    when (this) {
        is Second -> Maybe.present(value)
        else -> Maybe.absent
    }

/** Returns the [Second] value held by this [Either], or `null` if this is a [First] value. */
inline fun <B> Either<*, B>.secondOrNull(): B? =
    when (this) {
        is Second -> value
        else -> null
    }

/**
 * Returns a [These] containing either the [First] value as [These.first], or the [Second] value as
 * [These.second]. Will never return a [These.both].
 */
fun <A, B> Either<A, B>.asThese(): These<A, B> =
    when (this) {
        is Second -> These.second(value)
        is First -> These.first(value)
    }

/**
 * Partitions this sequence of [Either] into two lists; [Pair.first] contains all [First] values,
 * and [Pair.second] contains all [Second] values.
 */
fun <A, B> Sequence<Either<A, B>>.partitionEithers(): Pair<List<A>, List<B>> {
    val firsts = mutableListOf<A>()
    val seconds = mutableListOf<B>()
    for (either in this) {
        when (either) {
            is First -> firsts.add(either.value)
            is Second -> seconds.add(either.value)
        }
    }
    return firsts to seconds
}

/**
 * Partitions this map of [Either] values into two maps; [Pair.first] contains all [First] values,
 * and [Pair.second] contains all [Second] values.
 */
fun <K, A, B> Map<K, Either<A, B>>.partitionEithers(): Pair<Map<K, A>, Map<K, B>> {
    val firsts = mutableMapOf<K, A>()
    val seconds = mutableMapOf<K, B>()
    for ((k, e) in this) {
        when (e) {
            is First -> firsts[k] = e.value
            is Second -> seconds[k] = e.value
        }
    }
    return firsts to seconds
}
