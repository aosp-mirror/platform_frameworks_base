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

import com.android.systemui.Dumpable
import com.android.systemui.ProtoDumpable
import com.android.systemui.dump.DumpsysEntry.DumpableEntry
import com.android.systemui.dump.DumpsysEntry.LogBufferEntry
import com.android.systemui.dump.DumpsysEntry.TableLogBufferEntry
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import java.util.TreeMap
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
    // NOTE: Using TreeMap ensures that iteration is in a predictable & alphabetical order.
    private val dumpables: MutableMap<String, DumpableEntry> = TreeMap()
    private val buffers: MutableMap<String, LogBufferEntry> = TreeMap()
    private val tableLogBuffers: MutableMap<String, TableLogBufferEntry> = TreeMap()

    /** See [registerCriticalDumpable]. */
    fun registerCriticalDumpable(module: Dumpable) {
        registerCriticalDumpable(module::class.java.canonicalName, module)
    }

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
        registerNormalDumpable(module::class.java.canonicalName, module)
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
     *   name of the thing being dumped (getClass().getName()), but can be anything as long as it
     *   doesn't clash with an existing registration.
     * @param priority the priority level of this dumpable, which affects at what point in the bug
     *   report this gets dump. By default, the dumpable will be called during the CRITICAL section
     *   of the bug report, so don't dump an excessive amount of stuff here.
     *
     * TODO(b/259973758): Replace all calls to this method with calls to [registerCriticalDumpable]
     *   or [registerNormalDumpable] instead.
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

        dumpables[name] = DumpableEntry(module, name, priority)
    }

    /**
     * Same as the above override, but automatically uses the canonical class name as the dumpable
     * name.
     */
    @Synchronized
    fun registerDumpable(module: Dumpable) {
        registerDumpable(module::class.java.canonicalName, module)
    }

    /** Unregisters a previously-registered dumpable. */
    @Synchronized
    fun unregisterDumpable(name: String) {
        dumpables.remove(name)
    }

    /** Register a [LogBuffer] to be dumped during a bug report. */
    @Synchronized
    fun registerBuffer(name: String, buffer: LogBuffer) {
        if (!canAssignToNameLocked(name, buffer)) {
            throw IllegalArgumentException("'$name' is already registered")
        }

        buffers[name] = LogBufferEntry(buffer, name)
    }

    /** Register a [TableLogBuffer] to be dumped during a bugreport */
    @Synchronized
    fun registerTableLogBuffer(name: String, buffer: TableLogBuffer) {
        if (!canAssignToNameLocked(name, buffer)) {
            throw IllegalArgumentException("'$name' is already registered")
        }

        // All buffers must be priority NORMAL, not CRITICAL, because they often contain a lot of
        // data.
        tableLogBuffers[name] = TableLogBufferEntry(buffer, name)
    }

    @Synchronized fun getDumpables(): Collection<DumpableEntry> = dumpables.values.toList()

    @Synchronized fun getLogBuffers(): Collection<LogBufferEntry> = buffers.values.toList()

    @Synchronized
    fun getTableLogBuffers(): Collection<TableLogBufferEntry> = tableLogBuffers.values.toList()

    @Synchronized
    fun freezeBuffers() {
        for (buffer in buffers.values) {
            buffer.buffer.freeze()
        }
    }

    @Synchronized
    fun unfreezeBuffers() {
        for (buffer in buffers.values) {
            buffer.buffer.unfreeze()
        }
    }

    private fun canAssignToNameLocked(name: String, newDumpable: Any): Boolean {
        val existingDumpable =
            dumpables[name]?.dumpable ?: buffers[name]?.buffer ?: tableLogBuffers[name]?.table
        return existingDumpable == null || newDumpable == existingDumpable
    }
}

/**
 * The priority level for a given dumpable, which affects at what point in the bug report this gets
 * dumped.
 */
enum class DumpPriority {
    CRITICAL,
    NORMAL,
}
