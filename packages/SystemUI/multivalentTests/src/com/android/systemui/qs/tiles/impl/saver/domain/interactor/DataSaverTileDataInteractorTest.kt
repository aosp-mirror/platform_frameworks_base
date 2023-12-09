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

package com.android.systemui.qs.tiles.impl.saver.domain.interactor

import android.os.UserHandle
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.saver.domain.model.DataSaverTileModel
import com.android.systemui.utils.leaks.FakeDataSaverController
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DataSaverTileDataInteractorTest : SysuiTestCase() {
    private val controller: FakeDataSaverController = FakeDataSaverController(LeakCheck())
    private val underTest: DataSaverTileDataInteractor = DataSaverTileDataInteractor(controller)

    @Test
    fun isAvailableRegardlessOfController() = runTest {
        controller.setDataSaverEnabled(false)

        runCurrent()
        val availability by collectLastValue(underTest.availability(TEST_USER))

        Truth.assertThat(availability).isTrue()
    }

    @Test
    fun dataMatchesController() = runTest {
        controller.setDataSaverEnabled(false)
        val flowValues: List<DataSaverTileModel> by
            collectValues(underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest)))

        runCurrent()
        controller.setDataSaverEnabled(true)
        runCurrent()
        controller.setDataSaverEnabled(false)
        runCurrent()

        Truth.assertThat(flowValues.size).isEqualTo(3)
        Truth.assertThat(flowValues.map { it.isEnabled })
            .containsExactly(false, true, false)
            .inOrder()
    }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
