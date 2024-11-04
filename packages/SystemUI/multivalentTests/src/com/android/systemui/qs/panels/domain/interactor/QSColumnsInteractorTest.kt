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

package com.android.systemui.qs.panels.domain.interactor

import android.content.res.mainResources
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.QSColumnsRepository
import com.android.systemui.qs.panels.data.repository.qsColumnsRepository
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSColumnsInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_infinite_grid_num_columns,
                1,
            )
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_dual_shade_num_columns,
                2,
            )
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_split_shade_num_columns,
                3,
            )
            qsColumnsRepository = QSColumnsRepository(mainResources, configurationRepository)
        }
    private lateinit var underTest: QSColumnsInteractor

    @Before
    fun setUp() {
        underTest = with(kosmos) { qsColumnsInteractor }
    }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun withSingleShade_returnsCorrectValue() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.columns)

                assertThat(latest).isEqualTo(1)
            }
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun withDualShade_returnsCorrectValue() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.columns)

                assertThat(latest).isEqualTo(2)
            }
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun withSplitShade_returnsCorrectValue() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.columns)

                fakeShadeRepository.setShadeLayoutWide(true)

                assertThat(latest).isEqualTo(3)
            }
        }
}
