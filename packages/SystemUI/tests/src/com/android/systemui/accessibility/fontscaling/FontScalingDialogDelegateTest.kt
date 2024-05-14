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

import android.content.res.Configuration
import android.os.Handler
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView.OnSeekBarWithIconButtonsChangeListener
import com.android.systemui.model.SysUiState
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

private const val ON: Int = 1
private const val OFF: Int = 0

/** Tests for [FontScalingDialogDelegate]. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class FontScalingDialogDelegateTest : SysuiTestCase() {
    private lateinit var fontScalingDialogDelegate: FontScalingDialogDelegate
    private lateinit var dialog: SystemUIDialog
    private lateinit var systemSettings: SystemSettings
    private lateinit var secureSettings: SecureSettings
    private lateinit var systemClock: FakeSystemClock
    private lateinit var backgroundDelayableExecutor: FakeExecutor
    private lateinit var testableLooper: TestableLooper
    private val fontSizeValueArray: Array<String> =
        mContext
            .getResources()
            .getStringArray(com.android.settingslib.R.array.entryvalues_font_size)

    @Mock private lateinit var dialogManager: SystemUIDialogManager
    @Mock private lateinit var dialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var sysuiState: SysUiState
    @Mock private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        val mainHandler = Handler(testableLooper.looper)
        systemSettings = FakeSettings()
        // Guarantee that the systemSettings always starts with the default font scale.
        systemSettings.putFloatForUser(Settings.System.FONT_SCALE, 1.0f, userTracker.userId)
        secureSettings = FakeSettings()
        systemClock = FakeSystemClock()
        backgroundDelayableExecutor = FakeExecutor(systemClock)
        whenever(sysuiState.setFlag(anyLong(), anyBoolean())).thenReturn(sysuiState)

        fontScalingDialogDelegate =
            spy(
                FontScalingDialogDelegate(
                    mContext,
                    dialogFactory,
                    LayoutInflater.from(mContext),
                    systemSettings,
                    secureSettings,
                    systemClock,
                    userTracker,
                    mainHandler,
                    backgroundDelayableExecutor
                )
            )

        dialog =
            SystemUIDialog(
                mContext,
                0,
                DEFAULT_DISMISS_ON_DEVICE_LOCK,
                dialogManager,
                sysuiState,
                fakeBroadcastDispatcher,
                mDialogTransitionAnimator,
                fontScalingDialogDelegate
            )

        whenever(dialogFactory.create(any(), any())).thenReturn(dialog)
    }

    @Test
    fun showTheDialog_seekbarIsShowingCorrectProgress() {
        dialog.show()

        val seekBar: SeekBar = dialog.findViewById<SeekBar>(R.id.seekbar)!!
        val progress: Int = seekBar.getProgress()
        val currentScale =
            systemSettings.getFloatForUser(
                Settings.System.FONT_SCALE,
                /* def= */ 1.0f,
                userTracker.userId
            )

        assertThat(currentScale).isEqualTo(fontSizeValueArray[progress].toFloat())

        dialog.dismiss()
    }

    @Test
    fun progressIsZero_clickIconEnd_seekBarProgressIncreaseOne_fontSizeScaled() {
        dialog.show()

        val iconEndFrame: ViewGroup = dialog.findViewById(R.id.icon_end_frame)!!
        val seekBarWithIconButtonsView: SeekBarWithIconButtonsView =
            dialog.findViewById(R.id.font_scaling_slider)!!
        val seekBar: SeekBar = dialog.findViewById(R.id.seekbar)!!

        seekBarWithIconButtonsView.setProgress(0)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        iconEndFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        val currentScale =
            systemSettings.getFloatForUser(
                Settings.System.FONT_SCALE,
                /* def= */ 1.0f,
                userTracker.userId
            )
        assertThat(seekBar.getProgress()).isEqualTo(1)
        assertThat(currentScale).isEqualTo(fontSizeValueArray[1].toFloat())

        dialog.dismiss()
    }

    @Test
    fun progressIsMax_clickIconStart_seekBarProgressDecreaseOne_fontSizeScaled() {
        dialog.show()

        val iconStartFrame: ViewGroup = dialog.findViewById(R.id.icon_start_frame)!!
        val seekBarWithIconButtonsView: SeekBarWithIconButtonsView =
            dialog.findViewById(R.id.font_scaling_slider)!!
        val seekBar: SeekBar = dialog.findViewById(R.id.seekbar)!!

        seekBarWithIconButtonsView.setProgress(fontSizeValueArray.size - 1)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        iconStartFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        val currentScale =
            systemSettings.getFloatForUser(
                Settings.System.FONT_SCALE,
                /* def= */ 1.0f,
                userTracker.userId
            )
        assertThat(seekBar.getProgress()).isEqualTo(fontSizeValueArray.size - 2)
        assertThat(currentScale)
            .isEqualTo(fontSizeValueArray[fontSizeValueArray.size - 2].toFloat())

        dialog.dismiss()
    }

    @Test
    fun progressChanged_keyWasNotSetBefore_fontScalingHasBeenChangedIsOn() {
        dialog.show()

        val iconStartFrame: ViewGroup = dialog.findViewById(R.id.icon_start_frame)!!
        secureSettings.putIntForUser(
            Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
            OFF,
            userTracker.userId
        )

        // Default seekbar progress for font size is 1, click start icon to decrease the progress
        iconStartFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        val currentSettings =
            secureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
                /* def = */ OFF,
                userTracker.userId
            )
        assertThat(currentSettings).isEqualTo(ON)

        dialog.dismiss()
    }

    @Test
    fun dragSeekbar_systemFontSizeSettingsDoesNotChange() {
        dialog.show()

        val slider = dialog.findViewById<SeekBarWithIconButtonsView>(R.id.font_scaling_slider)!!
        val changeListener = slider.onSeekBarWithIconButtonsChangeListener

        val seekBar: SeekBar = slider.findViewById(R.id.seekbar)!!

        // Default seekbar progress for font size is 1, simulate dragging to 0 without
        // releasing the finger.
        changeListener.onStartTrackingTouch(seekBar)
        // Update seekbar progress. This will trigger onProgressChanged in the
        // OnSeekBarChangeListener and the seekbar could get updated progress value
        // in onStopTrackingTouch.
        seekBar.progress = 0
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // Verify that the scale of font size remains the default value 1.0f.
        var systemScale =
            systemSettings.getFloatForUser(
                Settings.System.FONT_SCALE,
                /* def= */ 1.0f,
                userTracker.userId
            )
        assertThat(systemScale).isEqualTo(1.0f)

        // Simulate releasing the finger from the seekbar.
        changeListener.onStopTrackingTouch(seekBar)
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // SeekBar interaction is finalized.
        changeListener.onUserInteractionFinalized(
            seekBar,
            OnSeekBarWithIconButtonsChangeListener.ControlUnitType.SLIDER
        )
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // Verify that the scale of font size has been updated.
        systemScale =
            systemSettings.getFloatForUser(
                Settings.System.FONT_SCALE,
                /* def= */ 1.0f,
                userTracker.userId
            )
        assertThat(systemScale).isEqualTo(fontSizeValueArray[0].toFloat())

        dialog.dismiss()
    }

    @Test
    fun dragSeekBar_createTextPreview() {
        dialog.show()
        val slider = dialog.findViewById<SeekBarWithIconButtonsView>(R.id.font_scaling_slider)!!
        val changeListener = slider.onSeekBarWithIconButtonsChangeListener

        val seekBar: SeekBar = slider.findViewById(R.id.seekbar)!!

        // Default seekbar progress for font size is 1, simulate dragging to 0 without
        // releasing the finger
        changeListener.onStartTrackingTouch(seekBar)
        changeListener.onProgressChanged(seekBar, /* progress= */ 0, /* fromUser= */ false)
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        verify(fontScalingDialogDelegate).createTextPreview(/* index= */ 0)
        dialog.dismiss()
    }

    @Test
    fun changeFontSize_buttonIsDisabledBeforeFontSizeChangeFinishes() {
        dialog.show()

        val iconEndFrame: ViewGroup = dialog.findViewById(R.id.icon_end_frame)!!
        val doneButton: Button = dialog.findViewById(com.android.internal.R.id.button1)!!

        iconEndFrame.performClick()
        backgroundDelayableExecutor.runAllReady()
        backgroundDelayableExecutor.advanceClockToNext()
        backgroundDelayableExecutor.runAllReady()

        // Verify that the button is disabled before receiving onConfigurationChanged
        assertThat(doneButton.isEnabled).isFalse()

        val config = Configuration()
        config.fontScale = 1.15f
        dialog.onConfigurationChanged(config)
        testableLooper.processAllMessages()
        assertThat(doneButton.isEnabled).isTrue()
    }
}
