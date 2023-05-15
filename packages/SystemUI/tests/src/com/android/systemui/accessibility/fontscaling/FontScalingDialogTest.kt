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
package com.android.systemui.accessibility.fontscaling

import android.os.Handler
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val ON: Int = 1
private const val OFF: Int = 0

/** Tests for [FontScalingDialog]. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class FontScalingDialogTest : SysuiTestCase() {
    private val MIN_UPDATE_INTERVAL_MS: Long = 800
    private val CHANGE_BY_SEEKBAR_DELAY_MS: Long = 100
    private val CHANGE_BY_BUTTON_DELAY_MS: Long = 300
    private lateinit var fontScalingDialog: FontScalingDialog
    private lateinit var systemSettings: SystemSettings
    private lateinit var secureSettings: SecureSettings
    private lateinit var systemClock: FakeSystemClock
    private lateinit var backgroundDelayableExecutor: FakeExecutor
    private val fontSizeValueArray: Array<String> =
        mContext
            .getResources()
            .getStringArray(com.android.settingslib.R.array.entryvalues_font_size)

    @Captor
    private lateinit var seekBarChangeCaptor: ArgumentCaptor<SeekBar.OnSeekBarChangeListener>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val mainHandler = Handler(TestableLooper.get(this).getLooper())
        systemSettings = FakeSettings()
        // Guarantee that the systemSettings always starts with the default font scale.
        systemSettings.putFloat(Settings.System.FONT_SCALE, 1.0f)
        secureSettings = FakeSettings()
        systemClock = FakeSystemClock()
        backgroundDelayableExecutor = FakeExecutor(systemClock)
        fontScalingDialog =
            FontScalingDialog(
                mContext,
                systemSettings,
                secureSettings,
                systemClock,
                mainHandler,
                backgroundDelayableExecutor
            )
    }

    @Test
    fun showTheDialog_seekbarIsShowingCorrectProgress() {
        fontScalingDialog.show()

        val seekBar: SeekBar = fontScalingDialog.findViewById<SeekBar>(R.id.seekbar)!!
        val progress: Int = seekBar.getProgress()
        val currentScale = systemSettings.getFloat(Settings.System.FONT_SCALE, /* def= */ 1.0f)

        assertThat(currentScale).isEqualTo(fontSizeValueArray[progress].toFloat())

        fontScalingDialog.dismiss()
    }

    @Test
    fun progressIsZero_clickIconEnd_seekBarProgressIncreaseOne_fontSizeScaled() {
        fontScalingDialog.show()

        val iconEndFrame: ViewGroup = fontScalingDialog.findViewById(R.id.icon_end_frame)!!
        val seekBarWithIconButtonsView: SeekBarWithIconButtonsView =
            fontScalingDialog.findViewById(R.id.font_scaling_slider)!!
        val seekBar: SeekBar = fontScalingDialog.findViewById(R.id.seekbar)!!

        seekBarWithIconButtonsView.setProgress(0)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        iconEndFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        val currentScale = systemSettings.getFloat(Settings.System.FONT_SCALE, /* def= */ 1.0f)
        assertThat(seekBar.getProgress()).isEqualTo(1)
        assertThat(currentScale).isEqualTo(fontSizeValueArray[1].toFloat())

        fontScalingDialog.dismiss()
    }

    @Test
    fun progressIsMax_clickIconStart_seekBarProgressDecreaseOne_fontSizeScaled() {
        fontScalingDialog.show()

        val iconStartFrame: ViewGroup = fontScalingDialog.findViewById(R.id.icon_start_frame)!!
        val seekBarWithIconButtonsView: SeekBarWithIconButtonsView =
            fontScalingDialog.findViewById(R.id.font_scaling_slider)!!
        val seekBar: SeekBar = fontScalingDialog.findViewById(R.id.seekbar)!!

        seekBarWithIconButtonsView.setProgress(fontSizeValueArray.size - 1)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        iconStartFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        val currentScale = systemSettings.getFloat(Settings.System.FONT_SCALE, /* def= */ 1.0f)
        assertThat(seekBar.getProgress()).isEqualTo(fontSizeValueArray.size - 2)
        assertThat(currentScale)
            .isEqualTo(fontSizeValueArray[fontSizeValueArray.size - 2].toFloat())

        fontScalingDialog.dismiss()
    }

    @Test
    fun progressChanged_keyWasNotSetBefore_fontScalingHasBeenChangedIsOn() {
        fontScalingDialog.show()

        val seekBarWithIconButtonsView: SeekBarWithIconButtonsView =
            fontScalingDialog.findViewById(R.id.font_scaling_slider)!!
        secureSettings.putInt(Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED, OFF)

        // Default seekbar progress for font size is 1, set it to another progress 0
        seekBarWithIconButtonsView.setProgress(0)
        backgroundDelayableExecutor.runAllReady()

        val currentSettings =
            secureSettings.getInt(
                Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
                /* def = */ OFF
            )
        assertThat(currentSettings).isEqualTo(ON)

        fontScalingDialog.dismiss()
    }

    @Test
    fun dragSeekbar_systemFontSizeSettingsDoesNotChange() {
        fontScalingDialog = spy(fontScalingDialog)
        val slider: SeekBarWithIconButtonsView = spy(SeekBarWithIconButtonsView(mContext))
        whenever(
                fontScalingDialog.findViewById<SeekBarWithIconButtonsView>(R.id.font_scaling_slider)
            )
            .thenReturn(slider)
        fontScalingDialog.show()
        verify(slider).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))
        val seekBar: SeekBar = slider.findViewById(R.id.seekbar)!!

        // Default seekbar progress for font size is 1, simulate dragging to 0 without
        // releasing the finger.
        seekBarChangeCaptor.value.onStartTrackingTouch(seekBar)
        // Update seekbar progress. This will trigger onProgressChanged in the
        // OnSeekBarChangeListener and the seekbar could get updated progress value
        // in onStopTrackingTouch.
        seekBar.progress = 0
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // Verify that the scale of font size remains the default value 1.0f.
        var systemScale = systemSettings.getFloat(Settings.System.FONT_SCALE, /* def= */ 1.0f)
        assertThat(systemScale).isEqualTo(1.0f)

        // Simulate releasing the finger from the seekbar.
        seekBarChangeCaptor.value.onStopTrackingTouch(seekBar)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // Verify that the scale of font size has been updated.
        systemScale = systemSettings.getFloat(Settings.System.FONT_SCALE, /* def= */ 1.0f)
        assertThat(systemScale).isEqualTo(fontSizeValueArray[0].toFloat())

        fontScalingDialog.dismiss()
    }

    @Test
    fun dragSeekBar_createTextPreview() {
        fontScalingDialog = spy(fontScalingDialog)
        val slider: SeekBarWithIconButtonsView = spy(SeekBarWithIconButtonsView(mContext))
        whenever(
                fontScalingDialog.findViewById<SeekBarWithIconButtonsView>(R.id.font_scaling_slider)
            )
            .thenReturn(slider)
        fontScalingDialog.show()
        verify(slider).setOnSeekBarChangeListener(capture(seekBarChangeCaptor))
        val seekBar: SeekBar = slider.findViewById(R.id.seekbar)!!

        // Default seekbar progress for font size is 1, simulate dragging to 0 without
        // releasing the finger
        seekBarChangeCaptor.value.onStartTrackingTouch(seekBar)
        seekBarChangeCaptor.value.onProgressChanged(
            seekBar,
            /* progress= */ 0,
            /* fromUser= */ false
        )
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        verify(fontScalingDialog).createTextPreview(/* index= */ 0)
        fontScalingDialog.dismiss()
    }
}
