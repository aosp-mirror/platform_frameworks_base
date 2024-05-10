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
import java.io.PrintWriter
import java.lang.IllegalArgumentException
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * An implementation of [Command] that includes a [CommandParser] which can set all delegated
 * properties.
 *
 * As the number of registrants to [CommandRegistry] grows, we should have a default mechanism for
 * parsing common command line arguments. We are not expecting to build an arbitrarily-functional
 * CLI, nor a GNU arg parse compliant interface here, we simply want to be able to empower clients
 * to create simple CLI grammars such as:
 * ```
 * $ my_command [-f|--flag]
 * $ my_command [-a|--arg] <params...>
 * $ my_command [subcommand1] [subcommand2]
 * $ my_command <positional_arg ...> # not-yet implemented
 * ```
 *
 * Note that the flags `-h` and `--help` are reserved for the base class. It seems prudent to just
 * avoid them in your implementation.
 *
 * Usage:
 *
 * The intended usage tries to be clever enough to enable good ergonomics, while not too clever as
 * to be unmaintainable. Using the default parser is done using property delegates, and looks like:
 * ```
 * class MyCommand(
 *     onExecute: (cmd: MyCommand, pw: PrintWriter) -> ()
 * ) : ParseableCommand(name) {
 *     val flag1 by flag(
 *         shortName = "-f",
 *         longName = "--flag",
 *         required = false,
 *     )
 *     val param1: String by param(
 *         shortName = "-a",
 *         longName = "--args",
 *         valueParser = Type.String
 *     ).required()
 *     val param2: Int by param(..., valueParser = Type.Int)
 *     val subCommand by subCommand(...)
 *
 *     override fun execute(pw: PrintWriter) {
 *         onExecute(this, pw)
 *     }
 *
 *     companion object {
 *        const val name = "my_command"
 *     }
 * }
 *
 * fun main() {
 *     fun printArgs(cmd: MyCommand, pw: PrintWriter) {
 *         pw.println("${cmd.flag1}")
 *         pw.println("${cmd.param1}")
 *         pw.println("${cmd.param2}")
 *         pw.println("${cmd.subCommand}")
 *     }
 *
 *     commandRegistry.registerCommand(MyCommand.companion.name) {
 *         MyCommand() { (cmd, pw) ->
 *             printArgs(cmd, pw)
 *         }
 *     }
 * }
 *
 * ```
 */
abstract class ParseableCommand(val name: String, val description: String? = null) : Command {
    val parser: CommandParser = CommandParser()

    val help by flag(longName = "help", shortName = "h", description = "Print help and return")

    /**
     * After [execute(pw, args)] is called, this class goes through a parsing stage and sets all
     * delegated properties. It is safe to read any delegated properties here.
     *
     * This method is never called for [SubCommand]s, since they are associated with a top-level
     * command that handles [execute]
     */
    abstract fun execute(pw: PrintWriter)

    /**
     * Given a command string list, [execute] parses the incoming command and validates the input.
     * If this command or any of its subcommands is passed `-h` or `--help`, then execute will only
     * print the relevant help message and exit.
     *
     * If any error is thrown during parsing, we will catch and log the error. This process should
     * _never_ take down its process. Override [onParseFailed] to handle an [ArgParseError].
     *
     * Important: none of the delegated fields can be read before this stage.
     */
    override fun execute(pw: PrintWriter, args: List<String>) {
        val success: Boolean
        try {
            success = parser.parse(args)
        } catch (e: ArgParseError) {
            pw.println(e.message)
            onParseFailed(e)
            return
        } catch (e: Exception) {
            pw.println("Unknown exception encountered during parse")
            pw.println(e)
            return
        }

        // Now we've parsed the incoming command without error. There are two things to check:
        // 1. If any help is requested, print the help message and return
        // 2. Otherwise, make sure required params have been passed in, and execute

        val helpSubCmds = subCmdsRequestingHelp()

        // Top-level help encapsulates subcommands. Otherwise, if _any_ subcommand requests
        // help then defer to them. Else, just execute
        if (help) {
            help(pw)
        } else if (helpSubCmds.isNotEmpty()) {
            helpSubCmds.forEach { it.help(pw) }
        } else {
            if (!success) {
                parser.generateValidationErrorMessages().forEach { pw.println(it) }
            } else {
                execute(pw)
            }
        }
    }

    /**
     * Returns a list of all commands that asked for help. If non-empty, parsing will stop to print
     * help. It is not guaranteed that delegates are fulfilled if help is requested
     */
    private fun subCmdsRequestingHelp(): List<ParseableCommand> =
        parser.subCommands.filter { it.cmd.help }.map { it.cmd }

    /** Override to do something when parsing fails */
    open fun onParseFailed(error: ArgParseError) {}

    /** Override to print a usage clause. E.g. `usage: my-cmd <arg1> <arg2>` */
    open fun usage(pw: IndentingPrintWriter) {}

    /**
     * Print out the list of tokens, their received types if any, and their description in a
     * formatted string.
     *
     * Example:
     * ```
     * my-command:
     *   MyCmd.description
     *
     * [optional] usage block
     *
     * Flags:
     *   -f
     *     description
     *   --flag2
     *     description
     *
     * Parameters:
     *   Required:
     *     -p1 [Param.Type]
     *       description
     *     --param2 [Param.Type]
     *       description
     *   Optional:
     *     same as above
     *
     * SubCommands:
     *   Required:
     *     ...
     *   Optional:
     *     ...
     * ```
     */
    override fun help(pw: PrintWriter) {
        val ipw = IndentingPrintWriter(pw)
        ipw.printBoxed(name)
        ipw.println()

        // Allow for a simple `usage` block for clients
        ipw.indented { usage(ipw) }

        if (description != null) {
            ipw.indented { ipw.println(description) }
            ipw.println()
        }

        val flags = parser.flags
        if (flags.isNotEmpty()) {
            ipw.println("FLAGS:")
            ipw.indented {
                flags.forEach {
                    it.describe(ipw)
                    ipw.println()
                }
            }
        }

        val (required, optional) = parser.params.partition { it is SingleArgParam<*> }
        if (required.isNotEmpty()) {
            ipw.println("REQUIRED PARAMS:")
            required.describe(ipw)
        }
        if (optional.isNotEmpty()) {
            ipw.println("OPTIONAL PARAMS:")
            optional.describe(ipw)
        }

        val (reqSub, optSub) = parser.subCommands.partition { it is RequiredSubCommand<*> }
        if (reqSub.isNotEmpty()) {
            ipw.println("REQUIRED SUBCOMMANDS:")
            reqSub.describe(ipw)
        }
        if (optSub.isNotEmpty()) {
            ipw.println("OPTIONAL SUBCOMMANDS:")
            optSub.describe(ipw)
        }
    }

    fun flag(
        longName: String,
        shortName: String? = null,
        description: String = "",
    ): Flag {
        if (!checkShortName(shortName)) {
            throw IllegalArgumentException(
                "Flag short name must be one character long, or null. Got ($shortName)"
            )
        }

        if (!checkLongName(longName)) {
            throw IllegalArgumentException("Flags must not start with '-'. Got $($longName)")
        }

        val short = shortName?.let { "-$shortName" }
        val long = "--$longName"

        return parser.flag(long, short, description)
    }

    fun <T : Any> param(
        longName: String,
        shortName: String? = null,
        description: String = "",
        valueParser: ValueParser<T>,
    ): SingleArgParamOptional<T> {
        if (!checkShortName(shortName)) {
            throw IllegalArgumentException(
                "Parameter short name must be one character long, or null. Got ($shortName)"
            )
        }

        if (!checkLongName(longName)) {
            throw IllegalArgumentException("Parameters must not start with '-'. Got $($longName)")
        }

        val short = shortName?.let { "-$shortName" }
        val long = "--$longName"

        return parser.param(long, short, description, valueParser)
    }

    fun <T : ParseableCommand> subCommand(
        command: T,
    ) = parser.subCommand(command)

    /** For use in conjunction with [param], makes the parameter required */
    fun <T : Any> SingleArgParamOptional<T>.required(): SingleArgParam<T> = parser.require(this)

    /** For use in conjunction with [subCommand], makes the given [SubCommand] required */
    fun <T : ParseableCommand> OptionalSubCommand<T>.required(): RequiredSubCommand<T> =
        parser.require(this)

    private fun checkShortName(short: String?): Boolean {
        return short == null || short.length == 1
    }

    private fun checkLongName(long: String): Boolean {
        return !long.startsWith("-")
    }

    companion object {
        fun Iterable<Describable>.describe(pw: IndentingPrintWriter) {
            pw.indented {
                forEach {
                    it.describe(pw)
                    pw.println()
                }
            }
        }
    }
}

/**
 * A flag is a boolean value passed over the command line. It can have a short form or long form.
 * The value is [Boolean.true] if the flag is found, else false
 */
data class Flag(
    override val shortName: String? = null,
    override val longName: String,
    override val description: String? = null,
) : ReadOnlyProperty<Any?, Boolean>, Describable {
    var inner: Boolean = false

    override fun getValue(thisRef: Any?, property: KProperty<*>) = inner
}

/**
 * Named CLI token. Can have a short or long name. Note: consider renaming to "primary" and
 * "secondary" names since we don't actually care what the strings are
 *
 * Flags and params will have [shortName]s that are always prefixed with a single dash, while
 * [longName]s are prefixed by a double dash. E.g., `my_command -f --flag`.
 *
 * Subcommands do not do any prefixing, and register their name as the [longName]
 *
 * Can be matched against an incoming token
 */
interface CliNamed {
    val shortName: String?
    val longName: String

    fun matches(token: String) = shortName == token || longName == token
}

interface Describable : CliNamed {
    val description: String?

    fun describe(pw: IndentingPrintWriter) {
        if (shortName != null) {
            pw.print("$shortName, ")
        }
        pw.print(longName)
        pw.println()
        if (description != null) {
            pw.indented { pw.println(description) }
        }
    }
}

/**
 * Print [s] inside of a unicode character box, like so:
 * ```
 *  ╔═══════════╗
 *  ║ my-string ║
 *  ╚═══════════╝
 * ```
 */
fun PrintWriter.printDoubleBoxed(s: String) {
    val length = s.length
    println("╔${"═".repeat(length + 2)}╗")
    println("║ $s ║")
    println("╚${"═".repeat(length + 2)}╝")
}

/**
 * Print [s] inside of a unicode character box, like so:
 * ```
 *  ┌───────────┐
 *  │ my-string │
 *  └───────────┘
 * ```
 */
fun PrintWriter.printBoxed(s: String) {
    val length = s.length
    println("┌${"─".repeat(length + 2)}┐")
    println("│ $s │")
    println("└${"─".repeat(length + 2)}┘")
}

fun IndentingPrintWriter.indented(block: () -> Unit) {
    increaseIndent()
    block()
    decreaseIndent()
}
