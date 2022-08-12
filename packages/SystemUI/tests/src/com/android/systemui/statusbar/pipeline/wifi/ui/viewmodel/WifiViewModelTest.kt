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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiViewModelTest : SysuiTestCase() {

    private lateinit var underTest: WifiViewModel

    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var constants: WifiConstants
    private lateinit var repository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        repository = FakeWifiRepository()
        interactor = WifiInteractor(repository)

        underTest = WifiViewModel(
                constants,
                logger,
                interactor
        )

        // Set up with a valid SSID
        repository.setWifiNetwork(WifiNetworkModel.Active(networkId = 1, ssid = "AB"))
    }

    @Test
    fun activityInVisible_showActivityConfigFalse_receivesFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(false)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Verify that on launch, we receive a false.
        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityInVisible_showActivityConfigFalse_noUpdatesReceived() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(false)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Update the repo to have activityIn
        repository.setWifiActivity(WifiActivityModel(hasActivityIn = true, hasActivityOut = false))
        yield()

        // Verify that we didn't update to activityIn=true (because our config is false)
        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityInVisible_showActivityConfigTrue_receivesUpdate() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Update the repo to have activityIn
        repository.setWifiActivity(WifiActivityModel(hasActivityIn = true, hasActivityOut = false))
        yield()

        // Verify that we updated to activityIn=true
        assertThat(latest).isTrue()

        job.cancel()
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
