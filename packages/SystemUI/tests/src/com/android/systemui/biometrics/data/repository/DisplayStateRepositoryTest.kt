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

package com.android.systemui.keyguard.data.repository

import android.hardware.devicestate.DeviceStateManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.util.Size
import android.view.Display
import android.view.DisplayInfo
import android.view.Surface
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.DisplayStateRepository
import com.android.systemui.biometrics.data.repository.DisplayStateRepositoryImpl
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.same
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

private const val NORMAL_DISPLAY_MODE_DEVICE_STATE = 2
private const val REAR_DISPLAY_MODE_DEVICE_STATE = 3

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DisplayStateRepositoryTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var deviceStateManager: DeviceStateManager
    @Mock private lateinit var displayManager: DisplayManager
    @Mock private lateinit var handler: Handler
    @Mock private lateinit var display: Display
    private lateinit var underTest: DisplayStateRepository

    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    @Captor
    private lateinit var displayListenerCaptor: ArgumentCaptor<DisplayManager.DisplayListener>

    @Before
    fun setUp() {
        val rearDisplayDeviceStates = intArrayOf(REAR_DISPLAY_MODE_DEVICE_STATE)
        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.array.config_rearDisplayDeviceStates,
            rearDisplayDeviceStates
        )

        mContext.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_reverseDefaultRotation,
            false
        )

        mContext = spy(mContext)
        whenever(mContext.display).thenReturn(display)

        underTest =
            DisplayStateRepositoryImpl(
                testScope.backgroundScope,
                mContext,
                deviceStateManager,
                displayManager,
                handler,
                fakeExecutor,
                UnconfinedTestDispatcher(),
            )
    }

    @Test
    fun updatesIsInRearDisplayMode_whenRearDisplayStateChanges() =
        testScope.runTest {
            val isInRearDisplayMode by collectLastValue(underTest.isInRearDisplayMode)
            runCurrent()

            val callback = deviceStateManager.captureCallback()

            callback.onStateChanged(NORMAL_DISPLAY_MODE_DEVICE_STATE)
            assertThat(isInRearDisplayMode).isFalse()

            callback.onStateChanged(REAR_DISPLAY_MODE_DEVICE_STATE)
            assertThat(isInRearDisplayMode).isTrue()
        }

    @Test
    fun updatesCurrentRotation_whenDisplayStateChanges() =
        testScope.runTest {
            val currentRotation by collectLastValue(underTest.currentRotation)
            runCurrent()

            verify(displayManager)
                .registerDisplayListener(
                    displayListenerCaptor.capture(),
                    same(handler),
                    eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED)
                )

            whenever(display.getDisplayInfo(any())).then {
                val info = it.getArgument<DisplayInfo>(0)
                info.rotation = Surface.ROTATION_90
                return@then true
            }
            displayListenerCaptor.value.onDisplayChanged(Surface.ROTATION_90)
            assertThat(currentRotation).isEqualTo(DisplayRotation.ROTATION_90)

            whenever(display.getDisplayInfo(any())).then {
                val info = it.getArgument<DisplayInfo>(0)
                info.rotation = Surface.ROTATION_180
                return@then true
            }
            displayListenerCaptor.value.onDisplayChanged(Surface.ROTATION_180)
            assertThat(currentRotation).isEqualTo(DisplayRotation.ROTATION_180)
        }

    @Test
    fun updatesCurrentSize_whenDisplayStateChanges() =
        testScope.runTest {
            val currentSize by collectLastValue(underTest.currentDisplaySize)
            runCurrent()

            verify(displayManager)
                .registerDisplayListener(
                    displayListenerCaptor.capture(),
                    same(handler),
                    eq(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED)
                )

            whenever(display.getDisplayInfo(any())).then {
                val info = it.getArgument<DisplayInfo>(0)
                info.rotation = Surface.ROTATION_0
                info.logicalWidth = 100
                info.logicalHeight = 200
                return@then true
            }
            displayListenerCaptor.value.onDisplayChanged(Surface.ROTATION_0)
            assertThat(currentSize).isEqualTo(Size(100, 200))

            whenever(display.getDisplayInfo(any())).then {
                val info = it.getArgument<DisplayInfo>(0)
                info.rotation = Surface.ROTATION_90
                info.logicalWidth = 100
                info.logicalHeight = 200
                return@then true
            }
            displayListenerCaptor.value.onDisplayChanged(Surface.ROTATION_180)
            assertThat(currentSize).isEqualTo(Size(200, 100))
        }
}

private fun DeviceStateManager.captureCallback() =
    withArgCaptor<DeviceStateManager.DeviceStateCallback> {
        verify(this@captureCallback).registerCallback(any(), capture())
    }
