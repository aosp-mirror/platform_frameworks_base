/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.brightness

import android.testing.AndroidTestingRunner
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.haptics.slider.SeekableSliderHapticPlugin
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.BrightnessMirrorController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.notNull
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BrightnessSliderControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var brightnessSliderView: BrightnessSliderView
    @Mock
    private lateinit var enforcedAdmin: RestrictedLockUtils.EnforcedAdmin
    @Mock
    private lateinit var mirrorController: BrightnessMirrorController
    @Mock
    private lateinit var mirror: ToggleSlider
    @Mock
    private lateinit var motionEvent: MotionEvent
    @Mock
    private lateinit var listener: ToggleSlider.Listener
    @Mock
    private lateinit var vibratorHelper: VibratorHelper

    @Captor
    private lateinit var seekBarChangeCaptor: ArgumentCaptor<SeekBar.OnSeekBarChangeListener>
    @Mock
    private lateinit var seekBar: SeekBar
    private val uiEventLogger = UiEventLoggerFake()
    private var mFalsingManager: FalsingManagerFake = FalsingManagerFake()
    private val systemClock = FakeSystemClock()

    private lateinit var mController: BrightnessSliderController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(mirrorController.toggleSlider).thenReturn(mirror)
        whenever(motionEvent.copy()).thenReturn(motionEvent)
        whenever(vibratorHelper.getPrimitiveDurations(anyInt())).thenReturn(intArrayOf(0))

        mController =
            BrightnessSliderController(
                brightnessSliderView,
                mFalsingManager,
                uiEventLogger,
                SeekableSliderHapticPlugin(vibratorHelper, systemClock),
            )
        mController.init()
        mController.setOnChangedListener(listener)
    }

    @After
    fun tearDown() {
        mController.onViewDetached()
    }

    @Test
    fun testListenersAddedOnAttach() {
        mController.onViewAttached()

        verify(brightnessSliderView).setOnSeekBarChangeListener(notNull())
    }

    @Test
    fun testAllListenersRemovedOnDettach() {
        mController.onViewAttached()
        mController.onViewDetached()

        verify(brightnessSliderView).setOnSeekBarChangeListener(isNull())
        verify(brightnessSliderView).setOnDispatchTouchEventListener(isNull())
    }

    @Test
    fun testEnforceAdminRelayed() {
        mController.setEnforcedAdmin(enforcedAdmin)
        verify(brightnessSliderView).setEnforcedAdmin(enforcedAdmin)
    }

    @Test
    fun testNullMirrorNotTrackingTouch() {
        whenever(mirrorController.toggleSlider).thenReturn(null)

        mController.setMirrorControllerAndMirror(mirrorController)

        verify(brightnessSliderView, never()).max
        verify(brightnessSliderView, never()).value
        verify(brightnessSliderView).setOnDispatchTouchEventListener(isNull())
    }

    @Test
    fun testSettingMirrorControllerReliesValuesAndSetsTouchTracking() {
        val maxValue = 100
        val progress = 30
        val checked = true
        whenever(brightnessSliderView.max).thenReturn(maxValue)
        whenever(brightnessSliderView.value).thenReturn(progress)

        mController.setMirrorControllerAndMirror(mirrorController)

        verify(mirror).max = maxValue
        verify(mirror).value = progress
        verify(brightnessSliderView).setOnDispatchTouchEventListener(notNull())
    }

    @Test
    fun testSetMaxRelayed() {
        mController.max = 120
        verify(brightnessSliderView).max = 120
    }

    @Test
    fun testGetMax() {
        whenever(brightnessSliderView.max).thenReturn(40)

        assertThat(mController.max).isEqualTo(40)
    }

    @Test
    fun testSetValue() {
        mController.value = 30
        verify(brightnessSliderView).value = 30
    }

    @Test
    fun testGetValue() {
        whenever(brightnessSliderView.value).thenReturn(20)

        assertThat(mController.value).isEqualTo(20)
    }

    @Test
    fun testMirrorEventWithMirror() {
        mController.setMirrorControllerAndMirror(mirrorController)

        mController.mirrorTouchEvent(motionEvent)

        verify(mirror).mirrorTouchEvent(motionEvent)
        verify(brightnessSliderView, never()).dispatchTouchEvent(any(MotionEvent::class.java))
    }

    @Test
    fun testMirrorEventWithoutMirror_dispatchToView() {
        mController.mirrorTouchEvent(motionEvent)

        verify(brightnessSliderView).dispatchTouchEvent(motionEvent)
    }

    @Test
    fun testSeekBarProgressChanged() {
        mController.onViewAttached()

        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onProgressChanged(seekBar, 23, true)

        verify(listener).onChanged(anyBoolean(), eq(23), eq(false))
    }

    @Test
    fun testSeekBarTrackingStarted() {
        whenever(brightnessSliderView.value).thenReturn(42)
        val event = BrightnessSliderEvent.BRIGHTNESS_SLIDER_STARTED_TRACKING_TOUCH

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onStartTrackingTouch(seekBar)

        verify(listener).onChanged(eq(true), eq(42), eq(false))
        verify(mirrorController).showMirror()
        verify(mirrorController).setLocationAndSize(brightnessSliderView)
        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        assertThat(uiEventLogger.eventId(0)).isEqualTo(event.id)
    }

    @Test
    fun testSeekBarTrackingStopped() {
        whenever(brightnessSliderView.value).thenReturn(23)
        val event = BrightnessSliderEvent.BRIGHTNESS_SLIDER_STOPPED_TRACKING_TOUCH

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onStopTrackingTouch(seekBar)

        verify(listener).onChanged(eq(false), eq(23), eq(true))
        verify(mirrorController).hideMirror()
        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        assertThat(uiEventLogger.eventId(0)).isEqualTo(event.id)
    }
}