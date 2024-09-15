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

package com.android.systemui.util.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class BooleanFlowOperatorsTest : SysuiTestCase() {

    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    @Test
    fun and_allTrue_returnsTrue() =
        testScope.runTest {
            val result by collectLastValue(allOf(TRUE, TRUE))
            assertThat(result).isTrue()
        }

    @Test
    fun and_anyFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(allOf(TRUE, FALSE, TRUE))
            assertThat(result).isFalse()
        }

    @Test
    fun and_allFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(allOf(FALSE, FALSE, FALSE))
            assertThat(result).isFalse()
        }

    @Test
    fun and_onlyEmitsWhenValueChanges() =
        testScope.runTest {
            val flow1 = MutableStateFlow(false)
            val flow2 = MutableStateFlow(false)
            val values by collectValues(allOf(flow1, flow2))

            assertThat(values).containsExactly(false)
            flow1.value = true
            // Overall value is still false, we should not have emitted again.
            assertThat(values).containsExactly(false)
            flow2.value = true
            assertThat(values).containsExactly(false, true).inOrder()
        }

    @Test
    fun or_allTrue_returnsTrue() =
        testScope.runTest {
            val result by collectLastValue(anyOf(TRUE, TRUE))
            assertThat(result).isTrue()
        }

    @Test
    fun or_anyTrue_returnsTrue() =
        testScope.runTest {
            val result by collectLastValue(anyOf(FALSE, TRUE, FALSE))
            assertThat(result).isTrue()
        }

    @Test
    fun or_allFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(anyOf(FALSE, FALSE, FALSE))
            assertThat(result).isFalse()
        }

    @Test
    fun or_onlyEmitsWhenValueChanges() =
        testScope.runTest {
            val flow1 = MutableStateFlow(false)
            val flow2 = MutableStateFlow(false)
            val values by collectValues(anyOf(flow1, flow2))

            assertThat(values).containsExactly(false)
            flow1.value = true
            assertThat(values).containsExactly(false, true).inOrder()
            flow2.value = true
            assertThat(values).containsExactly(false, true).inOrder()
        }

    @Test
    fun not_true_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(not(TRUE))
            assertThat(result).isFalse()
        }

    @Test
    fun not_false_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(not(FALSE))
            assertThat(result).isTrue()
        }

    private companion object {
        val TRUE: Flow<Boolean>
            get() = flowOf(true)
        val FALSE: Flow<Boolean>
            get() = flowOf(false)
    }
}
