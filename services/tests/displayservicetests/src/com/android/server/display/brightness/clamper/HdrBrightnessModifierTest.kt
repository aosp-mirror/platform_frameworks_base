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

package com.android.server.display.brightness.clamper

import android.hardware.display.DisplayManagerInternal
import android.os.IBinder
import android.util.Spline
import android.view.SurfaceControlHdrLayerInfoListener
import androidx.test.filters.SmallTest
import com.android.server.display.DisplayBrightnessState
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.brightness.clamper.BrightnessClamperController.ClamperChangeListener
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState
import com.android.server.display.brightness.clamper.HdrBrightnessModifier.DEFAULT_MAX_HDR_SDR_RATIO
import com.android.server.display.brightness.clamper.HdrBrightnessModifier.Injector
import com.android.server.display.config.createHdrBrightnessData
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class HdrBrightnessModifierTest {

    private val testHandler = TestHandler(null)
    private val testInjector = TestInjector()
    private val mockChangeListener = mock<ClamperChangeListener>()
    private val mockDisplayDeviceConfig = mock<DisplayDeviceConfig>()
    private val mockDisplayBinder = mock<IBinder>()
    private val mockDisplayBinderOther = mock<IBinder>()
    private val mockSpline = mock<Spline>()
    private val mockRequest = mock<DisplayManagerInternal.DisplayPowerRequest>()

    private lateinit var modifier: HdrBrightnessModifier
    private val dummyData = createDisplayDeviceData(mockDisplayDeviceConfig, mockDisplayBinder)
    private val dummyHdrData = createHdrBrightnessData()

    @Test
    fun `change listener is not called on init`() {
        initHdrModifier()

        verify(mockChangeListener, never()).onChanged()
    }

    @Test
    fun `hdr listener registered on init if hdr data is present`() {
        initHdrModifier()

        assertThat(testInjector.registeredHdrListener).isNotNull()
        assertThat(testInjector.registeredToken).isEqualTo(mockDisplayBinder)
    }

    @Test
    fun `hdr listener not registered on init if hdr data is missing`() {
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(null)
        modifier = HdrBrightnessModifier(testHandler, mockChangeListener, testInjector, dummyData)

        testHandler.flush()

        assertThat(testInjector.registeredHdrListener).isNull()
        assertThat(testInjector.registeredToken).isNull()
    }

    @Test
    fun `unsubscribes hdr listener when display changed with no hdr data`() {
        initHdrModifier()

        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(null)
        modifier.onDisplayChanged(dummyData)
        testHandler.flush()

        assertThat(testInjector.registeredHdrListener).isNull()
        assertThat(testInjector.registeredToken).isNull()
        verify(mockChangeListener, never()).onChanged()
    }

    @Test
    fun `resubscribes hdr listener when display changed with different token`() {
        initHdrModifier()

        modifier.onDisplayChanged(
            createDisplayDeviceData(mockDisplayDeviceConfig, mockDisplayBinderOther))
        testHandler.flush()

        assertThat(testInjector.registeredHdrListener).isNotNull()
        assertThat(testInjector.registeredToken).isEqualTo(mockDisplayBinderOther)
        verify(mockChangeListener, never()).onChanged()
    }

    @Test
    fun `test NO_HDR mode`() {
        initHdrModifier()

        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            sdrToHdrRatioSpline = mockSpline
        ))
        // screen size = 10_000
        modifier.onDisplayChanged(createDisplayDeviceData(
            mockDisplayDeviceConfig, mockDisplayBinder,
            width = 100,
            height = 100
        ))
        testHandler.flush()
        // hdr size = 900
        val desiredMaxHdrRatio = 8f
        val hdrWidth = 30
        val hdrHeight = 30
        testInjector.registeredHdrListener!!.onHdrInfoChanged(
            mockDisplayBinder, 1, hdrWidth, hdrHeight, 0, desiredMaxHdrRatio
        )
        testHandler.flush()

        val modifierState = ModifiersAggregatedState()
        modifier.applyStateChange(modifierState)

        assertThat(modifierState.mHdrHbmEnabled).isFalse()
        assertThat(modifierState.mMaxDesiredHdrRatio).isEqualTo(DEFAULT_MAX_HDR_SDR_RATIO)
        assertThat(modifierState.mSdrHdrRatioSpline).isNull()

        val stateBuilder = DisplayBrightnessState.builder()
        modifier.apply(mockRequest, stateBuilder)

        verify(mockDisplayDeviceConfig, never()).getHdrBrightnessFromSdr(any(), any(), any())
        assertThat(stateBuilder.hdrBrightness).isEqualTo(DisplayBrightnessState.BRIGHTNESS_NOT_SET)
    }

    @Test
    fun `test NBM_HDR mode`() {
        initHdrModifier()
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            sdrToHdrRatioSpline = mockSpline
        ))
        // screen size = 10_000
        modifier.onDisplayChanged(createDisplayDeviceData(
            mockDisplayDeviceConfig, mockDisplayBinder,
            width = 100,
            height = 100
        ))
        testHandler.flush()
        // hdr size = 5_100
        val desiredMaxHdrRatio = 8f
        val hdrWidth = 100
        val hdrHeight = 51
        testInjector.registeredHdrListener!!.onHdrInfoChanged(
            mockDisplayBinder, 1, hdrWidth, hdrHeight, 0, desiredMaxHdrRatio
        )
        testHandler.flush()

        val modifierState = ModifiersAggregatedState()
        modifier.applyStateChange(modifierState)

        assertThat(modifierState.mHdrHbmEnabled).isFalse()
        assertThat(modifierState.mMaxDesiredHdrRatio).isEqualTo(desiredMaxHdrRatio)
        assertThat(modifierState.mSdrHdrRatioSpline).isEqualTo(mockSpline)

        val expectedHdrBrightness = 0.85f
        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, desiredMaxHdrRatio, mockSpline)).thenReturn(expectedHdrBrightness)
        val stateBuilder = DisplayBrightnessState.builder()
        modifier.apply(mockRequest, stateBuilder)

        assertThat(stateBuilder.hdrBrightness).isEqualTo(expectedHdrBrightness)
    }

    @Test
    fun `test HBM_HDR mode`() {
        initHdrModifier()
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            sdrToHdrRatioSpline = mockSpline
        ))
        // screen size = 10_000
        modifier.onDisplayChanged(createDisplayDeviceData(
            mockDisplayDeviceConfig, mockDisplayBinder,
            width = 100,
            height = 100
        ))
        testHandler.flush()
        // hdr size = 7_100
        val desiredMaxHdrRatio = 8f
        val hdrWidth = 100
        val hdrHeight = 71
        testInjector.registeredHdrListener!!.onHdrInfoChanged(
            mockDisplayBinder, 1, hdrWidth, hdrHeight, 0, desiredMaxHdrRatio
        )
        testHandler.flush()

        val modifierState = ModifiersAggregatedState()
        modifier.applyStateChange(modifierState)

        assertThat(modifierState.mHdrHbmEnabled).isTrue()
        assertThat(modifierState.mMaxDesiredHdrRatio).isEqualTo(desiredMaxHdrRatio)
        assertThat(modifierState.mSdrHdrRatioSpline).isEqualTo(mockSpline)

        val expectedHdrBrightness = 0.83f
        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, desiredMaxHdrRatio, mockSpline)).thenReturn(expectedHdrBrightness)
        val stateBuilder = DisplayBrightnessState.builder()
        modifier.apply(mockRequest, stateBuilder)

        assertThat(stateBuilder.hdrBrightness).isEqualTo(expectedHdrBrightness)
    }

    private fun initHdrModifier() {
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(dummyHdrData)
        modifier = HdrBrightnessModifier(testHandler, mockChangeListener, testInjector, dummyData)
        testHandler.flush()
    }


    internal class TestInjector : Injector() {
        var registeredHdrListener: SurfaceControlHdrLayerInfoListener? = null
        var registeredToken: IBinder? = null

        override fun registerHdrListener(
            listener: SurfaceControlHdrLayerInfoListener, token: IBinder
        ) {
            registeredHdrListener = listener
            registeredToken = token
        }

        override fun unregisterHdrListener(
            listener: SurfaceControlHdrLayerInfoListener, token: IBinder
        ) {
            registeredHdrListener = null
            registeredToken = null
        }
    }
}