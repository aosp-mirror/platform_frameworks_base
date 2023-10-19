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

import android.util.IndentingPrintWriter
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Definitions for all parameter types usable by [ParseableCommand]. Parameters are command line
 * tokens that accept a fixed number of arguments and convert them to a parsed type.
 *
 * Example:
 * ```
 * my_command --single-arg-param arg
 * ```
 *
 * In the example, `my_command` is the name of the command, `--single-arg-param` is the parameter,
 * and `arg` is the value parsed by that parameter into its eventual type.
 *
 * Note on generics: The intended usage for parameters is to be able to return the parsed type from
 * the given command as a `val` via property delegation. For example, let's say we have a command
 * that has one optional and one required parameter:
 * ```
 * class MyCommand : ParseableCommand {
 *   val requiredParam: Int by parser.param(...).required()
 *   val optionalParam: Int? by parser.param(...)
 * }
 * ```
 *
 * In order to make the simple `param` method return the correct type, we need to do two things:
 * 1. Break out the generic type into 2 pieces (TParsed and T)
 * 2. Create two different underlying Parameter subclasses to handle the property delegation. One
 *    handles `T?` and the other handles `T`. Note that in both cases, `TParsed` is always non-null
 *    since the value parsed from the argument will throw an exception if missing or if it cannot be
 *    parsed.
 */

/** A param type knows the number of arguments it expects */
sealed interface Param : Describable {
    val numArgs: Int

    /**
     * Consume [numArgs] items from the iterator and relay the result into its corresponding
     * delegated type.
     */
    fun parseArgsFromIter(iterator: Iterator<String>)
}

/**
 * Base class for required and optional SingleArgParam classes. For convenience, UnaryParam is
 * defined as a [MultipleArgParam] where numArgs = 1. The benefit is that we can define the parsing
 * in a single place, and yet on the client side we can unwrap the underlying list of params
 * automatically.
 */
abstract class UnaryParamBase<out T, out TParsed : T>(val wrapped: MultipleArgParam<T, TParsed>) :
    Param, ReadOnlyProperty<Any?, T> {
    var handled = false

    override fun describe(pw: IndentingPrintWriter) {
        if (shortName != null) {
            pw.print("$shortName, ")
        }
        pw.print(longName)
        pw.println(" ${typeDescription()}")
        if (description != null) {
            pw.indented { pw.println(description) }
        }
    }

    /**
     * Try to describe the arg type. We can know if it's one of the base types what kind of input it
     * takes. Otherwise just print "<arg>" and let the clients describe in the help text
     */
    private fun typeDescription() =
        when (wrapped.valueParser) {
            Type.Int -> "<int>"
            Type.Float -> "<float>"
            Type.String -> "<string>"
            Type.Boolean -> "<boolean>"
            else -> "<arg>"
        }
}

/** Required single-arg parameter, delegating a non-null type to the client. */
class SingleArgParam<out T : Any>(
    override val longName: String,
    override val shortName: String? = null,
    override val description: String? = null,
    val valueParser: ValueParser<T>,
) :
    UnaryParamBase<T, T>(
        MultipleArgParam(
            longName,
            shortName,
            1,
            description,
            valueParser,
        )
    ) {

    override fun getValue(thisRef: Any?, property: KProperty<*>): T =
        if (handled) {
            wrapped.getValue(thisRef, property)[0]
        } else {
            throw IllegalStateException("Attempt to read property before parse() has executed")
        }

    override val numArgs: Int = 1

    override fun parseArgsFromIter(iterator: Iterator<String>) {
        wrapped.parseArgsFromIter(iterator)
        handled = true
    }
}

/** Optional single-argument parameter, delegating a nullable type to the client. */
class SingleArgParamOptional<out T : Any>(
    override val longName: String,
    override val shortName: String? = null,
    override val description: String? = null,
    val valueParser: ValueParser<T>,
) :
    UnaryParamBase<T?, T>(
        MultipleArgParam(
            longName,
            shortName,
            1,
            description,
            valueParser,
        )
    ) {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T? =
        wrapped.getValue(thisRef, property).getOrNull(0)

    override val numArgs: Int = 1

    override fun parseArgsFromIter(iterator: Iterator<String>) {
        wrapped.parseArgsFromIter(iterator)
        handled = true
    }
}

/**
 * Parses a list of args into the underlying [T] data type. The resultant value is an ordered list
 * of type [TParsed].
 *
 * [T] and [TParsed] are split out here in the case where the entire param is optional. I.e., a
 * MultipleArgParam<T?, T> indicates a command line argument that can be omitted. In that case, the
 * inner list is List<T>?, NOT List<T?>. If the argument is provided, then the type is always going
 * to be parsed into T rather than T?.
 */
class MultipleArgParam<out T, out TParsed : T>(
    override val longName: String,
    override val shortName: String? = null,
    override val numArgs: Int = 1,
    override val description: String? = null,
    val valueParser: ValueParser<TParsed>,
) : ReadOnlyProperty<Any?, List<TParsed>>, Param {
    private val inner: MutableList<TParsed> = mutableListOf()

    override fun getValue(thisRef: Any?, property: KProperty<*>): List<TParsed> = inner

    /**
     * Consumes [numArgs] values of the iterator and parses them into [TParsed].
     *
     * @throws ArgParseError on the first failure
     */
    override fun parseArgsFromIter(iterator: Iterator<String>) {
        if (!iterator.hasNext()) {
            throw ArgParseError("no argument provided for $shortName")
        }
        for (i in 0 until numArgs) {
            valueParser
                .parseValue(iterator.next())
                .fold(onSuccess = { inner.add(it) }, onFailure = { throw it })
        }
    }
}

data class ArgParseError(override val message: String) : Exception(message)
