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
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.BooleanFlowOperators.and
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.BooleanFlowOperators.or
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
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
            val result by collectLastValue(and(TRUE, TRUE))
            assertThat(result).isTrue()
        }

    @Test
    fun and_anyFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(and(TRUE, FALSE, TRUE))
            assertThat(result).isFalse()
        }

    @Test
    fun and_allFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(and(FALSE, FALSE, FALSE))
            assertThat(result).isFalse()
        }

    @Test
    fun or_allTrue_returnsTrue() =
        testScope.runTest {
            val result by collectLastValue(or(TRUE, TRUE))
            assertThat(result).isTrue()
        }

    @Test
    fun or_anyTrue_returnsTrue() =
        testScope.runTest {
            val result by collectLastValue(or(FALSE, TRUE, FALSE))
            assertThat(result).isTrue()
        }

    @Test
    fun or_allFalse_returnsFalse() =
        testScope.runTest {
            val result by collectLastValue(or(FALSE, FALSE, FALSE))
            assertThat(result).isFalse()
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
