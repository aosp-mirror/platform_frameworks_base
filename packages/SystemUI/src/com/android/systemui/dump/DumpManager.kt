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

import android.util.ArrayMap
import com.android.systemui.Dumpable
import com.android.systemui.ProtoDumpable
import com.android.systemui.dump.nano.SystemUIProtoDump
import com.android.systemui.plugins.log.LogBuffer
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintains a registry of things that should be dumped when a bug report is taken
 *
 * When a bug report is taken, SystemUI dumps various diagnostic information that we hope will be
 * useful for the eventual readers of the bug report. Code that wishes to participate in this dump
 * should register itself here.
 *
 * See [DumpHandler] for more information on how and when this information is dumped.
 */
@Singleton
open class DumpManager @Inject constructor() {
    private val dumpables: MutableMap<String, RegisteredDumpable<Dumpable>> = ArrayMap()
    private val buffers: MutableMap<String, RegisteredDumpable<LogBuffer>> = ArrayMap()

    /**
     * Registers a dumpable to be called during the CRITICAL section of the bug report.
     *
     * The CRITICAL section gets very high priority during a dump, but also a very limited amount of
     * time to do the dumping. So, please don't dump an excessive amount of stuff using CRITICAL.
     *
     * See [registerDumpable].
     */
    fun registerCriticalDumpable(name: String, module: Dumpable) {
        registerDumpable(name, module, DumpPriority.CRITICAL)
    }

    /** See [registerNormalDumpable]. */
    fun registerNormalDumpable(module: Dumpable) {
        registerNormalDumpable(module::class.java.simpleName, module)
    }

    /**
     * Registers a dumpable to be called during the NORMAL section of the bug report.
     *
     * The NORMAL section gets a lower priority during a dump, but also more time. This should be
     * used by [LogBuffer] instances, [ProtoDumpable] instances, and any [Dumpable] instances that
     * dump a lot of information.
     */
    fun registerNormalDumpable(name: String, module: Dumpable) {
        registerDumpable(name, module, DumpPriority.NORMAL)
    }

    /**
     * Register a dumpable to be called during a bug report.
     *
     * @param name The name to register the dumpable under. This is typically the qualified class
     * name of the thing being dumped (getClass().getName()), but can be anything as long as it
     * doesn't clash with an existing registration.
     * @param priority the priority level of this dumpable, which affects at what point in the bug
     * report this gets dump. By default, the dumpable will be called during the CRITICAL section of
     * the bug report, so don't dump an excessive amount of stuff here.
     *
     * TODO(b/259973758): Replace all calls to this method with calls to [registerCriticalDumpable]
     * or [registerNormalDumpable] instead.
     */
    @Synchronized
    @JvmOverloads
    @Deprecated("Use registerCriticalDumpable or registerNormalDumpable instead")
    fun registerDumpable(
        name: String,
        module: Dumpable,
        priority: DumpPriority = DumpPriority.CRITICAL,
    ) {
        if (!canAssignToNameLocked(name, module)) {
            throw IllegalArgumentException("'$name' is already registered")
        }

        dumpables[name] = RegisteredDumpable(name, module, priority)
    }

    /**
     * Same as the above override, but automatically uses the simple class name as the dumpable
     * name.
     */
    @Synchronized
    fun registerDumpable(module: Dumpable) {
        registerDumpable(module::class.java.simpleName, module)
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

        // All buffers must be priority NORMAL, not CRITICAL, because they often contain a lot of
        // data.
        buffers[name] = RegisteredDumpable(name, buffer, DumpPriority.NORMAL)
    }

    /**
     * Dumps the first dumpable or buffer whose registered name ends with [target]
     */
    @Synchronized
    fun dumpTarget(
        target: String,
        pw: PrintWriter,
        args: Array<String>,
        tailLength: Int,
    ) {
        for (dumpable in dumpables.values) {
            if (dumpable.name.endsWith(target)) {
                dumpDumpable(dumpable, pw, args)
                return
            }
        }

        for (buffer in buffers.values) {
            if (buffer.name.endsWith(target)) {
                dumpBuffer(buffer, pw, tailLength)
                return
            }
        }
    }

    @Synchronized
    fun dumpProtoTarget(
        target: String,
        protoDump: SystemUIProtoDump,
        args: Array<String>
    ) {
        for (dumpable in dumpables.values) {
            if (dumpable.dumpable is ProtoDumpable && dumpable.name.endsWith(target)) {
                dumpProtoDumpable(dumpable.dumpable, protoDump, args)
                return
            }
        }
    }

    @Synchronized
    fun dumpProtoDumpables(
        systemUIProtoDump: SystemUIProtoDump,
        args: Array<String>
    ) {
        for (dumpable in dumpables.values) {
            if (dumpable.dumpable is ProtoDumpable) {
                dumpProtoDumpable(
                    dumpable.dumpable,
                    systemUIProtoDump,
                    args
                )
            }
        }
    }

    /**
     * Dumps all registered dumpables with critical priority to [pw]
     */
    @Synchronized
    fun dumpCritical(pw: PrintWriter, args: Array<String>) {
        for (dumpable in dumpables.values) {
            if (dumpable.priority == DumpPriority.CRITICAL) {
                dumpDumpable(dumpable, pw, args)
            }
        }
    }

    /**
     * To [pw], dumps (1) all registered dumpables with normal priority; and (2) all [LogBuffer]s.
     */
    @Synchronized
    fun dumpNormal(pw: PrintWriter, args: Array<String>, tailLength: Int = 0) {
        for (dumpable in dumpables.values) {
            if (dumpable.priority == DumpPriority.NORMAL) {
                dumpDumpable(dumpable, pw, args)
            }
        }

        for (buffer in buffers.values) {
            dumpBuffer(buffer, pw, tailLength)
        }
    }

    /**
     * Dump all the instances of [Dumpable].
     */
    @Synchronized
    fun dumpDumpables(pw: PrintWriter, args: Array<String>) {
        for (module in dumpables.values) {
            dumpDumpable(module, pw, args)
        }
    }

    /**
     * Dumps the names of all registered dumpables (one per line)
     */
    @Synchronized
    fun listDumpables(pw: PrintWriter) {
        for (module in dumpables.values) {
            pw.println(module.name)
        }
    }

    /**
     * Dumps all registered [LogBuffer]s to [pw]
     */
    @Synchronized
    fun dumpBuffers(pw: PrintWriter, tailLength: Int) {
        for (buffer in buffers.values) {
            dumpBuffer(buffer, pw, tailLength)
        }
    }

    /**
     * Dumps the names of all registered buffers (one per line)
     */
    @Synchronized
    fun listBuffers(pw: PrintWriter) {
        for (buffer in buffers.values) {
            pw.println(buffer.name)
        }
    }

    @Synchronized
    fun freezeBuffers() {
        for (buffer in buffers.values) {
            buffer.dumpable.freeze()
        }
    }

    @Synchronized
    fun unfreezeBuffers() {
        for (buffer in buffers.values) {
            buffer.dumpable.unfreeze()
        }
    }

    private fun dumpDumpable(
        dumpable: RegisteredDumpable<Dumpable>,
        pw: PrintWriter,
        args: Array<String>
    ) {
        pw.println()
        pw.println("${dumpable.name}:")
        pw.println("----------------------------------------------------------------------------")
        dumpable.dumpable.dump(pw, args)
    }

    private fun dumpBuffer(
        buffer: RegisteredDumpable<LogBuffer>,
        pw: PrintWriter,
        tailLength: Int
    ) {
        pw.println()
        pw.println()
        pw.println("BUFFER ${buffer.name}:")
        pw.println("============================================================================")
        buffer.dumpable.dump(pw, tailLength)
    }

    private fun dumpProtoDumpable(
        protoDumpable: ProtoDumpable,
        systemUIProtoDump: SystemUIProtoDump,
        args: Array<String>
    ) {
        protoDumpable.dumpProto(systemUIProtoDump, args)
    }

    private fun canAssignToNameLocked(name: String, newDumpable: Any): Boolean {
        val existingDumpable = dumpables[name]?.dumpable ?: buffers[name]?.dumpable
        return existingDumpable == null || newDumpable == existingDumpable
    }
}

private data class RegisteredDumpable<T>(
    val name: String,
    val dumpable: T,
    val priority: DumpPriority,
)

/**
 * The priority level for a given dumpable, which affects at what point in the bug report this gets
 * dumped.
 */
enum class DumpPriority {
    CRITICAL,
    NORMAL,
}
