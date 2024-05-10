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

/**
 * [CommandParser] defines the collection of tokens which can be parsed from an incoming command
 * list, and parses them into their respective containers. Supported tokens are of the following
 * forms:
 * ```
 * Flag: boolean value, false by default. always optional.
 * Param: named parameter, taking N args all of a given type. Currently only single arg parameters
 *        are supported.
 * SubCommand: named command created by adding a command to a parent. Supports all fields above, but
 *             not other subcommands.
 * ```
 *
 * Tokens are added via the factory methods for each token type. They can be made `required` by
 * calling the [require] method for the appropriate type, as follows:
 * ```
 * val requiredParam = parser.require(parser.param(...))
 * ```
 *
 * The reason for having an explicit require is so that generic type arguments can be handled
 * properly. See [SingleArgParam] and [SingleArgParamOptional] for the difference between an
 * optional parameter and a required one.
 *
 * Typical usage of a required parameter, however, will occur within the context of a
 * [ParseableCommand], which defines a convenience `require()` method:
 * ```
 * class MyCommand : ParseableCommand {
 *   val requiredParam = param(...).require()
 * }
 * ```
 *
 * This parser defines two modes of parsing, both of which validate for required parameters.
 * 1. [parse] is a top-level parsing method. This parser will walk the given arg list and populate
 *    all of the delegate classes based on their type. It will handle SubCommands, and after parsing
 *    will check for any required-but-missing SubCommands or Params.
 *
 *    **This method requires that every received token is represented in its grammar.**
 * 2. [parseAsSubCommand] is a second-level parsing method suitable for any [SubCommand]. This
 *    method will handle _only_ flags and params. It will return parsing control to its parent
 *    parser on the first unknown token rather than throwing.
 */
class CommandParser {
    private val _flags = mutableListOf<Flag>()
    val flags: List<Flag> = _flags
    private val _params = mutableListOf<Param>()
    val params: List<Param> = _params
    private val _subCommands = mutableListOf<SubCommand>()
    val subCommands: List<SubCommand> = _subCommands

    private val tokenSet = mutableSetOf<String>()

    /**
     * Parse the arg list into the fields defined in the containing class.
     *
     * @return true if all required fields are present after parsing
     * @throws ArgParseError on any failure to process args
     */
    fun parse(args: List<String>): Boolean {
        if (args.isEmpty()) {
            return false
        }

        val iterator = args.listIterator()
        var tokenHandled: Boolean
        while (iterator.hasNext()) {
            val token = iterator.next()
            tokenHandled = false

            flags
                .find { it.matches(token) }
                ?.let {
                    it.inner = true
                    tokenHandled = true
                }

            if (tokenHandled) continue

            params
                .find { it.matches(token) }
                ?.let {
                    it.parseArgsFromIter(iterator)
                    tokenHandled = true
                }

            if (tokenHandled) continue

            subCommands
                .find { it.matches(token) }
                ?.let {
                    it.parseSubCommandArgs(iterator)
                    tokenHandled = true
                }

            if (!tokenHandled) {
                throw ArgParseError("Unknown token: $token")
            }
        }

        return validateRequiredParams()
    }

    /**
     * Parse a subset of the commands that came in from the top-level [parse] method, for the
     * subcommand that this parser represents. Note that subcommands may not contain other
     * subcommands. But they may contain flags and params.
     *
     * @return true if all required fields are present after parsing
     * @throws ArgParseError on any failure to process args
     */
    fun parseAsSubCommand(iter: ListIterator<String>): Boolean {
        // arg[-1] is our subcommand name, so the rest of the args are either for this
        // subcommand, OR for the top-level command to handle. Therefore, we bail on the first
        // failure, but still check our own required params

        // The mere presence of a subcommand (similar to a flag) is a valid subcommand
        if (flags.isEmpty() && params.isEmpty()) {
            return validateRequiredParams()
        }

        var tokenHandled: Boolean
        while (iter.hasNext()) {
            val token = iter.next()
            tokenHandled = false

            flags
                .find { it.matches(token) }
                ?.let {
                    it.inner = true
                    tokenHandled = true
                }

            if (tokenHandled) continue

            params
                .find { it.matches(token) }
                ?.let {
                    it.parseArgsFromIter(iter)
                    tokenHandled = true
                }

            if (!tokenHandled) {
                // Move the cursor position backwards since we've arrived at a token
                // that we don't own
                iter.previous()
                break
            }
        }

        return validateRequiredParams()
    }

    /**
     * If [parse] or [parseAsSubCommand] does not produce a valid result, generate a list of errors
     * based on missing elements
     */
    fun generateValidationErrorMessages(): List<String> {
        val missingElements = mutableListOf<String>()

        if (unhandledParams.isNotEmpty()) {
            val names = unhandledParams.map { it.longName }
            missingElements.add("No values passed for required params: $names")
        }

        if (unhandledSubCmds.isNotEmpty()) {
            missingElements.addAll(unhandledSubCmds.map { it.longName })
            val names = unhandledSubCmds.map { it.shortName }
            missingElements.add("No values passed for required sub-commands: $names")
        }

        return missingElements
    }

    /** Check for any missing, required params, or any invalid subcommands */
    private fun validateRequiredParams(): Boolean =
        unhandledParams.isEmpty() && unhandledSubCmds.isEmpty() && unvalidatedSubCmds.isEmpty()

    // If any required param (aka non-optional) hasn't handled a field, then return false
    private val unhandledParams: List<Param>
        get() = params.filter { (it is SingleArgParam<*>) && !it.handled }

    private val unhandledSubCmds: List<SubCommand>
        get() = subCommands.filter { (it is RequiredSubCommand<*> && !it.handled) }

    private val unvalidatedSubCmds: List<SubCommand>
        get() = subCommands.filter { !it.validationStatus }

    private fun checkCliNames(short: String?, long: String): String? {
        if (short != null && tokenSet.contains(short)) {
            return short
        }

        if (tokenSet.contains(long)) {
            return long
        }

        return null
    }

    private fun subCommandContainsSubCommands(cmd: ParseableCommand): Boolean =
        cmd.parser.subCommands.isNotEmpty()

    private fun registerNames(short: String?, long: String) {
        if (short != null) {
            tokenSet.add(short)
        }
        tokenSet.add(long)
    }

    /**
     * Turns a [SingleArgParamOptional]<T> into a [SingleArgParam] by converting the [T?] into [T]
     *
     * @return a [SingleArgParam] property delegate
     */
    fun <T : Any> require(old: SingleArgParamOptional<T>): SingleArgParam<T> {
        val newParam =
            SingleArgParam(
                longName = old.longName,
                shortName = old.shortName,
                description = old.description,
                valueParser = old.valueParser,
            )

        replaceWithRequired(old, newParam)
        return newParam
    }

    private fun <T : Any> replaceWithRequired(
        old: SingleArgParamOptional<T>,
        new: SingleArgParam<T>,
    ) {
        _params.remove(old)
        _params.add(new)
    }

    /**
     * Turns an [OptionalSubCommand] into a [RequiredSubCommand] by converting the [T?] in to [T]
     *
     * @return a [RequiredSubCommand] property delegate
     */
    fun <T : ParseableCommand> require(optional: OptionalSubCommand<T>): RequiredSubCommand<T> {
        val newCmd = RequiredSubCommand(optional.cmd)
        replaceWithRequired(optional, newCmd)
        return newCmd
    }

    private fun <T : ParseableCommand> replaceWithRequired(
        old: OptionalSubCommand<T>,
        new: RequiredSubCommand<T>,
    ) {
        _subCommands.remove(old)
        _subCommands.add(new)
    }

    internal fun flag(
        longName: String,
        shortName: String? = null,
        description: String = "",
    ): Flag {
        checkCliNames(shortName, longName)?.let {
            throw IllegalArgumentException("Detected reused flag name ($it)")
        }
        registerNames(shortName, longName)

        val flag = Flag(shortName, longName, description)
        _flags.add(flag)
        return flag
    }

    internal fun <T : Any> param(
        longName: String,
        shortName: String? = null,
        description: String = "",
        valueParser: ValueParser<T>,
    ): SingleArgParamOptional<T> {
        checkCliNames(shortName, longName)?.let {
            throw IllegalArgumentException("Detected reused param name ($it)")
        }
        registerNames(shortName, longName)

        val param =
            SingleArgParamOptional(
                shortName = shortName,
                longName = longName,
                description = description,
                valueParser = valueParser,
            )
        _params.add(param)
        return param
    }

    internal fun <T : ParseableCommand> subCommand(
        command: T,
    ): OptionalSubCommand<T> {
        checkCliNames(null, command.name)?.let {
            throw IllegalArgumentException("Cannot re-use name for subcommand ($it)")
        }

        if (subCommandContainsSubCommands(command)) {
            throw IllegalArgumentException(
                "SubCommands may not contain other SubCommands. $command"
            )
        }

        registerNames(null, command.name)

        val subCmd = OptionalSubCommand(command)
        _subCommands.add(subCmd)
        return subCmd
    }
}
