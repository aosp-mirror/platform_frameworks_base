/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.airplane.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AirplaneModeInteractorTest : SysuiTestCase() {

    private val mobileConnectionsRepository =
        FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), mock<TableLogBuffer> {})
    private val airplaneModeRepository = FakeAirplaneModeRepository()
    private val connectivityRepository = FakeConnectivityRepository()

    private val underTest =
        AirplaneModeInteractor(
            airplaneModeRepository,
            connectivityRepository,
            mobileConnectionsRepository
        )

    @Test
    fun isAirplaneMode_matchesRepo() = runTest {
        var latest: Boolean? = null
        underTest.isAirplaneMode.onEach { latest = it }.launchIn(backgroundScope)

        airplaneModeRepository.setIsAirplaneMode(true)
        runCurrent()
        assertThat(latest).isTrue()

        airplaneModeRepository.setIsAirplaneMode(false)
        runCurrent()
        assertThat(latest).isFalse()

        airplaneModeRepository.setIsAirplaneMode(true)
        runCurrent()
        assertThat(latest).isTrue()
    }

    @Test
    fun isForceHidden_repoHasWifiHidden_outputsTrue() = runTest {
        connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.AIRPLANE))

        var latest: Boolean? = null
        underTest.isForceHidden.onEach { latest = it }.launchIn(backgroundScope)
        runCurrent()

        assertThat(latest).isTrue()
    }

    @Test
    fun isForceHidden_repoDoesNotHaveWifiHidden_outputsFalse() = runTest {
        connectivityRepository.setForceHiddenIcons(setOf())

        var latest: Boolean? = null
        underTest.isForceHidden.onEach { latest = it }.launchIn(backgroundScope)
        runCurrent()

        assertThat(latest).isFalse()
    }

    @Test
    fun testSetAirplaneMode_inEcmMode_Blocked() = runTest {
        mobileConnectionsRepository.setIsInEcmState(true)

        assertThat(underTest.setIsAirplaneMode(true))
            .isEqualTo(AirplaneModeInteractor.SetResult.BLOCKED_BY_ECM)
        assertThat(airplaneModeRepository.isAirplaneMode.value).isFalse()
    }

    @Test
    fun testSetAirplaneMode_notInEcmMode_Success() = runTest {
        mobileConnectionsRepository.setIsInEcmState(false)

        underTest.setIsAirplaneMode(true)

        assertThat(underTest.setIsAirplaneMode(true))
            .isEqualTo(AirplaneModeInteractor.SetResult.SUCCESS)
        assertThat(airplaneModeRepository.isAirplaneMode.value).isTrue()
    }
}
