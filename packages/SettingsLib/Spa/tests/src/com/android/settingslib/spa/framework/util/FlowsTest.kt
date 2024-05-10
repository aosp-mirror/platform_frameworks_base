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

package com.android.settingslib.spa.framework.util

import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.waitUntil
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowsTest {
    @Test
    fun mapItem() = runTest {
        val inputFlow = flowOf(listOf("A", "BB", "CCC"))

        val outputFlow = inputFlow.mapItem { it.length }

        assertThat(outputFlow.first()).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun asyncMapItem() = runTest {
        val inputFlow = flowOf(listOf("A", "BB", "CCC"))

        val outputFlow = inputFlow.asyncMapItem { it.length }

        assertThat(outputFlow.first()).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun filterItem() = runTest {
        val inputFlow = flowOf(listOf("A", "BB", "CCC"))

        val outputFlow = inputFlow.filterItem { it.length >= 2 }

        assertThat(outputFlow.first()).containsExactly("BB", "CCC").inOrder()
    }

    @Test
    fun waitFirst_otherFlowEmpty() = runTest {
        val mainFlow = flowOf("A")
        val otherFlow = emptyFlow<String>()

        val outputFlow = mainFlow.waitFirst(otherFlow)

        assertThat(outputFlow.count()).isEqualTo(0)
    }

    @Test
    fun waitFirst_otherFlowOneValue() = runTest {
        val mainFlow = flowOf("A")
        val otherFlow = flowOf("B")

        val outputFlow = mainFlow.waitFirst(otherFlow)

        assertThat(outputFlow.toList()).containsExactly("A")
    }

    @Test
    fun waitFirst_otherFlowTwoValues() = runTest {
        val mainFlow = flowOf("A")
        val otherFlow = flowOf("B", "B")

        val outputFlow = mainFlow.waitFirst(otherFlow)

        assertThat(outputFlow.toList()).containsExactly("A")
    }

    @Test
    fun collectLatestWithLifecycle() {
        val mainFlow = flowOf("A")
        var actual: String? = null

        mainFlow.collectLatestWithLifecycle(TestLifecycleOwner()) {
            actual = it
        }

        waitUntil { actual == "A" }
    }
}
