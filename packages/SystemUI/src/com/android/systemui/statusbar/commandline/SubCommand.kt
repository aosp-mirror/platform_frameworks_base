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
 * Sub commands wrap [ParseableCommand]s and are attached to a parent [ParseableCommand]. As such
 * they have their own parser which will parse the args as a subcommand. I.e., the subcommand's
 * parser will consume the iterator created by the parent, reversing the index when it reaches an
 * unknown token.
 *
 * In order to keep subcommands relatively simple and not have to do complicated validation, sub
 * commands will return control to the parent parser as soon as they discover a token that they do
 * not own. They will throw an [ArgParseError] if parsing fails or if they don't receive arguments
 * for a required parameter.
 */
sealed interface SubCommand : Describable {
    val cmd: ParseableCommand

    /** Checks if all of the required elements were passed in to [parseSubCommandArgs] */
    var validationStatus: Boolean

    /**
     * To keep parsing simple, [parseSubCommandArgs] requires a [ListIterator] so that it can rewind
     * the iterator when it yields control upwards
     */
    fun parseSubCommandArgs(iterator: ListIterator<String>)
}

/**
 * Note that the delegated type from the subcommand is `T: ParseableCommand?`. SubCommands are
 * created via adding a fully-formed [ParseableCommand] to parent command.
 *
 * At this point in time, I don't recommend nesting subcommands.
 */
class OptionalSubCommand<T : ParseableCommand>(
    override val cmd: T,
) : SubCommand, ReadOnlyProperty<Any?, ParseableCommand?> {
    override val shortName: String? = null
    override val longName: String = cmd.name
    override val description: String? = cmd.description
    override var validationStatus = true

    private var isPresent = false

    /** Consume tokens from the iterator and pass them to the wrapped command */
    override fun parseSubCommandArgs(iterator: ListIterator<String>) {
        validationStatus = cmd.parser.parseAsSubCommand(iterator)
        isPresent = true
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T? =
        if (isPresent) {
            cmd
        } else {
            null
        }

    override fun describe(pw: IndentingPrintWriter) {
        cmd.help(pw)
    }
}

/**
 * Non-optional subcommand impl. Top-level parser is expected to throw [ArgParseError] if this token
 * is not present in the incoming command
 */
class RequiredSubCommand<T : ParseableCommand>(
    override val cmd: T,
) : SubCommand, ReadOnlyProperty<Any?, ParseableCommand> {
    override val shortName: String? = null
    override val longName: String = cmd.name
    override val description: String? = cmd.description
    override var validationStatus = true

    /** Unhandled, required subcommands are an error */
    var handled = false

    override fun parseSubCommandArgs(iterator: ListIterator<String>) {
        validationStatus = cmd.parser.parseAsSubCommand(iterator)
        handled = true
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): ParseableCommand = cmd

    override fun describe(pw: IndentingPrintWriter) {
        cmd.help(pw)
    }
}
