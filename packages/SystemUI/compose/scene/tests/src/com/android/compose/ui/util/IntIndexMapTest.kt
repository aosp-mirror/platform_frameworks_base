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

package com.android.compose.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntIndexMapTest {

    @Test
    fun testSetGetFirstAndSize() {
        val map = IntIndexedMap<String>()

        // Write first element at 10
        map[10] = "1"
        assertThat(map[10]).isEqualTo("1")
        assertThat(map.size).isEqualTo(1)
        assertThat(map.first()).isEqualTo("1")

        // Write same element to same index
        map[10] = "1"
        assertThat(map[10]).isEqualTo("1")
        assertThat(map.size).isEqualTo(1)

        // Writing into larger index
        map[12] = "2"
        assertThat(map[12]).isEqualTo("2")
        assertThat(map.size).isEqualTo(2)
        assertThat(map.first()).isEqualTo("1")

        // Overwriting existing index
        map[10] = "3"
        assertThat(map[10]).isEqualTo("3")
        assertThat(map.size).isEqualTo(2)
        assertThat(map.first()).isEqualTo("3")

        // Writing into smaller index
        map[0] = "4"
        assertThat(map[0]).isEqualTo("4")
        assert(map.size == 3)
        assertThat(map.first()).isEqualTo("4")

        // Writing null into non-null index
        map[0] = null
        assertThat(map[0]).isEqualTo(null)
        assertThat(map.size).isEqualTo(2)
        assertThat(map.first()).isEqualTo("3")

        // Writing null into smaller null index
        map[1] = null
        assertThat(map[1]).isEqualTo(null)
        assertThat(map.size).isEqualTo(2)

        // Writing null into larger null index
        map[15] = null
        assertThat(map[15]).isEqualTo(null)
        assertThat(map.size).isEqualTo(2)

        // Remove existing element
        map.remove(12)
        assertThat(map[12]).isEqualTo(null)
        assertThat(map.size).isEqualTo(1)

        // Remove non-existing element
        map.remove(17)
        assertThat(map[17]).isEqualTo(null)
        assertThat(map.size).isEqualTo(1)

        // Remove all elements
        assertThat(map.first()).isEqualTo("3")
        map.remove(10)
        map.remove(10)
        map.remove(0)
        assertThat(map.size).isEqualTo(0)
        assertThat(map[10]).isEqualTo(null)
        assertThat(map.size).isEqualTo(0)
    }
}
