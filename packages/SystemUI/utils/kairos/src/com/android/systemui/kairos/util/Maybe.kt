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

@file:Suppress("NOTHING_TO_INLINE", "SuspendCoroutine")

package com.android.systemui.kairos.util

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** Represents a value that may or may not be present. */
sealed class Maybe<out A>

/** A [Maybe] value that is present. */
data class Just<out A> internal constructor(val value: A) : Maybe<A>()

/** A [Maybe] value that is not present. */
data object None : Maybe<Nothing>()

/** Utilities to query [Maybe] instances from within a [maybe] block. */
@RestrictsSuspension
object MaybeScope {
    suspend operator fun <A> Maybe<A>.not(): A = suspendCoroutine { k ->
        if (this is Just) k.resume(value)
    }

    suspend inline fun guard(crossinline block: () -> Boolean): Unit = suspendCoroutine { k ->
        if (block()) k.resume(Unit)
    }
}

/**
 * Returns a [Maybe] value produced by evaluating [block].
 *
 * [block] can use its [MaybeScope] receiver to query other [Maybe] values, automatically cancelling
 * execution of [block] and producing [None] when attempting to query a [Maybe] that is not present.
 *
 * This can be used instead of Kotlin's built-in nullability (`?.` and `?:`) operators when dealing
 * with complex combinations of nullables:
 * ``` kotlin
 * val aMaybe: Maybe<Any> = ...
 * val bMaybe: Maybe<Any> = ...
 * val result: String = maybe {
 *   val a = !aMaybe
 *   val b = !bMaybe
 *   "Got: $a and $b"
 * }
 * ```
 */
fun <A> maybe(block: suspend MaybeScope.() -> A): Maybe<A> {
    var maybeResult: Maybe<A> = None
    val k =
        object : Continuation<A> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<A>) {
                maybeResult = result.getOrNull()?.let { just(it) } ?: None
            }
        }
    block.startCoroutine(MaybeScope, k)
    return maybeResult
}

/** Returns a [Just] containing this value, or [None] if `null`. */
inline fun <A> (A?).toMaybe(): Maybe<A> = maybe(this)

/** Returns a [Just] containing a non-null [value], or [None] if `null`. */
inline fun <A> maybe(value: A?): Maybe<A> = value?.let(::just) ?: None

/** Returns a [Just] containing [value]. */
fun <A> just(value: A): Maybe<A> = Just(value)

/** A [Maybe] that is not present. */
val none: Maybe<Nothing> = None

/** A [Maybe] that is not present. */
inline fun <A> none(): Maybe<A> = None

/** Returns the value present in this [Maybe], or `null` if not present. */
inline fun <A> Maybe<A>.orNull(): A? = orElse(null)

/**
 * Returns a [Maybe] holding the result of applying [transform] to the value in the original
 * [Maybe].
 */
inline fun <A, B> Maybe<A>.map(transform: (A) -> B): Maybe<B> =
    when (this) {
        is Just -> just(transform(value))
        is None -> None
    }

/** Returns the result of applying [transform] to the value in the original [Maybe]. */
inline fun <A, B> Maybe<A>.flatMap(transform: (A) -> Maybe<B>): Maybe<B> =
    when (this) {
        is Just -> transform(value)
        is None -> None
    }

/** Returns the value present in this [Maybe], or the result of [defaultValue] if not present. */
inline fun <A> Maybe<A>.orElseGet(defaultValue: () -> A): A =
    when (this) {
        is Just -> value
        is None -> defaultValue()
    }

/**
 * Returns the value present in this [Maybe], or invokes [error] with the message returned from
 * [getMessage].
 */
inline fun <A> Maybe<A>.orError(getMessage: () -> Any): A = orElseGet { error(getMessage()) }

/** Returns the value present in this [Maybe], or [defaultValue] if not present. */
inline fun <A> Maybe<A>.orElse(defaultValue: A): A =
    when (this) {
        is Just -> value
        is None -> defaultValue
    }

/**
 * Returns a [Maybe] that contains the present in the original [Maybe], only if it satisfies
 * [predicate].
 */
inline fun <A> Maybe<A>.filter(predicate: (A) -> Boolean): Maybe<A> =
    when (this) {
        is Just -> if (predicate(value)) this else None
        else -> this
    }

/** Returns a [List] containing all values that are present in this [Iterable]. */
fun <A> Iterable<Maybe<A>>.filterJust(): List<A> = asSequence().filterJust().toList()

/** Returns a [List] containing all values that are present in this [Sequence]. */
fun <A> Sequence<Maybe<A>>.filterJust(): Sequence<A> = filterIsInstance<Just<A>>().map { it.value }

// Align

/**
 * Returns a [Maybe] containing the result of applying the values present in the original [Maybe]
 * and other, applied to [transform] as a [These].
 */
inline fun <A, B, C> Maybe<A>.alignWith(other: Maybe<B>, transform: (These<A, B>) -> C): Maybe<C> =
    when (this) {
        is Just -> {
            val a = value
            when (other) {
                is Just -> {
                    val b = other.value
                    just(transform(These.both(a, b)))
                }
                None -> just(transform(These.thiz(a)))
            }
        }
        None ->
            when (other) {
                is Just -> {
                    val b = other.value
                    just(transform(These.that(b)))
                }
                None -> none
            }
    }

// Alt

/** Returns a [Maybe] containing the value present in the original [Maybe], or [other]. */
infix fun <A> Maybe<A>.orElseMaybe(other: Maybe<A>): Maybe<A> = orElseGetMaybe { other }

/**
 * Returns a [Maybe] containing the value present in the original [Maybe], or the result of [other].
 */
inline fun <A> Maybe<A>.orElseGetMaybe(other: () -> Maybe<A>): Maybe<A> =
    when (this) {
        is Just -> this
        else -> other()
    }

// Apply

/**
 * Returns a [Maybe] containing the value present in [argMaybe] applied to the function present in
 * the original [Maybe].
 */
fun <A, B> Maybe<(A) -> B>.apply(argMaybe: Maybe<A>): Maybe<B> = flatMap { f ->
    argMaybe.map { a -> f(a) }
}

/**
 * Returns a [Maybe] containing the result of applying [transform] to the values present in the
 * original [Maybe] and [other].
 */
inline fun <A, B, C> Maybe<A>.zipWith(other: Maybe<B>, transform: (A, B) -> C) = flatMap { a ->
    other.map { b -> transform(a, b) }
}

// Bind

/**
 * Returns a [Maybe] containing the value present in the [Maybe] present in the original [Maybe].
 */
fun <A> Maybe<Maybe<A>>.flatten(): Maybe<A> = flatMap { it }

// Semigroup

/**
 * Returns a [Maybe] containing the result of applying the values present in the original [Maybe]
 * and other, applied to [transform].
 */
fun <A> Maybe<A>.mergeWith(other: Maybe<A>, transform: (A, A) -> A): Maybe<A> =
    alignWith(other) { it.merge(transform) }

/**
 * Returns a list containing only the present results of applying [transform] to each element in the
 * original iterable.
 */
fun <A, B> Iterable<A>.mapMaybe(transform: (A) -> Maybe<B>): List<B> =
    asSequence().mapMaybe(transform).toList()

/**
 * Returns a sequence containing only the present results of applying [transform] to each element in
 * the original sequence.
 */
fun <A, B> Sequence<A>.mapMaybe(transform: (A) -> Maybe<B>): Sequence<B> =
    map(transform).filterIsInstance<Just<B>>().map { it.value }

/**
 * Returns a map with values of only the present results of applying [transform] to each entry in
 * the original map.
 */
inline fun <K, A, B> Map<K, A>.mapMaybeValues(
    crossinline p: (Map.Entry<K, A>) -> Maybe<B>
): Map<K, B> = asSequence().mapMaybe { entry -> p(entry).map { entry.key to it } }.toMap()

/** Returns a map with all non-present values filtered out. */
fun <K, A> Map<K, Maybe<A>>.filterJustValues(): Map<K, A> =
    asSequence().mapMaybe { (key, mValue) -> mValue.map { key to it } }.toMap()

/**
 * Returns a pair of [Maybes][Maybe] that contain the [Pair.first] and [Pair.second] values present
 * in the original [Maybe].
 */
fun <A, B> Maybe<Pair<A, B>>.splitPair(): Pair<Maybe<A>, Maybe<B>> =
    map { it.first } to map { it.second }

/** Returns the value associated with [key] in this map as a [Maybe]. */
fun <K, V> Map<K, V>.getMaybe(key: K): Maybe<V> {
    val value = get(key)
    if (value == null && !containsKey(key)) {
        return none
    } else {
        @Suppress("UNCHECKED_CAST")
        return just(value as V)
    }
}
