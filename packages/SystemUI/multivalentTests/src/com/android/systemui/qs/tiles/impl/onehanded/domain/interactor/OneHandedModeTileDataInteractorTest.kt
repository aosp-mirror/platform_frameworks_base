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

package com.android.systemui.qs.tiles.impl.onehanded.domain.interactor

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.oneHandedModeRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.onehanded.domain.OneHandedModeTileDataInteractor
import com.android.wm.shell.onehanded.OneHanded
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class OneHandedModeTileDataInteractorTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val testUser = UserHandle.of(1)!!
    private val oneHandedModeRepository = kosmos.oneHandedModeRepository
    private val underTest: OneHandedModeTileDataInteractor =
        OneHandedModeTileDataInteractor(oneHandedModeRepository)

    @Test
    fun availability_matchesController() = runTest {
        val expectedAvailability = OneHanded.sIsSupportOneHandedMode
        val availability by collectLastValue(underTest.availability(testUser))

        assertThat(availability).isEqualTo(expectedAvailability)
    }

    @Test
    fun data_matchesRepository() = runTest {
        val lastData by
            collectLastValue(underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest)))
        runCurrent()
        assertThat(lastData!!.isEnabled).isFalse()

        oneHandedModeRepository.setIsEnabled(true, testUser)
        runCurrent()
        assertThat(lastData!!.isEnabled).isTrue()

        oneHandedModeRepository.setIsEnabled(false, testUser)
        runCurrent()
        assertThat(lastData!!.isEnabled).isFalse()
    }
}
