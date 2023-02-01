/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.log.table

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBufferHelper.Companion.adjustMaxSize
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

@SysUISingleton
class TableLogBufferFactory
@Inject
constructor(
    private val dumpManager: DumpManager,
    private val systemClock: SystemClock,
) {
    private val existingBuffers = mutableMapOf<String, TableLogBuffer>()

    /**
     * Creates a new [TableLogBuffer]. This method should only be called from static contexts, where
     * it is guaranteed only to be created one time. See [getOrCreate] for a cache-aware method of
     * obtaining a buffer.
     *
     * @param name a unique table name
     * @param maxSize the buffer max size. See [adjustMaxSize]
     *
     * @return a new [TableLogBuffer] registered with [DumpManager]
     */
    fun create(
        name: String,
        maxSize: Int,
    ): TableLogBuffer {
        val tableBuffer = TableLogBuffer(adjustMaxSize(maxSize), name, systemClock)
        dumpManager.registerNormalDumpable(name, tableBuffer)
        return tableBuffer
    }

    /**
     * Log buffers are retained indefinitely by [DumpManager], so that they can be represented in
     * bugreports. Because of this, many of them are created statically in the Dagger graph.
     *
     * In the case where you have to create a logbuffer with a name only known at runtime, this
     * method can be used to lazily create a table log buffer which is then cached for reuse.
     *
     * @return a [TableLogBuffer] suitable for reuse
     */
    fun getOrCreate(
        name: String,
        maxSize: Int,
    ): TableLogBuffer =
        existingBuffers.getOrElse(name) {
            val buffer = create(name, maxSize)
            existingBuffers[name] = buffer
            buffer
        }
}
