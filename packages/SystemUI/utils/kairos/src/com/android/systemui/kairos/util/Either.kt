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

/**
 * Contains a value of two possibilities: `Left<A>` or `Right<B>`
 *
 * [Either] generalizes sealed classes the same way that [Pair] generalizes data classes; if a
 * [Pair] is effectively an anonymous grouping of two instances, then an [Either] is an anonymous
 * set of two options.
 */
sealed class Either<out A, out B>

/** An [Either] that contains a [Left] value. */
data class Left<out A>(val value: A) : Either<A, Nothing>()

/** An [Either] that contains a [Right] value. */
data class Right<out B>(val value: B) : Either<Nothing, B>()

/**
 * Returns an [Either] containing the result of applying [transform] to the [Left] value, or the
 * [Right] value unchanged.
 */
inline fun <A, B, C> Either<A, C>.mapLeft(transform: (A) -> B): Either<B, C> =
    when (this) {
        is Left -> Left(transform(value))
        is Right -> this
    }

/**
 * Returns an [Either] containing the result of applying [transform] to the [Right] value, or the
 * [Left] value unchanged.
 */
inline fun <A, B, C> Either<A, B>.mapRight(transform: (B) -> C): Either<A, C> =
    when (this) {
        is Left -> this
        is Right -> Right(transform(value))
    }

/** Returns a [Maybe] containing the [Left] value held by this [Either], if present. */
inline fun <A> Either<A, *>.leftMaybe(): Maybe<A> =
    when (this) {
        is Left -> just(value)
        else -> None
    }

/** Returns the [Left] value held by this [Either], or `null` if this is a [Right] value. */
inline fun <A> Either<A, *>.leftOrNull(): A? =
    when (this) {
        is Left -> value
        else -> null
    }

/** Returns a [Maybe] containing the [Right] value held by this [Either], if present. */
inline fun <B> Either<*, B>.rightMaybe(): Maybe<B> =
    when (this) {
        is Right -> just(value)
        else -> None
    }

/** Returns the [Right] value held by this [Either], or `null` if this is a [Left] value. */
inline fun <B> Either<*, B>.rightOrNull(): B? =
    when (this) {
        is Right -> value
        else -> null
    }

/**
 * Partitions this sequence of [Either] into two lists; [Pair.first] contains all [Left] values, and
 * [Pair.second] contains all [Right] values.
 */
fun <A, B> Sequence<Either<A, B>>.partitionEithers(): Pair<List<A>, List<B>> {
    val lefts = mutableListOf<A>()
    val rights = mutableListOf<B>()
    for (either in this) {
        when (either) {
            is Left -> lefts.add(either.value)
            is Right -> rights.add(either.value)
        }
    }
    return lefts to rights
}

/**
 * Partitions this map of [Either] values into two maps; [Pair.first] contains all [Left] values,
 * and [Pair.second] contains all [Right] values.
 */
fun <K, A, B> Map<K, Either<A, B>>.partitionEithers(): Pair<Map<K, A>, Map<K, B>> {
    val lefts = mutableMapOf<K, A>()
    val rights = mutableMapOf<K, B>()
    for ((k, e) in this) {
        when (e) {
            is Left -> lefts[k] = e.value
            is Right -> rights[k] = e.value
        }
    }
    return lefts to rights
}
