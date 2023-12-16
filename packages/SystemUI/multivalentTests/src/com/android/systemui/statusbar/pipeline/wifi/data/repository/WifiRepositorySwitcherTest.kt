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

import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoModeWifiDataSource
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiInputLogger
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiRepositorySwitcherTest : SysuiTestCase() {
    private lateinit var underTest: WifiRepositorySwitcher
    private lateinit var realImpl: WifiRepositoryImpl
    private lateinit var demoImpl: DemoWifiRepository

    @Mock private lateinit var demoModeController: DemoModeController
    @Mock private lateinit var logger: WifiInputLogger
    @Mock private lateinit var tableLogger: TableLogBuffer
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var wifiManager: WifiManager
    @Mock private lateinit var demoModeWifiDataSource: DemoModeWifiDataSource
    private val demoModelFlow = MutableStateFlow<FakeWifiEventModel?>(null)

    private val mainExecutor = FakeExecutor(FakeSystemClock())

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Never start in demo mode
        whenever(demoModeController.isInDemoMode).thenReturn(false)

        realImpl =
            WifiRepositoryImpl(
                fakeBroadcastDispatcher,
                connectivityManager,
                FakeConnectivityRepository(),
                logger,
                tableLogger,
                mainExecutor,
                testDispatcher,
                testScope.backgroundScope,
                wifiManager,
            )

        whenever(demoModeWifiDataSource.wifiEvents).thenReturn(demoModelFlow)

        demoImpl =
            DemoWifiRepository(
                demoModeWifiDataSource,
                testScope.backgroundScope,
            )

        underTest =
            WifiRepositorySwitcher(
                realImpl,
                demoImpl,
                demoModeController,
                testScope.backgroundScope,
            )
    }

    @Test
    fun switcherActiveRepo_updatesWhenDemoModeChanges() =
        testScope.runTest {
            assertThat(underTest.activeRepo.value).isSameInstanceAs(realImpl)

            var latest: WifiRepository? = null
            val job = underTest.activeRepo.onEach { latest = it }.launchIn(this)

            startDemoMode()

            assertThat(latest).isSameInstanceAs(demoImpl)

            finishDemoMode()

            assertThat(latest).isSameInstanceAs(realImpl)

            job.cancel()
        }

    private fun startDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(true)
        getDemoModeCallback().onDemoModeStarted()
    }

    private fun finishDemoMode() {
        whenever(demoModeController.isInDemoMode).thenReturn(false)
        getDemoModeCallback().onDemoModeFinished()
    }

    private fun getDemoModeCallback(): DemoMode {
        val captor = kotlinArgumentCaptor<DemoMode>()
        Mockito.verify(demoModeController).addCallback(captor.capture())
        return captor.value
    }
}
