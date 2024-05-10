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

package com.android.settingslib.spa.framework.compose

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverridableFlowTest {

    @Test
    fun noOverride() = runTest {
        val overridableFlow = OverridableFlow(flowOf(true))

        launch {
            val values = collectValues(overridableFlow.flow)
            assertThat(values).containsExactly(true)
        }
    }

    @Test
    fun whenOverride() = runTest {
        val overridableFlow = OverridableFlow(flowOf(true))

        overridableFlow.override(false)

        launch {
            val values = collectValues(overridableFlow.flow)
            assertThat(values).containsExactly(true, false).inOrder()
        }
    }

    private suspend fun <T> collectValues(flow: Flow<T>): List<T> = withTimeout(500) {
        val flowValues = mutableListOf<T>()
        flow.toList(flowValues)
        flowValues
    }
}
