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

package com.android.systemui.qs.tiles.impl.reducebrightness.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.reduceBrightColorsController
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class ReduceBrightColorsTileDataInteractorTest : SysuiTestCase() {

    private val isAvailable = true
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val reduceBrightColorsController = kosmos.reduceBrightColorsController
    private val underTest: ReduceBrightColorsTileDataInteractor =
        ReduceBrightColorsTileDataInteractor(
            testScope.testScheduler,
            isAvailable,
            reduceBrightColorsController
        )

    @Test
    fun alwaysAvailable() =
        testScope.runTest {
            val availability = underTest.availability(TEST_USER).toCollection(mutableListOf())

            assertThat(availability).hasSize(1)
            assertThat(availability.last()).isEqualTo(isAvailable)
        }

    @Test
    fun dataMatchesTheRepository() =
        testScope.runTest {
            val dataList: List<ReduceBrightColorsTileModel> by
                collectValues(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            reduceBrightColorsController.isReduceBrightColorsActivated = true
            runCurrent()

            reduceBrightColorsController.isReduceBrightColorsActivated = false
            runCurrent()

            assertThat(dataList).hasSize(3)
            assertThat(dataList.map { it.isEnabled }).isEqualTo(listOf(false, true, false))
        }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
