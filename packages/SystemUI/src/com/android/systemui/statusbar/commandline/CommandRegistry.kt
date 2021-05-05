/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context

import com.android.systemui.Prefs
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main

import java.io.PrintWriter
import java.lang.IllegalStateException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

import javax.inject.Inject

/**
 * Registry / dispatcher for incoming shell commands. See [StatusBarManagerService] and
 * [StatusBarShellCommand] for how things are set up. Commands come in here by way of the service
 * like so:
 *
 * `adb shell cmd statusbar <command>`
 *
 * Where `cmd statusbar` send the shell command through to StatusBarManagerService, and
 * <command> is either processed in system server, or sent through to IStatusBar (CommandQueue)
 */
@SysUISingleton
class CommandRegistry @Inject constructor(
    val context: Context,
    @Main val mainExecutor: Executor
) {
    // To keep the command line parser hermetic, create a new one for every shell command
    private val commandMap = mutableMapOf<String, CommandWrapper>()
    private var initialized = false

    /**
     * Register a [Command] for a given name. The name here is the top-level namespace for
     * the registered command. A command could look like this for instance:
     *
     * `adb shell cmd statusbar notifications list`
     *
     * Where `notifications` is the command that signifies which receiver to send the remaining args
     * to.
     *
     * @param command String name of the command to register. Currently does not support aliases
     * @param receiverFactory Creates an instance of the receiver on every command
     * @param executor Pass an executor to offload your `receive` to another thread
     */
    @Synchronized
    fun registerCommand(
        name: String,
        commandFactory: () -> Command,
        executor: Executor
    ) {
        if (commandMap[name] != null) {
            throw IllegalStateException("A command is already registered for ($name)")
        }
        commandMap[name] = CommandWrapper(commandFactory, executor)
    }

    /**
     * Register a [Command] for a given name, to be executed on the main thread.
     */
    @Synchronized
    fun registerCommand(name: String, commandFactory: () -> Command) {
        registerCommand(name, commandFactory, mainExecutor)
    }

    /** Unregister a receiver */
    @Synchronized
    fun unregisterCommand(command: String) {
        commandMap.remove(command)
    }

    private fun initializeCommands() {
        initialized = true
        // TODO: Might want a dedicated place for commands without a home. Currently
        // this is here because Prefs.java is just an interface
        registerCommand("prefs") { PrefsCommand(context) }
    }

    /**
     * Receive a shell command and dispatch to the appropriate [Command]. Blocks until finished.
     */
    fun onShellCommand(pw: PrintWriter, args: Array<String>) {
        if (!initialized) initializeCommands()

        if (args.isEmpty()) {
            help(pw)
            return
        }

        val commandName = args[0]
        val wrapper = commandMap[commandName]

        if (wrapper == null) {
            help(pw)
            return
        }

        // Create a new instance of the command
        val command = wrapper.commandFactory()

        // Wrap the receive command in a task so that we can wait for its completion
        val task = FutureTask<Unit> {
            command.execute(pw, args.drop(1))
        }

        wrapper.executor.execute {
            task.run()
        }

        // Wait for the future to complete
        task.get()
    }

    private fun help(pw: PrintWriter) {
        pw.println("Usage: adb shell cmd statusbar <command>")
        pw.println("  known commands:")
        for (k in commandMap.keys) {
            pw.println("   $k")
        }
    }
}

private const val TAG = "CommandRegistry"

interface Command {
    fun execute(pw: PrintWriter, args: List<String>)
    fun help(pw: PrintWriter)
}

// Wrap commands in an executor package
private data class CommandWrapper(val commandFactory: () -> Command, val executor: Executor)

// Commands can go here for now, but they should move outside

private class PrefsCommand(val context: Context) : Command {
    override fun help(pw: PrintWriter) {
        pw.println("usage: prefs <command> [args]")
        pw.println("Available commands:")
        pw.println("  list-prefs")
        pw.println("  set-pref <pref name> <value>")
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        if (args.isEmpty()) {
            help(pw)
            return
        }

        val topLevel = args[0]

        when (topLevel) {
            "list-prefs" -> listPrefs(pw)
            else -> help(pw)
        }
    }

    private fun listPrefs(pw: PrintWriter) {
        pw.println("Available keys:")
        for (field in Prefs.Key::class.java.declaredFields) {
            pw.print("  ")
            pw.println(field.get(Prefs.Key::class.java))
        }
    }
}
