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

package com.android.systemui.display.domain.interactor

import android.companion.virtual.VirtualDeviceManager
import android.testing.TestableLooper
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import android.view.Display.TYPE_VIRTUAL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState.CONCURRENT_DISPLAY
import com.android.systemui.display.data.repository.FakeDeviceStateRepository
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.display.data.repository.createPendingDisplay
import com.android.systemui.display.data.repository.display
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.PendingDisplay
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor.State
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class ConnectedDisplayInteractorTest : SysuiTestCase() {

    private val virtualDeviceManager = mock<VirtualDeviceManager>()

    private val fakeDisplayRepository = FakeDisplayRepository()
    private val fakeKeyguardRepository = FakeKeyguardRepository()
    private val fakeDeviceStateRepository = FakeDeviceStateRepository()
    private val connectedDisplayStateProvider: ConnectedDisplayInteractor =
        ConnectedDisplayInteractorImpl(
            virtualDeviceManager,
            fakeKeyguardRepository,
            fakeDisplayRepository,
            fakeDeviceStateRepository,
            UnconfinedTestDispatcher(),
        )
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setup() {
        whenever(virtualDeviceManager.isVirtualDeviceOwnedMirrorDisplay(anyInt())).thenReturn(false)
        fakeKeyguardRepository.setKeyguardShowing(false)
    }

    @Test
    fun displayState_nullDisplays_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(emptySet())

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_emptyDisplays_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(emptySet())

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_internalDisplay_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_INTERNAL)))

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun displayState_externalDisplay_connected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_EXTERNAL)))

            assertThat(value).isEqualTo(State.CONNECTED)
        }

    @Test
    fun displayState_multipleExternalDisplays_connected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(display(type = TYPE_EXTERNAL), display(type = TYPE_EXTERNAL))
            )

            assertThat(value).isEqualTo(State.CONNECTED)
        }

    @Test
    fun displayState_externalSecure_connectedSecure() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(display(type = TYPE_EXTERNAL, flags = Display.FLAG_SECURE))
            )

            assertThat(value).isEqualTo(State.CONNECTED_SECURE)
        }

    @Test
    fun displayState_multipleExternal_onlyOneSecure_connectedSecure() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(
                setOf(
                    display(type = TYPE_EXTERNAL, flags = Display.FLAG_SECURE),
                    display(type = TYPE_EXTERNAL, flags = 0)
                )
            )

            assertThat(value).isEqualTo(State.CONNECTED_SECURE)
        }

    @Test
    fun displayState_virtualDeviceOwnedMirrorVirtualDisplay_connected() =
        testScope.runTest {
            whenever(virtualDeviceManager.isVirtualDeviceOwnedMirrorDisplay(anyInt()))
                .thenReturn(true)
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_VIRTUAL)))

            assertThat(value).isEqualTo(State.CONNECTED)
        }

    @Test
    fun displayState_virtualDeviceUnownedMirrorVirtualDisplay_disconnected() =
        testScope.runTest {
            val value by lastValue()

            fakeDisplayRepository.emit(setOf(display(type = TYPE_VIRTUAL)))

            assertThat(value).isEqualTo(State.DISCONNECTED)
        }

    @Test
    fun virtualDeviceOwnedMirrorVirtualDisplay_emitsConnectedDisplayAddition() =
        testScope.runTest {
            whenever(virtualDeviceManager.isVirtualDeviceOwnedMirrorDisplay(anyInt()))
                .thenReturn(true)
            var count = 0
            val job =
                connectedDisplayStateProvider.connectedDisplayAddition
                    .onEach { count++ }
                    .launchIn(this)

            fakeDisplayRepository.emit(display(type = TYPE_VIRTUAL))

            runCurrent()
            assertThat(count).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun virtualDeviceUnownedMirrorVirtualDisplay_doesNotEmitConnectedDisplayAddition() =
        testScope.runTest {
            var count = 0
            val job =
                connectedDisplayStateProvider.connectedDisplayAddition
                    .onEach { count++ }
                    .launchIn(this)

            fakeDisplayRepository.emit(display(type = TYPE_VIRTUAL))

            runCurrent()
            assertThat(count).isEqualTo(0)
            job.cancel()
        }

    @Test
    fun externalDisplay_emitsConnectedDisplayAddition() =
        testScope.runTest {
            var count = 0
            val job =
                connectedDisplayStateProvider.connectedDisplayAddition
                    .onEach { count++ }
                    .launchIn(this)

            fakeDisplayRepository.emit(display(type = TYPE_EXTERNAL))

            runCurrent()
            assertThat(count).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun internalDisplay_doesNotEmitConnectedDisplayAddition() =
        testScope.runTest {
            var count = 0
            val job =
                connectedDisplayStateProvider.connectedDisplayAddition
                    .onEach { count++ }
                    .launchIn(this)

            fakeDisplayRepository.emit(display(type = TYPE_INTERNAL))

            runCurrent()
            assertThat(count).isEqualTo(0)
            job.cancel()
        }

    @Test
    fun pendingDisplay_propagated() =
        testScope.runTest {
            val value by lastPendingDisplay()
            val pendingDisplayId = createPendingDisplay()

            fakeDisplayRepository.emit(pendingDisplayId)

            assertThat(value).isNotNull()
        }

    @Test
    fun onPendingDisplay_keyguardShowing_returnsPendingDisplay() =
        testScope.runTest {
            fakeKeyguardRepository.setKeyguardShowing(true)
            val pendingDisplay by lastPendingDisplay()

            fakeDisplayRepository.emit(createPendingDisplay())
            assertThat(pendingDisplay).isNull()

            fakeKeyguardRepository.setKeyguardShowing(false)

            assertThat(pendingDisplay).isNotNull()
        }

    @Test
    fun onPendingDisplay_keyguardShowing_returnsNull() =
        testScope.runTest {
            fakeKeyguardRepository.setKeyguardShowing(false)
            val pendingDisplay by lastPendingDisplay()

            fakeDisplayRepository.emit(createPendingDisplay())
            assertThat(pendingDisplay).isNotNull()

            fakeKeyguardRepository.setKeyguardShowing(true)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun concurrentDisplaysInProgress_started_returnsTrue() =
        testScope.runTest {
            val concurrentDisplaysInProgress =
                collectLastValue(connectedDisplayStateProvider.concurrentDisplaysInProgress)

            fakeDeviceStateRepository.emit(CONCURRENT_DISPLAY)

            assertThat(concurrentDisplaysInProgress()).isTrue()
        }

    @Test
    fun concurrentDisplaysInProgress_stopped_returnsFalse() =
        testScope.runTest {
            val concurrentDisplaysInProgress =
                collectLastValue(connectedDisplayStateProvider.concurrentDisplaysInProgress)

            fakeDeviceStateRepository.emit(CONCURRENT_DISPLAY)
            fakeDeviceStateRepository.emit(DeviceStateRepository.DeviceState.UNKNOWN)

            assertThat(concurrentDisplaysInProgress()).isFalse()
        }

    @Test
    fun concurrentDisplaysInProgress_otherStates_returnsFalse() =
        testScope.runTest {
            val concurrentDisplaysInProgress =
                collectLastValue(connectedDisplayStateProvider.concurrentDisplaysInProgress)

            DeviceStateRepository.DeviceState.entries
                .filter { it != CONCURRENT_DISPLAY }
                .forEach { deviceState ->
                    fakeDeviceStateRepository.emit(deviceState)

                    assertThat(concurrentDisplaysInProgress()).isFalse()
                }
        }

    private fun TestScope.lastValue(): FlowValue<State?> =
        collectLastValue(connectedDisplayStateProvider.connectedDisplayState)

    private fun TestScope.lastPendingDisplay(): FlowValue<PendingDisplay?> =
        collectLastValue(connectedDisplayStateProvider.pendingDisplay)
}
