/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.brightness

import androidx.test.filters.SmallTest
import com.android.internal.os.Clock
import com.android.server.display.brightness.LightSensorController.AmbientLightRingBuffer
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock


private const val BUFFER_INITIAL_CAPACITY = 3

@SmallTest
class AmbientLightRingBufferTest {

    private val buffer = AmbientLightRingBuffer(BUFFER_INITIAL_CAPACITY, mock<Clock>())

    @Test
    fun `test created empty`() {
        assertThat(buffer.size()).isEqualTo(0)
    }

    @Test
    fun `test push to empty buffer`() {
        buffer.push(1000, 0.5f)

        assertThat(buffer.size()).isEqualTo(1)
        assertThat(buffer.getLux(0)).isEqualTo(0.5f)
        assertThat(buffer.getTime(0)).isEqualTo(1000)
    }

    @Test
    fun `test prune keeps youngest outside horizon and sets time to horizon`() {
        buffer.push(1000, 0.5f)
        buffer.push(2000, 0.6f)
        buffer.push(3000, 0.7f)

        buffer.prune(2500)

        assertThat(buffer.size()).isEqualTo(2)

        assertThat(buffer.getLux(0)).isEqualTo(0.6f)
        assertThat(buffer.getTime(0)).isEqualTo(2500)
    }

    @Test
    fun `test prune keeps inside horizon`() {
        buffer.push(1000, 0.5f)
        buffer.push(2000, 0.6f)
        buffer.push(3000, 0.7f)

        buffer.prune(2500)

        assertThat(buffer.size()).isEqualTo(2)

        assertThat(buffer.getLux(1)).isEqualTo(0.7f)
        assertThat(buffer.getTime(1)).isEqualTo(3000)
    }


    @Test
    fun `test pushes correctly after prune`() {
        buffer.push(1000, 0.5f)
        buffer.push(2000, 0.6f)
        buffer.push(3000, 0.7f)
        buffer.prune(2500)

        buffer.push(4000, 0.8f)

        assertThat(buffer.size()).isEqualTo(3)

        assertThat(buffer.getLux(0)).isEqualTo(0.6f)
        assertThat(buffer.getTime(0)).isEqualTo(2500)
        assertThat(buffer.getLux(1)).isEqualTo(0.7f)
        assertThat(buffer.getTime(1)).isEqualTo(3000)
        assertThat(buffer.getLux(2)).isEqualTo(0.8f)
        assertThat(buffer.getTime(2)).isEqualTo(4000)
    }

    @Test
    fun `test increase buffer size`() {
        buffer.push(1000, 0.5f)
        buffer.push(2000, 0.6f)
        buffer.push(3000, 0.7f)

        buffer.push(4000, 0.8f)

        assertThat(buffer.size()).isEqualTo(4)

        assertThat(buffer.getLux(0)).isEqualTo(0.5f)
        assertThat(buffer.getTime(0)).isEqualTo(1000)
        assertThat(buffer.getLux(1)).isEqualTo(0.6f)
        assertThat(buffer.getTime(1)).isEqualTo(2000)
        assertThat(buffer.getLux(2)).isEqualTo(0.7f)
        assertThat(buffer.getTime(2)).isEqualTo(3000)
        assertThat(buffer.getLux(3)).isEqualTo(0.8f)
        assertThat(buffer.getTime(3)).isEqualTo(4000)
    }

    @Test
    fun `test buffer clear`() {
        buffer.push(1000, 0.5f)
        buffer.push(2000, 0.6f)
        buffer.push(3000, 0.7f)

        buffer.clear()

        assertThat(buffer.size()).isEqualTo(0)
        assertThrows(ArrayIndexOutOfBoundsException::class.java) {
            buffer.getLux(0)
        }
    }
}