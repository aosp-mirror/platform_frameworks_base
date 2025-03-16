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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.res.mainResources
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.media.controls.ui.controller.MediaLocation
import com.android.systemui.media.controls.ui.controller.mediaHostStatesManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.qs.panels.data.repository.QSColumnsRepository
import com.android.systemui.qs.panels.data.repository.qsColumnsRepository
import com.android.systemui.res.R
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class QSColumnsViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            usingMediaInComposeFragment = true
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_infinite_grid_num_columns,
                SINGLE_SPLIT_SHADE_COLUMNS,
            )
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_dual_shade_num_columns,
                DUAL_SHADE_COLUMNS,
            )
            testCase.context.orCreateTestableResources.addOverride(
                R.integer.quick_settings_split_shade_num_columns,
                SINGLE_SPLIT_SHADE_COLUMNS,
            )
            qsColumnsRepository = QSColumnsRepository(mainResources, configurationRepository)
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun mediaLocationNull_singleOrSplit_alwaysSingleShadeColumns() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(null)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)

                makeMediaVisible(LOCATION_QQS, visible = true)
                makeMediaVisible(LOCATION_QS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)
            }
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun mediaLocationNull_dualShade_alwaysDualShadeColumns() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(null)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)

                makeMediaVisible(LOCATION_QQS, visible = true)
                makeMediaVisible(LOCATION_QS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)
            }
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun mediaLocationQS_dualShade_alwaysDualShadeColumns() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(LOCATION_QS)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)

                makeMediaVisible(LOCATION_QS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)
            }
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun mediaLocationQQS_dualShade_alwaysDualShadeColumns() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(LOCATION_QQS)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)

                makeMediaVisible(LOCATION_QQS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(DUAL_SHADE_COLUMNS)
            }
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun mediaLocationQS_singleOrSplit_halfColumnsOnCorrectConfigurationAndVisible() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(LOCATION_QS)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)

                makeMediaVisible(LOCATION_QS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS / 2)
            }
        }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun mediaLocationQQS_singleOrSplit_halfColumnsOnCorrectConfigurationAndVisible() =
        with(kosmos) {
            testScope.runTest {
                val underTest = qsColumnsViewModelFactory.create(LOCATION_QQS)
                underTest.activateIn(testScope)

                setConfigurationForMediaInRow(mediaInRow = false)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)

                setConfigurationForMediaInRow(mediaInRow = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS)

                makeMediaVisible(LOCATION_QQS, visible = true)
                runCurrent()

                assertThat(underTest.columns).isEqualTo(SINGLE_SPLIT_SHADE_COLUMNS / 2)
            }
        }

    companion object {
        private const val SINGLE_SPLIT_SHADE_COLUMNS = 4
        private const val DUAL_SHADE_COLUMNS = 2

        private fun Kosmos.makeMediaVisible(@MediaLocation location: Int, visible: Boolean) {
            mediaHostStatesManager.updateHostState(
                location,
                MediaHost.MediaHostStateHolder().apply { this.visible = visible },
            )
        }
    }
}
