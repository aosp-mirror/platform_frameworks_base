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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class ScreenRecordTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val screenRecordRepo = kosmos.screenRecordRepository
    private val underTest: ScreenRecordTileDataInteractor =
        ScreenRecordTileDataInteractor(screenRecordRepo)

    private val isRecording = ScreenRecordModel.Recording
    private val isDoingNothing = ScreenRecordModel.DoingNothing
    private val isStarting0 = ScreenRecordModel.Starting(0)

    @Test
    fun isAvailable_returnsTrue() = runTest {
        val availability by collectLastValue(underTest.availability(TEST_USER))

        assertThat(availability).isTrue()
    }

    @Test
    fun dataMatchesRepo() =
        testScope.runTest {
            val lastModel by
                collectLastValue(
                    underTest.tileData(TEST_USER, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            assertThat(lastModel).isEqualTo(isDoingNothing)

            val expectedModelStartingIn1 = ScreenRecordModel.Starting(1)
            screenRecordRepo.screenRecordState.value = expectedModelStartingIn1
            assertThat(lastModel).isEqualTo(expectedModelStartingIn1)

            screenRecordRepo.screenRecordState.value = isStarting0
            assertThat(lastModel).isEqualTo(isStarting0)

            screenRecordRepo.screenRecordState.value = isDoingNothing
            assertThat(lastModel).isEqualTo(isDoingNothing)

            screenRecordRepo.screenRecordState.value = isRecording
            assertThat(lastModel).isEqualTo(isRecording)

            screenRecordRepo.screenRecordState.value = isDoingNothing
            assertThat(lastModel).isEqualTo(isDoingNothing)
        }

    private companion object {
        val TEST_USER = UserHandle.of(1)!!
    }
}
