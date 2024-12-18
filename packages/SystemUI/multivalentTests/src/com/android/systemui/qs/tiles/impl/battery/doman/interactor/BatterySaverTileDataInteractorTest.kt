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

package com.android.systemui.qs.tiles.impl.battery.doman.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.battery.domain.interactor.BatterySaverTileDataInteractor
import com.android.systemui.utils.leaks.FakeBatteryController
import com.google.common.truth.Truth
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
class BatterySaverTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val batteryController = FakeBatteryController(LeakCheck())
    private val testUser = UserHandle.of(1)
    private val underTest =
        BatterySaverTileDataInteractor(testScope.testScheduler, batteryController)

    @Test
    fun availability_isTrue() =
        testScope.runTest {
            val availability = underTest.availability(testUser).toCollection(mutableListOf())

            Truth.assertThat(availability).hasSize(1)
            Truth.assertThat(availability.last()).isTrue()
        }

    @Test
    fun tileData_matchesBatteryControllerPowerSaving() =
        testScope.runTest {
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            runCurrent()
            Truth.assertThat(data!!.isPowerSaving).isFalse()

            batteryController.setPowerSaveMode(true)
            runCurrent()
            Truth.assertThat(data!!.isPowerSaving).isTrue()

            batteryController.setPowerSaveMode(false)
            runCurrent()
            Truth.assertThat(data!!.isPowerSaving).isFalse()
        }

    @Test
    fun tileData_matchesBatteryControllerIsPluggedIn() =
        testScope.runTest {
            val data by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            runCurrent()
            Truth.assertThat(data!!.isPluggedIn).isFalse()

            batteryController.isPluggedIn = true
            runCurrent()
            Truth.assertThat(data!!.isPluggedIn).isTrue()

            batteryController.isPluggedIn = false
            runCurrent()
            Truth.assertThat(data!!.isPluggedIn).isFalse()
        }
}
