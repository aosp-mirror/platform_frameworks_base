/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.flashlight.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.flashlight.domain.model.FlashlightTileModel
import com.android.systemui.utils.leaks.FakeFlashlightController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class FlashlightTileDataInteractorTest : SysuiTestCase() {
    private lateinit var controller: FakeFlashlightController
    private lateinit var underTest: FlashlightTileDataInteractor

    @Before
    fun setup() {
        controller = FakeFlashlightController(LeakCheck())
        underTest = FlashlightTileDataInteractor(controller)
    }

    @Test
    fun availabilityOnMatchesController() = runTest {
        controller.hasFlashlight = true

        runCurrent()
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isTrue()
    }
    @Test
    fun availabilityOffMatchesController() = runTest {
        controller.hasFlashlight = false

        runCurrent()
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isFalse()
    }

    @Test
    fun isEnabledDataMatchesControllerWhenAvailable() = runTest {
        val flowValues: List<FlashlightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        controller.setFlashlight(true)
        runCurrent()
        controller.setFlashlight(false)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(4) // 2 from setup(), 2 from this test
        assertThat(
                flowValues.filterIsInstance<FlashlightTileModel.FlashlightAvailable>().map {
                    it.isEnabled
                }
            )
            .containsExactly(false, false, true, false)
            .inOrder()
    }

    /**
     * Simulates the scenario of changes in flashlight tile availability when camera is initially
     * closed, then opened, and closed again.
     */
    @Test
    fun availabilityDataMatchesControllerAvailability() = runTest {
        val flowValues: List<FlashlightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        controller.onFlashlightAvailabilityChanged(false)
        runCurrent()
        controller.onFlashlightAvailabilityChanged(true)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(4) // 2 from setup + 2 from this test
        assertThat(flowValues.map { it is FlashlightTileModel.FlashlightAvailable })
            .containsExactly(true, true, false, true)
            .inOrder()
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
