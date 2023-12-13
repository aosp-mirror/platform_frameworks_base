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
 * limitations under the License.
 */

package com.android.systemui.statusbar.commandline

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Utilities for parsing the [String] command line arguments. Arguments are related to the
 * [Parameter] type, which declares the number of, and resulting type of, the arguments that it
 * takes when parsing. For Example:
 * ```
 * my-command --param <str> --param2 <int>
 * ```
 *
 * Defines 2 parameters, the first of which takes a string, and the second requires an int. Because
 * fundamentally _everything_ is a string, we have to define a convenient way to get from the
 * incoming `StringArg` to the resulting `T`-arg, where `T` is the type required by the client.
 *
 * Parsing is therefore a relatively straightforward operation: (String) -> T. However, since
 * parsing can always fail, the type is actually (String) -> Result<T>. We will always want to fail
 * on the first error and propagate it to the caller (typically this results in printing the `help`
 * message of the command`).
 *
 * The identity parsing is trivial:
 * ```
 * (s: String) -> String = { s -> s }
 * ```
 *
 * Basic mappings are actually even provided by Kotlin's stdlib:
 * ```
 * (s: String) -> Boolean = { s -> s.toBooleanOrNull() }
 * (s: String) -> Int = { s -> s.toIntOrNull() }
 * ...
 * ```
 *
 * In order to properly encode errors, we will ascribe an error type to any `null` values, such that
 * parsing looks like this:
 * ```
 * val mapping: (String) -> T? = {...} // for some T
 * val parser: (String) -> Result<T> = { s ->
 *   mapping(s)?.let {
 *     Result.success(it)
 *   } ?: Result.failure(/* some failure type */)
 * }
 * ```
 *
 * Composition
 *
 * The ability to compose value parsing enables us to provide a couple of reasonable default parsers
 * and allow clients to seamlessly build upon that using map functions. Consider the case where we
 * want to validate that a value is an [Int] between 0 and 100. We start with the generic [Int]
 * parser, and a validator, of the type (Int) -> Result<Int>:
 * ```
 * val intParser = { s ->
 *   s.toStringOrNull().?let {...} ?: ...
 * }
 *
 * val validator = { i ->
 *   if (i > 100 || i < 0) {
 *     Result.failure(...)
 *   } else {
 *     Result.success(i)
 *   }
 * ```
 *
 * In order to combine these functions, we need to define a new [flatMap] function that can get us
 * from a `Result<T>` to a `Result<R>`, and short-circuit on any error. We want to see this:
 * ```
 * val validatingParser = { s ->
 *   intParser.invoke(s).flatMap { i ->
 *     validator(i)
 *   }
 * }
 * ```
 *
 * The flatMap is relatively simply defined, we can mimic the existing definition for [Result.map],
 * though the implementation is uglier because of the `internal` definition for `value`
 *
 * ```
 * inline fun <R, T> Result<T>.flatMap(transform: (value: T) -> Result<R>): Result<R> {
 *   return when {
 *     isSuccess -> transform(getOrThrow())
 *     else -> Result.failure(exceptionOrNull()!!)
 *   }
 * }
 * ```
 */

/**
 * Given a [transform] that returns a [Result], apply the transform to this result, unwrapping the
 * return value so that
 *
 * These [contract] and [callsInPlace] methods are copied from the [Result.map] definition
 */
@OptIn(ExperimentalContracts::class)
inline fun <R, T> Result<T>.flatMap(transform: (value: T) -> Result<R>): Result<R> {
    contract { callsInPlace(transform, InvocationKind.AT_MOST_ONCE) }

    return when {
        // Should never throw, we just don't have access to [this.value]
        isSuccess -> transform(getOrThrow())
        // Exception should never be null here
        else -> Result.failure(exceptionOrNull()!!)
    }
}

/**
 * ValueParser turns a [String] into a Result<A> by applying a transform. See the default
 * implementations below for starting points. The intention here is to provide the base mappings and
 * allow clients to attach their own transforms. They are expected to succeed or return null on
 * failure. The failure is propagated to the command parser as a Result and will fail on any
 * [Result.failure]
 */
fun interface ValueParser<out A> {
    fun parseValue(value: String): Result<A>
}

/** Map a [ValueParser] of type A to one of type B, by applying the given [transform] */
inline fun <A, B> ValueParser<A>.map(crossinline transform: (A) -> B?): ValueParser<B> {
    return ValueParser<B> { value ->
        this.parseValue(value).flatMap { a ->
            transform(a)?.let { b -> Result.success(b) }
                ?: Result.failure(ArgParseError("Failed to transform value $value"))
        }
    }
}

/**
 * Base type parsers are provided by the lib, and can be simply composed upon by [ValueParser.map]
 * functions on the parser
 */

/** String parsing always succeeds if the value exists */
private val parseString: ValueParser<String> = ValueParser { value -> Result.success(value) }

private val parseBoolean: ValueParser<Boolean> = ValueParser { value ->
    value.toBooleanStrictOrNull()?.let { Result.success(it) }
        ?: Result.failure(ArgParseError("Failed to parse $value as a boolean"))
}

private val parseInt: ValueParser<Int> = ValueParser { value ->
    value.toIntOrNull()?.let { Result.success(it) }
        ?: Result.failure(ArgParseError("Failed to parse $value as an int"))
}

private val parseFloat: ValueParser<Float> = ValueParser { value ->
    value.toFloatOrNull()?.let { Result.success(it) }
        ?: Result.failure(ArgParseError("Failed to parse $value as a float"))
}

/** Default parsers that can be use as-is, or [map]ped to another type */
object Type {
    val Boolean = parseBoolean
    val Int = parseInt
    val Float = parseFloat
    val String = parseString
}
