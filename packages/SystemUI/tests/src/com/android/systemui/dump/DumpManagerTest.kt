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

import androidx.test.filters.SmallTest
import com.android.systemui.Dumpable
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
class DumpManagerTest : SysuiTestCase() {

    @Mock private lateinit var dumpable1: Dumpable
    @Mock private lateinit var dumpable2: Dumpable
    @Mock private lateinit var dumpable3: Dumpable

    @Mock private lateinit var buffer1: LogBuffer
    @Mock private lateinit var buffer2: LogBuffer

    @Mock private lateinit var table1: TableLogBuffer
    @Mock private lateinit var table2: TableLogBuffer

    private val dumpManager = DumpManager()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testRegisterUnregister_dumpables() {
        // GIVEN a variety of registered dumpables
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)

        // WHEN the collection is requested
        var dumpables = dumpManager.getDumpables().map { it.dumpable }

        // THEN it contains the registered entries
        assertThat(dumpables).containsExactly(dumpable1, dumpable2, dumpable3)

        // WHEN the dumpables are unregistered
        dumpManager.unregisterDumpable("dumpable2")
        dumpManager.unregisterDumpable("dumpable3")

        // WHEN the dumpable collection is requests
        dumpables = dumpManager.getDumpables().map { it.dumpable }

        // THEN it contains only the currently-registered entry
        assertThat(dumpables).containsExactly(dumpable1)
    }

    @Test
    fun testRegister_buffers() {
        // GIVEN a set of registered buffers
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN the collection is requested
        val dumpables = dumpManager.getLogBuffers().map { it.buffer }

        // THEN it contains the registered entries
        assertThat(dumpables).containsExactly(buffer1, buffer2)
    }

    @Test
    fun testRegister_tableLogBuffers() {
        // GIVEN a set of registered buffers
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN the collection is requested
        val tables = dumpManager.getTableLogBuffers().map { it.table }

        // THEN it contains the registered entries
        assertThat(tables).containsExactly(table1, table2)
    }

    @Test
    fun registerDumpable_throwsWhenNameCannotBeAssigned() {
        // GIVEN dumpable1 and buffer1 and table1 are registered
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerTableLogBuffer("table1", table1)

        // THEN an exception is thrown when trying to re-register a new dumpable under the same key
        assertThrows(IllegalArgumentException::class.java) {
            dumpManager.registerCriticalDumpable("dumpable1", dumpable2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dumpManager.registerBuffer("buffer1", buffer2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dumpManager.registerTableLogBuffer("table1", table2)
        }
    }

    @Test
    fun registerDumpable_doesNotThrowWhenReRegistering() {
        // GIVEN dumpable1 and buffer1 are registered
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerBuffer("buffer1", buffer1)

        // THEN no exception is thrown when trying to re-register a new dumpable under the same key
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerBuffer("buffer1", buffer1)

        // No exception thrown
    }

    @Test
    fun getDumpables_returnsSafeCollection() {
        // GIVEN a variety of registered dumpables
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)

        // WHEN the collection is retrieved
        val dumpables = dumpManager.getDumpables()

        // WHEN the collection changes from underneath
        dumpManager.unregisterDumpable("dumpable1")
        dumpManager.unregisterDumpable("dumpable2")
        dumpManager.unregisterDumpable("dumpable3")

        // THEN new collections are empty
        assertThat(dumpManager.getDumpables()).isEmpty()

        // AND the collection is still safe to use
        assertThat(dumpables).hasSize(3)
    }

    @Test
    fun getBuffers_returnsSafeCollection() {
        // GIVEN a set of registered buffers
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN the collection is requested
        val buffers = dumpManager.getLogBuffers()

        // WHEN the collection changes
        dumpManager.registerBuffer("buffer3", buffer1)

        // THEN the new entry is represented
        assertThat(dumpManager.getLogBuffers()).hasSize(3)

        // AND the previous collection is unchanged
        assertThat(buffers).hasSize(2)
    }

    @Test
    fun getTableBuffers_returnsSafeCollection() {
        // GIVEN a set of registered buffers
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN the collection is requested
        val tables = dumpManager.getTableLogBuffers()

        // WHEN the collection changes
        dumpManager.registerTableLogBuffer("table3", table1)

        // THEN the new entry is represented
        assertThat(dumpManager.getTableLogBuffers()).hasSize(3)

        // AND the previous collection is unchanged
        assertThat(tables).hasSize(2)
    }
}
