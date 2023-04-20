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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
class AirplaneModeInteractorTest : SysuiTestCase() {

    private lateinit var underTest: AirplaneModeInteractor

    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository

    @Before
    fun setUp() {
        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        underTest = AirplaneModeInteractor(airplaneModeRepository, connectivityRepository)
    }

    @Test
    fun isAirplaneMode_matchesRepo() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isAirplaneMode.onEach { latest = it }.launchIn(this)

            airplaneModeRepository.setIsAirplaneMode(true)
            yield()
            assertThat(latest).isTrue()

            airplaneModeRepository.setIsAirplaneMode(false)
            yield()
            assertThat(latest).isFalse()

            airplaneModeRepository.setIsAirplaneMode(true)
            yield()
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isForceHidden_repoHasWifiHidden_outputsTrue() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.AIRPLANE))

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isForceHidden_repoDoesNotHaveWifiHidden_outputsFalse() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf())

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }
}

private val IMMEDIATE = Dispatchers.Main.immediate
