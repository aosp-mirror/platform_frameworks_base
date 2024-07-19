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
import android.os.PowerManager.BRIGHTNESS_MAX
import android.util.Spline
import android.view.SurfaceControlHdrLayerInfoListener
import androidx.test.filters.SmallTest
import com.android.server.display.DisplayBrightnessState
import com.android.server.display.DisplayBrightnessState.BRIGHTNESS_NOT_SET
import com.android.server.display.DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET
import com.android.server.display.DisplayDeviceConfig
import com.android.server.display.brightness.clamper.BrightnessClamperController.ClamperChangeListener
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState
import com.android.server.display.brightness.clamper.HdrBrightnessModifier.DEFAULT_MAX_HDR_SDR_RATIO
import com.android.server.display.brightness.clamper.HdrBrightnessModifier.Injector
import com.android.server.display.config.HdrBrightnessData
import com.android.server.display.config.createHdrBrightnessData
import com.android.server.testutils.OffsettableClock
import com.android.server.testutils.TestHandler
import com.google.common.truth.Truth.assertThat

import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val SEND_TIME_TOLERANCE: Long = 100

@SmallTest
class HdrBrightnessModifierTest {

    private val stoppedClock = OffsettableClock.Stopped()
    private val testHandler = TestHandler(null, stoppedClock)
    private val testInjector = TestInjector()
    private val mockChangeListener = mock<ClamperChangeListener>()
    private val mockDisplayDeviceConfig = mock<DisplayDeviceConfig>()
    private val mockDisplayBinder = mock<IBinder>()
    private val mockDisplayBinderOther = mock<IBinder>()
    private val mockSpline = mock<Spline>()
    private val mockRequest = mock<DisplayManagerInternal.DisplayPowerRequest>()

    private lateinit var modifier: HdrBrightnessModifier
    private val dummyData = createDisplayDeviceData(mockDisplayDeviceConfig, mockDisplayBinder)

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
        initHdrModifier(null)

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
        // screen size = 10_000
        setupDisplay(width = 100, height = 100, hdrBrightnessData = createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            sdrToHdrRatioSpline = mockSpline
        ))

        // hdr size = 900
        val desiredMaxHdrRatio = 8f
        setupHdrLayer(width = 30, height = 30, maxHdrRatio = desiredMaxHdrRatio)

        assertModifierState()
    }

    @Test
    fun `test NBM_HDR mode`() {
        initHdrModifier()
        // screen size = 10_000
        val transitionPoint = 0.55f
        setupDisplay(width = 100, height = 100, hdrBrightnessData = createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            transitionPoint = transitionPoint,
            sdrToHdrRatioSpline = mockSpline
        ))
        // hdr size = 5_100
        val desiredMaxHdrRatio = 8f
        setupHdrLayer(width = 100, height = 51, maxHdrRatio = desiredMaxHdrRatio)

        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, desiredMaxHdrRatio, mockSpline)).thenReturn(0.85f)

        assertModifierState(
            maxBrightness = transitionPoint,
            hdrRatio = desiredMaxHdrRatio,
            hdrBrightness = transitionPoint,
            spline = mockSpline
        )
    }

    @Test
    fun `test HBM_HDR mode`() {
        initHdrModifier()
        // screen size = 10_000
        setupDisplay(width = 100, height = 100, hdrBrightnessData = createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            transitionPoint = 0.55f,
            sdrToHdrRatioSpline = mockSpline
        ))
        // hdr size = 7_100
        val desiredMaxHdrRatio = 8f
        setupHdrLayer(width = 100, height = 71, maxHdrRatio = desiredMaxHdrRatio)

        val expectedHdrBrightness = 0.92f
        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, desiredMaxHdrRatio, mockSpline)).thenReturn(expectedHdrBrightness)

        assertModifierState(
            hdrRatio = desiredMaxHdrRatio,
            hdrBrightness = expectedHdrBrightness,
            spline = mockSpline
        )
    }

    @Test
    fun `test display change no HDR content`() {
        initHdrModifier()
        setupDisplay(width = 100, height = 100)
        assertModifierState()
        clearInvocations(mockChangeListener)
        // display change, new instance of HdrBrightnessData
        setupDisplay(width = 100, height = 100)

        assertModifierState()
        verify(mockChangeListener, never()).onChanged()
    }

    @Test
    fun `test display change with HDR content`() {
        initHdrModifier()
        setupDisplay(width = 100, height = 100)
        setupHdrLayer(width = 100, height = 100, maxHdrRatio = 5f)
        assertModifierState(
            hdrBrightness = 0f,
            hdrRatio = 5f,
            spline = mockSpline
        )
        clearInvocations(mockChangeListener)
        // display change, new instance of HdrBrightnessData
        setupDisplay(width = 100, height = 100)

        assertModifierState(
            hdrBrightness = 0f,
            hdrRatio = 5f,
            spline = mockSpline
        )
        // new instance of HdrBrightnessData received, notify listener
        verify(mockChangeListener).onChanged()
    }

    @Test
    fun `test ambient lux decrease above maxBrightnessLimits no HDR`() {
        initHdrModifier()
        modifier.setAmbientLux(1000f)
        setupDisplay(width = 100, height = 100, hdrBrightnessData = createHdrBrightnessData(
            maxBrightnessLimits = mapOf(Pair(500f, 0.6f))
        ))

        modifier.setAmbientLux(500f)
        // verify debounce is not scheduled
        assertThat(testHandler.hasMessagesOrCallbacks()).isFalse()

        assertModifierState()
        verify(mockDisplayDeviceConfig, never()).getHdrBrightnessFromSdr(any(), any(), any())
    }

    @Test
    fun `test ambient lux decrease above maxBrightnessLimits with HDR`() {
        initHdrModifier()
        modifier.setAmbientLux(1000f)
        setupDisplay(width = 200, height = 200, hdrBrightnessData = createHdrBrightnessData(
            maxBrightnessLimits = mapOf(Pair(500f, 0.6f)),
            sdrToHdrRatioSpline = mockSpline
        ))
        setupHdrLayer(width = 200, height = 200, maxHdrRatio = 8f)

        modifier.setAmbientLux(500f)

        // verify debounce is not scheduled
        assertThat(testHandler.hasMessagesOrCallbacks()).isFalse()

        val hdrBrightnessFromSdr = 0.83f
        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, 8f, mockSpline)).thenReturn(hdrBrightnessFromSdr)

        assertModifierState(
            hdrBrightness = hdrBrightnessFromSdr,
            spline = mockSpline,
            hdrRatio = 8f
        )
    }

    @Test
    fun `test ambient lux decrease below maxBrightnessLimits no HDR`() {
        initHdrModifier()
        modifier.setAmbientLux(1000f)
        setupDisplay(width = 100, height = 100, hdrBrightnessData = createHdrBrightnessData(
            maxBrightnessLimits = mapOf(Pair(500f, 0.6f))
        ))

        modifier.setAmbientLux(499f)
        // verify debounce is not scheduled
        assertThat(testHandler.hasMessagesOrCallbacks()).isFalse()

        assertModifierState()
        verify(mockDisplayDeviceConfig, never()).getHdrBrightnessFromSdr(any(), any(), any())
    }

    @Test
    fun `test ambient lux decrease below maxBrightnessLimits with HDR`() {
        initHdrModifier()
        modifier.setAmbientLux(1000f)
        val maxBrightness = 0.6f
        val brightnessDecreaseDebounceMillis = 2800L
        val animationRate = 0.01f
        setupDisplay(width = 200, height = 200, hdrBrightnessData = createHdrBrightnessData(
            maxBrightnessLimits = mapOf(Pair(500f, maxBrightness)),
            brightnessDecreaseDebounceMillis = brightnessDecreaseDebounceMillis,
            screenBrightnessRampDecrease = animationRate,
            sdrToHdrRatioSpline = mockSpline,
        ))
        setupHdrLayer(width = 200, height = 200, maxHdrRatio = 8f)

        modifier.setAmbientLux(499f)

        val hdrBrightnessFromSdr = 0.83f
        whenever(mockDisplayDeviceConfig.getHdrBrightnessFromSdr(
            0f, 8f, mockSpline)).thenReturn(hdrBrightnessFromSdr)
        // debounce with brightnessDecreaseDebounceMillis, no changes to the state just yet
        assertModifierState(
            hdrBrightness = hdrBrightnessFromSdr,
            spline = mockSpline,
            hdrRatio = 8f
        )

        // verify debounce is scheduled
        assertThat(testHandler.hasMessagesOrCallbacks()).isTrue()
        val msgInfo = testHandler.pendingMessages.peek()
        assertSendTime(brightnessDecreaseDebounceMillis, msgInfo!!.sendTime)
        clearInvocations(mockChangeListener)

        // triggering debounce, state changes
        testHandler.flush()

        verify(mockChangeListener).onChanged()

        assertModifierState(
            hdrBrightness = maxBrightness,
            spline = mockSpline,
            hdrRatio = 8f,
            maxBrightness = maxBrightness,
            animationRate = animationRate
        )
    }

    private fun setupHdrLayer(width: Int = 100, height: Int = 100, maxHdrRatio: Float = 0.8f) {
        testInjector.registeredHdrListener!!.onHdrInfoChanged(
            mockDisplayBinder, 1, width, height, 0, maxHdrRatio
        )
        testHandler.flush()
    }

    private fun setupDisplay(
        width: Int = 100,
        height: Int = 100,
        hdrBrightnessData: HdrBrightnessData? = createHdrBrightnessData(
            minimumHdrPercentOfScreenForNbm = 0.5f,
            minimumHdrPercentOfScreenForHbm = 0.7f,
            transitionPoint = 0.68f,
            sdrToHdrRatioSpline = mockSpline
        )
    ) {
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(hdrBrightnessData)
        modifier.onDisplayChanged(createDisplayDeviceData(
            mockDisplayDeviceConfig, mockDisplayBinder,
            width = width,
            height = height
        ))
        testHandler.flush()
    }

    private fun initHdrModifier(hdrBrightnessData: HdrBrightnessData? = createHdrBrightnessData()) {
        whenever(mockDisplayDeviceConfig.hdrBrightnessData).thenReturn(hdrBrightnessData)
        modifier = HdrBrightnessModifier(testHandler, mockChangeListener, testInjector, dummyData)
        testHandler.flush()
    }

    // MsgInfo.sendTime is calculated first by adding SystemClock.uptimeMillis()
    // (in Handler.sendMessageDelayed) and then by subtracting SystemClock.uptimeMillis()
    // (in TestHandler.sendMessageAtTime, there might be several milliseconds difference between
    // SystemClock.uptimeMillis() calls, and subtracted value might be greater than added.
    private fun assertSendTime(expectedTime: Long, sendTime: Long) {
        assertThat(sendTime).isAtMost(expectedTime)
        assertThat(sendTime).isGreaterThan(expectedTime - SEND_TIME_TOLERANCE)
    }

    private fun assertModifierState(
        maxBrightness: Float = BRIGHTNESS_MAX,
        hdrRatio: Float = DEFAULT_MAX_HDR_SDR_RATIO,
        spline: Spline? = null,
        hdrBrightness: Float = BRIGHTNESS_NOT_SET,
        animationRate: Float = CUSTOM_ANIMATION_RATE_NOT_SET
    ) {
        val modifierState = ModifiersAggregatedState()
        modifier.applyStateChange(modifierState)

        assertThat(modifierState.mMaxHdrBrightness).isEqualTo(maxBrightness)
        assertThat(modifierState.mMaxDesiredHdrRatio).isEqualTo(hdrRatio)
        assertThat(modifierState.mSdrHdrRatioSpline).isEqualTo(spline)

        val stateBuilder = DisplayBrightnessState.builder()
        modifier.apply(mockRequest, stateBuilder)

        assertThat(stateBuilder.hdrBrightness).isEqualTo(hdrBrightness)
        assertThat(stateBuilder.customAnimationRate).isEqualTo(animationRate)
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