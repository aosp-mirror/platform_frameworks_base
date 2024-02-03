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

package com.android.systemui.dump

import com.android.systemui.Dumpable
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer

/**
 * A DumpsysEntry is a named, registered entry tracked by [DumpManager] which can be addressed and
 * used both in a bugreport / dumpsys invocation or in an individual CLI implementation.
 *
 * The idea here is that we define every type that [DumpManager] knows about and defines the minimum
 * shared interface between each type. So far, just [name] and [priority]. This way, [DumpManager]
 * can just store them in separate maps and do the minimal amount of work to discriminate between
 * them.
 *
 * Individual consumers can request these participants in a list via the relevant get* methods on
 * [DumpManager]
 */
sealed interface DumpsysEntry {
    val name: String
    val priority: DumpPriority

    data class DumpableEntry(
        val dumpable: Dumpable,
        override val name: String,
        override val priority: DumpPriority,
    ) : DumpsysEntry

    data class LogBufferEntry(
        val buffer: LogBuffer,
        override val name: String,
    ) : DumpsysEntry {
        // All buffers must be priority NORMAL, not CRITICAL, because they often contain a lot of
        // data.
        override val priority: DumpPriority = DumpPriority.NORMAL
    }

    data class TableLogBufferEntry(
        val table: TableLogBuffer,
        override val name: String,
    ) : DumpsysEntry {
        // All buffers must be priority NORMAL, not CRITICAL, because they often contain a lot of
        // data.
        override val priority: DumpPriority = DumpPriority.NORMAL
    }
}
