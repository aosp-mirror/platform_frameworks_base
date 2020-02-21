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
import android.util.ArrayMap
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dump.DumpManager.Companion.PRIORITY_ARG_CRITICAL
import com.android.systemui.dump.DumpManager.Companion.PRIORITY_ARG_HIGH
import com.android.systemui.dump.DumpManager.Companion.PRIORITY_ARG_NORMAL
import com.android.systemui.log.LogBuffer
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Oversees SystemUI's output during bug reports (and dumpsys in general)
 *
 * When a bug report is taken, SystemUI dumps various diagnostic information that we hope will be
 * useful for the eventual readers of the bug report. Code that wishes to participate in this dump
 * should register itself here.
 *
 * Dump output is split into two sections, CRITICAL and NORMAL. All dumpables registered via
 * [registerDumpable] appear in the CRITICAL section, while all [LogBuffer]s appear in the NORMAL
 * section (due to their length).
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
 * Finally, the following will simulate what we dump during the CRITICAL and NORMAL sections of a
 * bug report:
 * $ <invocation> bugreport-critical
 * $ <invocation> bugreport-normal
 * ```
 */
@Singleton
class DumpManager @Inject constructor(
    private val context: Context
) {
    private val dumpables: MutableMap<String, RegisteredDumpable<Dumpable>> = ArrayMap()
    private val buffers: MutableMap<String, RegisteredDumpable<LogBuffer>> = ArrayMap()

    /**
     * Register a dumpable to be called during a bug report. The dumpable will be called during the
     * CRITICAL section of the bug report, so don't dump an excessive amount of stuff here.
     *
     * @param name The name to register the dumpable under. This is typically the qualified class
     * name of the thing being dumped (getClass().getName()), but can be anything as long as it
     * doesn't clash with an existing registration.
     */
    @Synchronized
    fun registerDumpable(name: String, module: Dumpable) {
        if (RESERVED_NAMES.contains(name)) {
            throw IllegalArgumentException("'$name' is reserved")
        }

        if (!canAssignToNameLocked(name, module)) {
            throw IllegalArgumentException("'$name' is already registered")
        }

        dumpables[name] = RegisteredDumpable(name, module)
    }

    /**
     * Unregisters a previously-registered dumpable.
     */
    @Synchronized
    fun unregisterDumpable(name: String) {
        dumpables.remove(name)
    }

    /**
     * Register a [LogBuffer] to be dumped during a bug report.
     */
    @Synchronized
    fun registerBuffer(name: String, buffer: LogBuffer) {
        if (!canAssignToNameLocked(name, buffer)) {
            throw IllegalArgumentException("'$name' is already registered")
        }
        buffers[name] = RegisteredDumpable(name, buffer)
    }

    /**
     * Dump the diagnostics! Behavior can be controlled via [args].
     */
    @Synchronized
    fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
        Trace.beginSection("DumpManager#dump()")
        val start = SystemClock.uptimeMillis()

        val parsedArgs = try {
            parseArgs(args)
        } catch (e: ArgParseException) {
            pw.println(e.message)
            return
        }

        when (parsedArgs.dumpPriority) {
            PRIORITY_ARG_CRITICAL -> dumpCriticalLocked(fd, pw, parsedArgs)
            PRIORITY_ARG_NORMAL -> dumpNormalLocked(pw, parsedArgs)
            else -> dumpParameterizedLocked(fd, pw, parsedArgs)
        }

        pw.println()
        pw.println("Dump took ${SystemClock.uptimeMillis() - start}ms")
        Trace.endSection()
    }

    private fun dumpCriticalLocked(fd: FileDescriptor, pw: PrintWriter, args: ParsedArgs) {
        dumpDumpablesLocked(fd, pw, args)
        dumpConfig(pw)
    }

    private fun dumpNormalLocked(pw: PrintWriter, args: ParsedArgs) {
        dumpBuffersLocked(pw, args)
    }

    private fun dumpParameterizedLocked(fd: FileDescriptor, pw: PrintWriter, args: ParsedArgs) {
        when (args.command) {
            "bugreport-critical" -> dumpCriticalLocked(fd, pw, args)
            "bugreport-normal" -> dumpNormalLocked(pw, args)
            "dumpables" -> dumpDumpablesLocked(fd, pw, args)
            "buffers" -> dumpBuffersLocked(pw, args)
            else -> dumpTargetsLocked(args.nonFlagArgs, fd, pw, args)
        }
    }

    private fun dumpTargetsLocked(
        targets: List<String>,
        fd: FileDescriptor,
        pw: PrintWriter,
        args: ParsedArgs
    ) {
        if (targets.isEmpty()) {
            pw.println("Nothing to dump :(")
        } else {
            for (target in targets) {
                dumpTarget(target, fd, pw, args)
            }
        }
    }

    private fun dumpTarget(
        target: String,
        fd: FileDescriptor,
        pw: PrintWriter,
        args: ParsedArgs
    ) {
        if (target == "config") {
            dumpConfig(pw)
            return
        }

        for (dumpable in dumpables.values) {
            if (dumpable.name.endsWith(target)) {
                dumpDumpable(dumpable, fd, pw, args)
                return
            }
        }

        for (buffer in buffers.values) {
            if (buffer.name.endsWith(target)) {
                dumpBuffer(buffer, pw, args)
                return
            }
        }
    }

    private fun dumpDumpablesLocked(fd: FileDescriptor, pw: PrintWriter, args: ParsedArgs) {
        for (module in dumpables.values) {
            dumpDumpable(module, fd, pw, args)
        }
    }

    private fun dumpBuffersLocked(pw: PrintWriter, args: ParsedArgs) {
        for (buffer in buffers.values) {
            dumpBuffer(buffer, pw, args)
        }
    }

    private fun dumpDumpable(
        dumpable: RegisteredDumpable<Dumpable>,
        fd: FileDescriptor,
        pw: PrintWriter,
        args: ParsedArgs
    ) {
        pw.println()
        pw.println("${dumpable.name}:")
        pw.println("----------------------------------------------------------------------------")
        dumpable.dumpable.dump(fd, pw, args.rawArgs)
    }

    private fun dumpBuffer(
        buffer: RegisteredDumpable<LogBuffer>,
        pw: PrintWriter,
        args: ParsedArgs
    ) {
        pw.println()
        pw.println()
        pw.println("BUFFER ${buffer.name}:")
        pw.println("============================================================================")
        buffer.dumpable.dump(pw, args.tailLength)
    }

    private fun dumpConfig(pw: PrintWriter) {
        pw.println("SystemUiServiceComponents configuration:")
        pw.print("vendor component: ")
        pw.println(context.resources.getString(R.string.config_systemUIVendorServiceComponent))
        dumpServiceList(pw, "global", R.array.config_systemUIServiceComponents)
        dumpServiceList(pw, "per-user", R.array.config_systemUIServiceComponentsPerUser)
    }

    private fun dumpServiceList(pw: PrintWriter, type: String, resId: Int) {
        val services: Array<String>? = context.resources.getStringArray(resId)
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
                    "-t", "--tail" -> {
                        pArgs.tailLength = readArgument(iterator, "--tail") {
                            it.toInt()
                        }
                    }
                    else -> {
                        throw ArgParseException("Unknown flag: $arg")
                    }
                }
            }
        }

        if (mutArgs.isNotEmpty() && COMMANDS.contains(mutArgs[0])) {
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

    private fun canAssignToNameLocked(name: String, newDumpable: Any): Boolean {
        val existingDumpable = dumpables[name]?.dumpable ?: buffers[name]?.dumpable
        return existingDumpable == null || newDumpable == existingDumpable
    }

    companion object {
        const val PRIORITY_ARG = "--dump-priority"
        const val PRIORITY_ARG_CRITICAL = "CRITICAL"
        const val PRIORITY_ARG_HIGH = "HIGH"
        const val PRIORITY_ARG_NORMAL = "NORMAL"
    }
}

private val PRIORITY_OPTIONS =
        arrayOf(PRIORITY_ARG_CRITICAL, PRIORITY_ARG_HIGH, PRIORITY_ARG_NORMAL)

private val COMMANDS = arrayOf("bugreport-critical", "bugreport-normal", "buffers", "dumpables")

private val RESERVED_NAMES = arrayOf("config", *COMMANDS)

private data class RegisteredDumpable<T>(
    val name: String,
    val dumpable: T
)

private class ParsedArgs(
    val rawArgs: Array<String>,
    val nonFlagArgs: List<String>
) {
    var dumpPriority: String? = null
    var tailLength: Int = 0
    var command: String? = null
}

class ArgParseException(message: String) : Exception(message)