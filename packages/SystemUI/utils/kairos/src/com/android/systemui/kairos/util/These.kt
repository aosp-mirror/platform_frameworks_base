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

/** Contains at least one of two potential values. */
sealed class These<A, B> {
    /** Contains a single potential value. */
    class This<A, B> internal constructor(val thiz: A) : These<A, B>()

    /** Contains a single potential value. */
    class That<A, B> internal constructor(val that: B) : These<A, B>()

    /** Contains both potential values. */
    class Both<A, B> internal constructor(val thiz: A, val that: B) : These<A, B>()

    companion object {
        /** Constructs a [These] containing only [thiz]. */
        fun <A, B> thiz(thiz: A): These<A, B> = This(thiz)

        /** Constructs a [These] containing only [that]. */
        fun <A, B> that(that: B): These<A, B> = That(that)

        /** Constructs a [These] containing both [thiz] and [that]. */
        fun <A, B> both(thiz: A, that: B): These<A, B> = Both(thiz, that)
    }
}

/**
 * Returns a single value from this [These]; either the single value held within, or the result of
 * applying [f] to both values.
 */
inline fun <A> These<A, A>.merge(f: (A, A) -> A): A =
    when (this) {
        is These.This -> thiz
        is These.That -> that
        is These.Both -> f(thiz, that)
    }

/** Returns the [These.This] [value][These.This.thiz] present in this [These] as a [Maybe]. */
fun <A> These<A, *>.maybeThis(): Maybe<A> =
    when (this) {
        is These.Both -> just(thiz)
        is These.That -> None
        is These.This -> just(thiz)
    }

/**
 * Returns the [These.This] [value][These.This.thiz] present in this [These], or `null` if not
 * present.
 */
fun <A : Any> These<A, *>.thisOrNull(): A? =
    when (this) {
        is These.Both -> thiz
        is These.That -> null
        is These.This -> thiz
    }

/** Returns the [These.That] [value][These.That.that] present in this [These] as a [Maybe]. */
fun <A> These<*, A>.maybeThat(): Maybe<A> =
    when (this) {
        is These.Both -> just(that)
        is These.That -> just(that)
        is These.This -> None
    }

/**
 * Returns the [These.That] [value][These.That.that] present in this [These], or `null` if not
 * present.
 */
fun <A : Any> These<*, A>.thatOrNull(): A? =
    when (this) {
        is These.Both -> that
        is These.That -> that
        is These.This -> null
    }

/** Returns [These.Both] values present in this [These] as a [Maybe]. */
fun <A, B> These<A, B>.maybeBoth(): Maybe<Pair<A, B>> =
    when (this) {
        is These.Both -> just(thiz to that)
        else -> None
    }

/** Returns a [These] containing [thiz] and/or [that] if they are present. */
fun <A, B> these(thiz: Maybe<A>, that: Maybe<B>): Maybe<These<A, B>> =
    when (thiz) {
        is Just ->
            just(
                when (that) {
                    is Just -> These.both(thiz.value, that.value)
                    else -> These.thiz(thiz.value)
                }
            )
        else ->
            when (that) {
                is Just -> just(These.that(that.value))
                else -> none
            }
    }

/**
 * Returns a [These] containing [thiz] and/or [that] if they are non-null, or `null` if both are
 * `null`.
 */
fun <A : Any, B : Any> theseNull(thiz: A?, that: B?): These<A, B>? =
    thiz?.let { that?.let { These.both(thiz, that) } ?: These.thiz(thiz) }
        ?: that?.let { These.that(that) }

/**
 * Returns two maps, with [Pair.first] containing all [These.This] values and [Pair.second]
 * containing all [These.That] values.
 *
 * If the value is [These.Both], then the associated key with appear in both output maps, bound to
 * [These.Both.thiz] and [These.Both.that] in each respective output.
 */
fun <K, A, B> Map<K, These<A, B>>.partitionThese(): Pair<Map<K, A>, Map<K, B>> {
    val a = mutableMapOf<K, A>()
    val b = mutableMapOf<K, B>()
    for ((k, t) in this) {
        when (t) {
            is These.Both -> {
                a[k] = t.thiz
                b[k] = t.that
            }
            is These.That -> {
                b[k] = t.that
            }
            is These.This -> {
                a[k] = t.thiz
            }
        }
    }
    return a to b
}
