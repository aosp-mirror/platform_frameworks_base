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
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.test.filters.SmallTest
import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.statusbar.policy.BrightnessMirrorController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.notNull
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BrightnessSliderTest : SysuiTestCase() {

    @Mock
    private lateinit var rootView: View
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

    @Captor
    private lateinit var seekBarChangeCaptor: ArgumentCaptor<SeekBar.OnSeekBarChangeListener>
    @Mock
    private lateinit var seekBar: SeekBar
    @Captor
    private lateinit var checkedChangeCaptor: ArgumentCaptor<CompoundButton.OnCheckedChangeListener>
    @Mock
    private lateinit var compoundButton: CompoundButton
    private var mFalsingManager: FalsingManagerFake = FalsingManagerFake()

    private lateinit var mController: BrightnessSlider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(mirrorController.toggleSlider).thenReturn(mirror)
        whenever(motionEvent.copy()).thenReturn(motionEvent)

        mController = BrightnessSlider(rootView, brightnessSliderView, mFalsingManager)
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

        verify(brightnessSliderView).setOnCheckedChangeListener(notNull())
        verify(brightnessSliderView).setOnSeekBarChangeListener(notNull())
    }

    @Test
    fun testAllListenersRemovedOnDettach() {
        mController.onViewAttached()
        mController.onViewDetached()

        verify(brightnessSliderView).setOnSeekBarChangeListener(isNull())
        verify(brightnessSliderView).setOnCheckedChangeListener(isNull())
        verify(brightnessSliderView).setOnDispatchTouchEventListener(isNull())
    }

    @Test
    fun testEnforceAdminRelayed() {
        mController.setEnforcedAdmin(enforcedAdmin)
        verify(brightnessSliderView).setEnforcedAdmin(enforcedAdmin)
    }

    @Test
    fun testNullMirrorControllerNotTrackingTouch() {
        mController.setMirrorControllerAndMirror(null)

        verify(brightnessSliderView, never()).max
        verify(brightnessSliderView, never()).value
        verify(brightnessSliderView, never()).isChecked
        verify(brightnessSliderView).setOnDispatchTouchEventListener(isNull())
    }

    @Test
    fun testNullMirrorNotTrackingTouch() {
        whenever(mirrorController.toggleSlider).thenReturn(null)

        mController.setMirrorControllerAndMirror(mirrorController)

        verify(brightnessSliderView, never()).max
        verify(brightnessSliderView, never()).value
        verify(brightnessSliderView, never()).isChecked
        verify(brightnessSliderView).setOnDispatchTouchEventListener(isNull())
    }

    @Test
    fun testSettingMirrorControllerReliesValuesAndSetsTouchTracking() {
        val maxValue = 100
        val progress = 30
        val checked = true
        whenever(brightnessSliderView.max).thenReturn(maxValue)
        whenever(brightnessSliderView.value).thenReturn(progress)
        whenever(brightnessSliderView.isChecked).thenReturn(checked)

        mController.setMirrorControllerAndMirror(mirrorController)

        verify(mirror).max = maxValue
        verify(mirror).isChecked = checked
        verify(mirror).value = progress
        verify(brightnessSliderView).setOnDispatchTouchEventListener(notNull())
    }

    @Test
    fun testSetCheckedRelayed_true() {
        mController.isChecked = true
        verify(brightnessSliderView).isChecked = true
    }

    @Test
    fun testSetCheckedRelayed_false() {
        mController.isChecked = false
        verify(brightnessSliderView).isChecked = false
    }

    @Test
    fun testGetChecked() {
        whenever(brightnessSliderView.isChecked).thenReturn(true)

        assertThat(mController.isChecked).isTrue()

        whenever(brightnessSliderView.isChecked).thenReturn(false)

        assertThat(mController.isChecked).isFalse()
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
        whenever(brightnessSliderView.isChecked).thenReturn(true)

        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onProgressChanged(seekBar, 23, true)

        verify(listener).onChanged(anyBoolean(), eq(true), eq(23), eq(false))
    }

    @Test
    fun testSeekBarTrackingStarted() {
        val parent = mock(ViewGroup::class.java)
        whenever(brightnessSliderView.value).thenReturn(42)
        whenever(brightnessSliderView.parent).thenReturn(parent)
        whenever(brightnessSliderView.isChecked).thenReturn(true)

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onStartTrackingTouch(seekBar)

        verify(listener).onChanged(eq(true), eq(true), eq(42), eq(false))
        verify(mirrorController).showMirror()
        verify(mirrorController).setLocation(parent)
    }

    @Test
    fun testSeekBarTrackingStopped() {
        whenever(brightnessSliderView.value).thenReturn(23)
        whenever(brightnessSliderView.isChecked).thenReturn(true)

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))

        seekBarChangeCaptor.value.onStopTrackingTouch(seekBar)

        verify(listener).onChanged(eq(false), eq(true), eq(23), eq(true))
        verify(mirrorController).hideMirror()
    }

    @Test
    fun testButtonCheckedChanged_false() {
        val checked = false

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnCheckedChangeListener(capture(checkedChangeCaptor))

        checkedChangeCaptor.value.onCheckedChanged(compoundButton, checked)

        verify(brightnessSliderView).enableSlider(!checked)
        verify(listener).onChanged(anyBoolean(), eq(checked), anyInt(), eq(false))
        // Called once with false when the mirror is set
        verify(mirror, times(2)).isChecked = checked
    }

    @Test
    fun testButtonCheckedChanged_true() {
        val checked = true

        mController.onViewAttached()
        mController.setMirrorControllerAndMirror(mirrorController)
        verify(brightnessSliderView).setOnCheckedChangeListener(capture(checkedChangeCaptor))

        checkedChangeCaptor.value.onCheckedChanged(compoundButton, checked)

        verify(brightnessSliderView).enableSlider(!checked)
        verify(listener).onChanged(anyBoolean(), eq(checked), anyInt(), eq(false))
        verify(mirror).isChecked = checked
    }
}