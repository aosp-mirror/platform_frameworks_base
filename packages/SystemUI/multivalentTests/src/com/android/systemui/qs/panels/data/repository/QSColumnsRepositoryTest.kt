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

package com.android.systemui.qs.panels.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSColumnsRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var underTest: QSColumnsRepository

    @Before
    fun setUp() {
        underTest = with(kosmos) { qsColumnsRepository }
    }

    @Test
    fun configChanges_triggerColumnsUpdate() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.columns)

                setColumnsInConfig(4)
                assertThat(latest).isEqualTo(4)

                setColumnsInConfig(8)
                assertThat(latest).isEqualTo(8)
            }
        }

    @Test
    fun withDualShade_returnsCorrectValue() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.dualShadeColumns)

                assertThat(latest).isEqualTo(4)
            }
        }

    @Test
    fun withSplitShade_returnsCorrectValue() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.splitShadeColumns)
                fakeShadeRepository.setShadeLayoutWide(true)

                assertThat(latest).isEqualTo(4)
            }
        }

    private fun setColumnsInConfig(
        columns: Int,
        id: Int = R.integer.quick_settings_infinite_grid_num_columns,
    ) =
        with(kosmos) {
            testCase.context.orCreateTestableResources.addOverride(id, columns)
            fakeConfigurationRepository.onConfigurationChange()
        }
}
