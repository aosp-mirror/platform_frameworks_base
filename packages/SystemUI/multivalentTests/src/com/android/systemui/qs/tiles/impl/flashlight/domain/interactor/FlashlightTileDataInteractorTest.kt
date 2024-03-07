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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
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
    fun dataMatchesController() = runTest {
        controller.setFlashlight(false)
        val flowValues: List<FlashlightTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        controller.setFlashlight(true)
        runCurrent()
        controller.setFlashlight(false)
        runCurrent()

        assertThat(flowValues.size).isEqualTo(3)
        assertThat(flowValues.map { it.isEnabled }).containsExactly(false, true, false).inOrder()
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
