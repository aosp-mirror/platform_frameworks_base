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

package com.android.systemui.log.table

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class TableLogBufferFactoryTest : SysuiTestCase() {
    private val dumpManager: DumpManager = mock()
    private val systemClock = FakeSystemClock()
    private val underTest = TableLogBufferFactory(dumpManager, systemClock)

    @Test
    fun `create - always creates new instance`() {
        val b1 = underTest.create(NAME_1, SIZE)
        val b1_copy = underTest.create(NAME_1, SIZE)
        val b2 = underTest.create(NAME_2, SIZE)
        val b2_copy = underTest.create(NAME_2, SIZE)

        assertThat(b1).isNotSameInstanceAs(b1_copy)
        assertThat(b1).isNotSameInstanceAs(b2)
        assertThat(b2).isNotSameInstanceAs(b2_copy)
    }

    @Test
    fun `getOrCreate - reuses instance`() {
        val b1 = underTest.getOrCreate(NAME_1, SIZE)
        val b1_copy = underTest.getOrCreate(NAME_1, SIZE)
        val b2 = underTest.getOrCreate(NAME_2, SIZE)
        val b2_copy = underTest.getOrCreate(NAME_2, SIZE)

        assertThat(b1).isSameInstanceAs(b1_copy)
        assertThat(b2).isSameInstanceAs(b2_copy)
        assertThat(b1).isNotSameInstanceAs(b2)
        assertThat(b1_copy).isNotSameInstanceAs(b2_copy)
    }

    companion object {
        const val NAME_1 = "name 1"
        const val NAME_2 = "name 2"

        const val SIZE = 8
    }
}
