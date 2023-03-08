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

package com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
class AirplaneModeViewModelImplTest : SysuiTestCase() {

    private lateinit var underTest: AirplaneModeViewModelImpl

    @Mock private lateinit var logger: TableLogBuffer
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var interactor: AirplaneModeInteractor
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        interactor = AirplaneModeInteractor(airplaneModeRepository, connectivityRepository)
        scope = CoroutineScope(IMMEDIATE)

        underTest =
            AirplaneModeViewModelImpl(
                interactor,
                logger,
                scope,
            )
    }

    @Test
    fun isAirplaneModeIconVisible_notAirplaneMode_outputsFalse() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf())
            airplaneModeRepository.setIsAirplaneMode(false)

            var latest: Boolean? = null
            val job = underTest.isAirplaneModeIconVisible.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isAirplaneModeIconVisible_forceHidden_outputsFalse() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.AIRPLANE))
            airplaneModeRepository.setIsAirplaneMode(true)

            var latest: Boolean? = null
            val job = underTest.isAirplaneModeIconVisible.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isAirplaneModeIconVisible_isAirplaneModeAndNotForceHidden_outputsTrue() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf())
            airplaneModeRepository.setIsAirplaneMode(true)

            var latest: Boolean? = null
            val job = underTest.isAirplaneModeIconVisible.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            job.cancel()
        }
}

private val IMMEDIATE = Dispatchers.Main.immediate
