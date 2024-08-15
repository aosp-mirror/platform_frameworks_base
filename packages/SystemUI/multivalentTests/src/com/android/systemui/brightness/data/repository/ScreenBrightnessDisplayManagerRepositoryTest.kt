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

package com.android.systemui.brightness.data.repository

import android.hardware.display.BrightnessInfo
import android.hardware.display.BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE
import android.hardware.display.BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.brightness.shared.model.LinearBrightness
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenBrightnessDisplayManagerRepositoryTest : SysuiTestCase() {
    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private val kosmos = testKosmos()

    private var currentBrightnessInfo = BrightnessInfo()

    @Mock private lateinit var displayManager: DisplayManager
    @Mock private lateinit var display: Display

    private val displayId = 0

    private lateinit var underTest: ScreenBrightnessDisplayManagerRepository

    @Before
    fun setUp() {
        underTest =
            ScreenBrightnessDisplayManagerRepository(
                displayId,
                displayManager,
                FakeLogBuffer.Factory.create(),
                mock<TableLogBuffer>(),
                kosmos.applicationCoroutineScope,
                kosmos.testDispatcher,
            )

        whenever(displayManager.getDisplay(displayId)).thenReturn(display)
        // Using then answer so it will be retrieved in every call
        whenever(display.brightnessInfo).thenAnswer { currentBrightnessInfo }
    }

    @Test
    fun startingBrightnessInfo() =
        with(kosmos) {
            testScope.runTest {
                val brightness by collectLastValue(underTest.linearBrightness)
                val minBrightness by collectLastValue(underTest.minLinearBrightness)
                val maxBrightness by collectLastValue(underTest.maxLinearBrightness)
                runCurrent()

                assertThat(brightness?.floatValue).isEqualTo(currentBrightnessInfo.brightness)
                assertThat(minBrightness?.floatValue)
                    .isEqualTo(currentBrightnessInfo.brightnessMinimum)
                assertThat(maxBrightness?.floatValue)
                    .isEqualTo(currentBrightnessInfo.brightnessMaximum)
            }
        }

    @Test
    fun followsChangingBrightnessInfo() =
        with(kosmos) {
            testScope.runTest {
                val listenerCaptor = argumentCaptor<DisplayManager.DisplayListener>()

                val brightness by collectLastValue(underTest.linearBrightness)
                val minBrightness by collectLastValue(underTest.minLinearBrightness)
                val maxBrightness by collectLastValue(underTest.maxLinearBrightness)
                runCurrent()

                verify(displayManager)
                    .registerDisplayListener(
                        capture(listenerCaptor),
                        eq(null),
                        eq(EVENT_FLAG_DISPLAY_BRIGHTNESS),
                    )

                val newBrightness = BrightnessInfo(0.6f, 0.3f, 0.9f)
                changeBrightnessInfoAndNotify(newBrightness, listenerCaptor.value)

                assertThat(brightness?.floatValue).isEqualTo(currentBrightnessInfo.brightness)
                assertThat(minBrightness?.floatValue)
                    .isEqualTo(currentBrightnessInfo.brightnessMinimum)
                assertThat(maxBrightness?.floatValue)
                    .isEqualTo(currentBrightnessInfo.brightnessMaximum)
            }
        }

    @Test
    fun minMaxWhenNotCollecting() =
        with(kosmos) {
            testScope.runTest {
                currentBrightnessInfo = BrightnessInfo(0.5f, 0.1f, 0.7f)
                val (min, max) = underTest.getMinMaxLinearBrightness()
                assertThat(min.floatValue).isEqualTo(currentBrightnessInfo.brightnessMinimum)
                assertThat(max.floatValue).isEqualTo(currentBrightnessInfo.brightnessMaximum)
            }
        }

    @Test
    fun minMaxWhenCollecting() =
        with(kosmos) {
            testScope.runTest {
                val listenerCaptor = argumentCaptor<DisplayManager.DisplayListener>()

                val brightness by collectLastValue(underTest.linearBrightness)
                runCurrent()

                verify(displayManager)
                    .registerDisplayListener(
                        capture(listenerCaptor),
                        eq(null),
                        eq(EVENT_FLAG_DISPLAY_BRIGHTNESS),
                    )

                changeBrightnessInfoAndNotify(
                    BrightnessInfo(0.5f, 0.1f, 0.7f),
                    listenerCaptor.value
                )
                runCurrent()

                val (min, max) = underTest.getMinMaxLinearBrightness()
                assertThat(min.floatValue).isEqualTo(currentBrightnessInfo.brightnessMinimum)
                assertThat(max.floatValue).isEqualTo(currentBrightnessInfo.brightnessMaximum)
            }
        }

    @Test
    fun setTemporaryBrightness_insideBounds() =
        with(kosmos) {
            testScope.runTest {
                val brightness = 0.3f
                underTest.setTemporaryBrightness(LinearBrightness(brightness))
                runCurrent()

                verify(displayManager).setTemporaryBrightness(displayId, brightness)
                verify(displayManager, never()).setBrightness(anyInt(), anyFloat())
            }
        }

    @Test
    fun setTemporaryBrightness_outsideBounds() =
        with(kosmos) {
            testScope.runTest {
                val brightness = 1.3f
                underTest.setTemporaryBrightness(LinearBrightness(brightness))
                runCurrent()

                verify(displayManager)
                    .setTemporaryBrightness(displayId, currentBrightnessInfo.brightnessMaximum)
                verify(displayManager, never()).setBrightness(anyInt(), anyFloat())
            }
        }

    @Test
    fun setBrightness_insideBounds() =
        with(kosmos) {
            testScope.runTest {
                val brightness = 0.3f
                underTest.setBrightness(LinearBrightness(brightness))
                runCurrent()

                verify(displayManager).setBrightness(displayId, brightness)
                verify(displayManager, never()).setTemporaryBrightness(anyInt(), anyFloat())
            }
        }

    @Test
    fun setBrightness_outsideBounds() =
        with(kosmos) {
            testScope.runTest {
                val brightness = 1.3f
                underTest.setBrightness(LinearBrightness(brightness))
                runCurrent()

                verify(displayManager)
                    .setBrightness(displayId, currentBrightnessInfo.brightnessMaximum)
                verify(displayManager, never()).setTemporaryBrightness(anyInt(), anyFloat())
            }
        }

    private fun changeBrightnessInfoAndNotify(
        newValue: BrightnessInfo,
        listener: DisplayManager.DisplayListener,
    ) {
        currentBrightnessInfo = newValue
        listener.onDisplayChanged(displayId)
    }

    companion object {
        fun BrightnessInfo(
            brightness: Float = 0f,
            minBrightness: Float = 0f,
            maxBrightness: Float = 1f,
        ): BrightnessInfo {
            return BrightnessInfo(
                brightness,
                minBrightness,
                maxBrightness,
                HIGH_BRIGHTNESS_MODE_OFF,
                1f,
                BRIGHTNESS_MAX_REASON_NONE,
            )
        }
    }
}
