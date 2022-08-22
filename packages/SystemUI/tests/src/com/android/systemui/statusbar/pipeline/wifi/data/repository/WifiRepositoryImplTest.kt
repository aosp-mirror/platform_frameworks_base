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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl.Companion.ACTIVITY_DEFAULT
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiRepositoryImpl

    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var wifiManager: WifiManager
    private lateinit var executor: Executor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())
    }

    @Test
    fun wifiActivity_nullWifiManager_receivesDefault() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                wifiManager = null,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isEqualTo(ACTIVITY_DEFAULT)

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesNone_activityFlowHasNone() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                wifiManager,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_NONE)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = false, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesIn_activityFlowHasIn() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                wifiManager,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_IN)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesOut_activityFlowHasOut() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                wifiManager,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_OUT)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = false, hasActivityOut = true)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesInout_activityFlowHasInAndOut() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                wifiManager,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT)

        assertThat(latest).isEqualTo(WifiActivityModel(hasActivityIn = true, hasActivityOut = true))

        job.cancel()
    }

    private fun getTrafficStateCallback(): TrafficStateCallback {
        val callbackCaptor = argumentCaptor<TrafficStateCallback>()
        verify(wifiManager).registerTrafficStateCallback(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
