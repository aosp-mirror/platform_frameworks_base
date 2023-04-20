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

package com.android.systemui.dump

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import com.android.systemui.CoreStartable
import com.android.systemui.R
import com.android.systemui.dump.DumpHandler.Companion.PRIORITY_ARG_CRITICAL
import com.android.systemui.dump.DumpHandler.Companion.PRIORITY_ARG_NORMAL
import com.android.systemui.dump.nano.SystemUIProtoDump
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.shared.system.UncaughtExceptionPreHandlerManager
import com.google.protobuf.nano.MessageNano
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/**
 * Oversees SystemUI's output during bug reports (and dumpsys in general)
 *
 * Dump output is split into two sections, CRITICAL and NORMAL. In general, the CRITICAL section
 * contains all dumpables that were registered to the [DumpManager], while the NORMAL sections
 * contains all [LogBuffer]s (due to their length).
 *
 * The CRITICAL and NORMAL sections can be found within a bug report by searching for
 * "SERVICE com.android.systemui/.SystemUIService" and
 * "SERVICE com.android.systemui/.dump.SystemUIAuxiliaryDumpService", respectively.
 *
 * Finally, some or all of the dump can be triggered on-demand via adb (see below).
 *
 * ```
 * # For the following, let <invocation> be:
 * $ adb shell dumpsys activity service com.android.systemui/.SystemUIService
 *
 * # To dump specific target(s), specify one or more registered names:
 * $ <invocation> NotifCollection
 * $ <invocation> StatusBar FalsingManager BootCompleteCacheImpl
 *
 * # Log buffers can be dumped in the same way (and can even be mixed in with other dump targets,
 * # although it's not clear why one would want such a thing):
 * $ <invocation> NotifLog
 * $ <invocation> StatusBar NotifLog BootCompleteCacheImpl
 *
 * # If passing -t or --tail, shows only the last N lines of any log buffers:
 * $ <invocation> NotifLog --tail 100
 *
 * # Dump targets are matched using String.endsWith(), so dumpables that register using their
 * # fully-qualified class name can still be dumped using their short name:
 * $ <invocation> com.android.keyguard.KeyguardUpdateMonitor
 * $ <invocation> keyguard.KeyguardUpdateMonitor
 * $ <invocation> KeyguardUpdateMonitor
 *
 * # To dump all dumpables or all buffers:
 * $ <invocation> dumpables
 * $ <invocation> buffers
 *
 * # Finally, the following will simulate what we dump during the CRITICAL and NORMAL sections of a
 * # bug report:
 * $ <invocation> bugreport-critical
 * $ <invocation> bugreport-normal
 *
 * # And if you need to be reminded of this list of commands:
 * $ <invocation> -h
 * $ <invocation> --help
 * ```
 */
class DumpHandler @Inject constructor(
    private val context: Context,
    private val dumpManager: DumpManager,
    private val logBufferEulogizer: LogBufferEulogizer,
    private val startables: MutableMap<Class<*>, Provider<CoreStartable>>,
    private val uncaughtExceptionPreHandlerManager: UncaughtExceptionPreHandlerManager
) {
    /**
     * Registers an uncaught exception handler
     */
    fun init() {
        uncaughtExceptionPreHandlerManager.registerHandler { _, e ->
            if (e is Exception) {
                logBufferEulogizer.record(e)
            }
        }
    }

    /**
     * Dump the diagnostics! Behavior can be controlled via [args].
     */
    fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
        Trace.beginSection("DumpManager#dump()")
        val start = SystemClock.uptimeMillis()

        val parsedArgs = try {
            parseArgs(args)
        } catch (e: ArgParseException) {
            pw.println(e.message)
            return
        }

        when {
            parsedArgs.dumpPriority == PRIORITY_ARG_CRITICAL -> dumpCritical(pw, parsedArgs)
            parsedArgs.dumpPriority == PRIORITY_ARG_NORMAL && !parsedArgs.proto -> {
                dumpNormal(pw, parsedArgs)
            }
            else -> dumpParameterized(fd, pw, parsedArgs)
        }

        pw.println()
        pw.println("Dump took ${SystemClock.uptimeMillis() - start}ms")
        Trace.endSection()
    }

    private fun dumpParameterized(fd: FileDescriptor, pw: PrintWriter, args: ParsedArgs) {
        when (args.command) {
            "bugreport-critical" -> dumpCritical(pw, args)
            "bugreport-normal" -> dumpNormal(pw, args)
            "dumpables" -> dumpDumpables(pw, args)
            "buffers" -> dumpBuffers(pw, args)
            "config" -> dumpConfig(pw)
            "help" -> dumpHelp(pw)
            else -> {
                if (args.proto) {
                    dumpProtoTargets(args.nonFlagArgs, fd, args)
                } else {
                    dumpTargets(args.nonFlagArgs, pw, args)
                }
            }
        }
    }

    private fun dumpCritical(pw: PrintWriter, args: ParsedArgs) {
        dumpManager.dumpCritical(pw, args.rawArgs)
        dumpConfig(pw)
    }

    private fun dumpNormal(pw: PrintWriter, args: ParsedArgs) {
        dumpManager.dumpNormal(pw, args.rawArgs, args.tailLength)
        logBufferEulogizer.readEulogyIfPresent(pw)
    }

    private fun dumpDumpables(pw: PrintWriter, args: ParsedArgs) {
        if (args.listOnly) {
            dumpManager.listDumpables(pw)
        } else {
            dumpManager.dumpDumpables(pw, args.rawArgs)
        }
    }

    private fun dumpBuffers(pw: PrintWriter, args: ParsedArgs) {
        if (args.listOnly) {
            dumpManager.listBuffers(pw)
        } else {
            dumpManager.dumpBuffers(pw, args.tailLength)
        }
    }

    private fun dumpProtoTargets(
            targets: List<String>,
            fd: FileDescriptor,
            args: ParsedArgs
    ) {
        val systemUIProto = SystemUIProtoDump()
        if (targets.isNotEmpty()) {
            for (target in targets) {
                dumpManager.dumpProtoTarget(target, systemUIProto, args.rawArgs)
            }
        } else {
            dumpManager.dumpProtoDumpables(systemUIProto, args.rawArgs)
        }
        val buffer = BufferedOutputStream(FileOutputStream(fd))
        buffer.use {
            it.write(MessageNano.toByteArray(systemUIProto))
            it.flush()
        }
    }

    private fun dumpTargets(
        targets: List<String>,
        pw: PrintWriter,
        args: ParsedArgs
    ) {
        if (targets.isNotEmpty()) {
            for (target in targets) {
                dumpManager.dumpTarget(target, pw, args.rawArgs, args.tailLength)
            }
        } else {
            if (args.listOnly) {
                pw.println("Dumpables:")
                dumpManager.listDumpables(pw)
                pw.println()

                pw.println("Buffers:")
                dumpManager.listBuffers(pw)
            } else {
                pw.println("Nothing to dump :(")
            }
        }
    }

    private fun dumpConfig(pw: PrintWriter) {
        pw.println("SystemUiServiceComponents configuration:")
        pw.print("vendor component: ")
        pw.println(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        val services: MutableList<String> = startables.keys
                .map({ cls: Class<*> -> cls.simpleName })
                .toMutableList()

        services.add(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        dumpServiceList(pw, "global", services.toTypedArray())
        dumpServiceList(pw, "per-user", R.array.config_systemUIServiceComponentsPerUser)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, resId: Int) {
        val services: Array<String> = context.resources.getStringArray(resId)
        dumpServiceList(pw, type, services)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, services: Array<String>?) {
        pw.print(type)
        pw.print(": ")
        if (services == null) {
            pw.println("N/A")
            return
        }
        pw.print(services.size)
        pw.println(" services")
        for (i in services.indices) {
            pw.print("  ")
            pw.print(i)
            pw.print(": ")
            pw.println(services[i])
        }
    }

    private fun dumpHelp(pw: PrintWriter) {
        pw.println("Let <invocation> be:")
        pw.println("$ adb shell dumpsys activity service com.android.systemui/.SystemUIService")
        pw.println()

        pw.println("Most common usage:")
        pw.println("$ <invocation> <targets>")
        pw.println("$ <invocation> NotifLog")
        pw.println("$ <invocation> StatusBar FalsingManager BootCompleteCacheImpl")
        pw.println("etc.")
        pw.println()

        pw.println("Special commands:")
        pw.println("$ <invocation> dumpables")
        pw.println("$ <invocation> buffers")
        pw.println("$ <invocation> bugreport-critical")
        pw.println("$ <invocation> bugreport-normal")
        pw.println("$ <invocation> config")
        pw.println()

        pw.println("Targets can be listed:")
        pw.println("$ <invocation> --list")
        pw.println("$ <invocation> dumpables --list")
        pw.println("$ <invocation> buffers --list")
        pw.println()

        pw.println("Show only the most recent N lines of buffers")
        pw.println("$ <invocation> NotifLog --tail 30")
    }

    private fun parseArgs(args: Array<String>): ParsedArgs {
        val mutArgs = args.toMutableList()
        val pArgs = ParsedArgs(args, mutArgs)

        val iterator = mutArgs.iterator()
        while (iterator.hasNext()) {
            val arg = iterator.next()
            if (arg.startsWith("-")) {
                iterator.remove()
                when (arg) {
                    PRIORITY_ARG -> {
                        pArgs.dumpPriority = readArgument(iterator, PRIORITY_ARG) {
                            if (PRIORITY_OPTIONS.contains(it)) {
                                it
                            } else {
                                throw IllegalArgumentException()
                            }
                        }
                    }
                    PROTO -> pArgs.proto = true
                    "-t", "--tail" -> {
                        pArgs.tailLength = readArgument(iterator, arg) {
                            it.toInt()
                        }
                    }
                    "-l", "--list" -> {
                        pArgs.listOnly = true
                    }
                    "-h", "--help" -> {
                        pArgs.command = "help"
                    }
                    // This flag is passed as part of the proto dump in Bug reports, we can ignore
                    // it because this is our default behavior.
                    "-a" -> {}
                    else -> {
                        throw ArgParseException("Unknown flag: $arg")
                    }
                }
            }
        }

        if (pArgs.command == null && mutArgs.isNotEmpty() && COMMANDS.contains(mutArgs[0])) {
            pArgs.command = mutArgs.removeAt(0)
        }

        return pArgs
    }

    private fun <T> readArgument(
        iterator: MutableIterator<String>,
        flag: String,
        parser: (arg: String) -> T
    ): T {
        if (!iterator.hasNext()) {
            throw ArgParseException("Missing argument for $flag")
        }
        val value = iterator.next()

        return try {
            parser(value).also { iterator.remove() }
        } catch (e: Exception) {
            throw ArgParseException("Invalid argument '$value' for flag $flag")
        }
    }

    companion object {
        const val PRIORITY_ARG = "--dump-priority"
        const val PRIORITY_ARG_CRITICAL = "CRITICAL"
        const val PRIORITY_ARG_NORMAL = "NORMAL"
        const val PROTO = "--proto"
    }
}

private val PRIORITY_OPTIONS = arrayOf(PRIORITY_ARG_CRITICAL, PRIORITY_ARG_NORMAL)

private val COMMANDS = arrayOf(
        "bugreport-critical",
        "bugreport-normal",
        "buffers",
        "dumpables",
        "config",
        "help"
)

private class ParsedArgs(
    val rawArgs: Array<String>,
    val nonFlagArgs: List<String>
) {
    var dumpPriority: String? = null
    var tailLength: Int = 0
    var command: String? = null
    var listOnly = false
    var proto = false
}

class ArgParseException(message: String) : Exception(message)
