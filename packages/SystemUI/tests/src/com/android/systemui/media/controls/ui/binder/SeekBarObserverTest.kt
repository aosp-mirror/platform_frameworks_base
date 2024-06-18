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

package com.android.systemui.media.controls.ui.binder

import android.animation.Animator
import android.animation.ObjectAnimator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SeekBarObserverTest : SysuiTestCase() {

    private val disabledHeight = 1
    private val enabledHeight = 2

    private lateinit var observer: SeekBarObserver
    @Mock private lateinit var mockSeekbarAnimator: ObjectAnimator
    @Mock private lateinit var mockHolder: MediaViewHolder
    @Mock private lateinit var mockSquigglyProgress: SquigglyProgress
    private lateinit var seekBarView: SeekBar
    private lateinit var scrubbingElapsedTimeView: TextView
    private lateinit var scrubbingTotalTimeView: TextView

    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        context.orCreateTestableResources.addOverride(
            R.dimen.qs_media_enabled_seekbar_height,
            enabledHeight
        )
        context.orCreateTestableResources.addOverride(
            R.dimen.qs_media_disabled_seekbar_height,
            disabledHeight
        )

        seekBarView = SeekBar(context)
        seekBarView.progressDrawable = mockSquigglyProgress
        scrubbingElapsedTimeView = TextView(context)
        scrubbingTotalTimeView = TextView(context)
        whenever(mockHolder.seekBar).thenReturn(seekBarView)
        whenever(mockHolder.scrubbingElapsedTimeView).thenReturn(scrubbingElapsedTimeView)
        whenever(mockHolder.scrubbingTotalTimeView).thenReturn(scrubbingTotalTimeView)

        observer =
            object : SeekBarObserver(mockHolder) {
                override fun buildResetAnimator(targetTime: Int): Animator {
                    return mockSeekbarAnimator
                }
            }
    }

    @Test
    fun seekBarGone() {
        // WHEN seek bar is disabled
        val isEnabled = false
        val data = SeekBarViewModel.Progress(isEnabled, false, false, false, null, 0, false)
        observer.onChanged(data)
        // THEN seek bar shows just a thin line with no text
        assertThat(seekBarView.isEnabled()).isFalse()
        assertThat(seekBarView.getThumb().getAlpha()).isEqualTo(0)
        assertThat(seekBarView.contentDescription).isEqualTo("")
        assertThat(seekBarView.maxHeight).isEqualTo(disabledHeight)
    }

    @Test
    fun seekBarVisible() {
        // WHEN seek bar is enabled
        val isEnabled = true
        val data = SeekBarViewModel.Progress(isEnabled, true, false, false, 3000, 12000, true)
        observer.onChanged(data)
        // THEN seek bar is visible and thick
        assertThat(seekBarView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(seekBarView.maxHeight).isEqualTo(enabledHeight)
    }

    @Test
    fun seekBarProgress() {
        // WHEN part of the track has been played
        val data = SeekBarViewModel.Progress(true, true, true, false, 3000, 120000, true)
        observer.onChanged(data)
        // THEN seek bar shows the progress
        assertThat(seekBarView.progress).isEqualTo(3000)
        assertThat(seekBarView.max).isEqualTo(120000)

        val desc = context.getString(R.string.controls_media_seekbar_description, "00:03", "02:00")
        assertThat(seekBarView.contentDescription).isEqualTo(desc)
    }

    @Test
    fun seekBarDisabledWhenSeekNotAvailable() {
        // WHEN seek is not available
        val isSeekAvailable = false
        val data =
            SeekBarViewModel.Progress(true, isSeekAvailable, false, false, 3000, 120000, false)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isFalse()
    }

    @Test
    fun seekBarEnabledWhenSeekNotAvailable() {
        // WHEN seek is available
        val isSeekAvailable = true
        val data =
            SeekBarViewModel.Progress(true, isSeekAvailable, false, false, 3000, 120000, false)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isTrue()
    }

    @Test
    fun seekBarPlayingNotScrubbing() {
        // WHEN playing
        val isPlaying = true
        val isScrubbing = false
        val data = SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, true)
        observer.onChanged(data)
        // THEN progress drawable is animating
        verify(mockSquigglyProgress).animate = true
    }

    @Test
    fun seekBarNotPlayingNotScrubbing() {
        // WHEN not playing & not scrubbing
        val isPlaying = false
        val isScrubbing = false
        val data = SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, true)
        observer.onChanged(data)
        // THEN progress drawable is not animating
        verify(mockSquigglyProgress).animate = false
    }

    @Test
    fun seekbarNotListeningNotScrubbingPlaying() {
        // WHEN playing
        val isPlaying = true
        val isScrubbing = false
        val data =
            SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, false)
        observer.onChanged(data)
        // THEN progress drawable is not animating
        verify(mockSquigglyProgress).animate = false
    }

    @Test
    fun seekBarPlayingScrubbing() {
        // WHEN playing & scrubbing
        val isPlaying = true
        val isScrubbing = true
        val data = SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, true)
        observer.onChanged(data)
        // THEN progress drawable is not animating
        verify(mockSquigglyProgress).animate = false
    }

    @Test
    fun seekBarNotPlayingScrubbing() {
        // WHEN playing & scrubbing
        val isPlaying = false
        val isScrubbing = true
        val data = SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, true)
        observer.onChanged(data)
        // THEN progress drawable is not animating
        verify(mockSquigglyProgress).animate = false
    }

    @Test
    fun seekBarProgress_enabledAndScrubbing_timeViewsHaveTime() {
        val isEnabled = true
        val isScrubbing = true
        val data = SeekBarViewModel.Progress(isEnabled, true, true, isScrubbing, 3000, 120000, true)

        observer.onChanged(data)

        assertThat(scrubbingElapsedTimeView.text).isEqualTo("00:03")
        assertThat(scrubbingTotalTimeView.text).isEqualTo("02:00")
    }

    @Test
    fun seekBarProgress_disabledAndScrubbing_timeViewsEmpty() {
        val isEnabled = false
        val isScrubbing = true
        val data = SeekBarViewModel.Progress(isEnabled, true, true, isScrubbing, 3000, 120000, true)

        observer.onChanged(data)

        assertThat(scrubbingElapsedTimeView.text).isEqualTo("")
        assertThat(scrubbingTotalTimeView.text).isEqualTo("")
    }

    @Test
    fun seekBarProgress_enabledAndNotScrubbing_timeViewsEmpty() {
        val isEnabled = true
        val isScrubbing = false
        val data = SeekBarViewModel.Progress(isEnabled, true, true, isScrubbing, 3000, 120000, true)

        observer.onChanged(data)

        assertThat(scrubbingElapsedTimeView.text).isEqualTo("")
        assertThat(scrubbingTotalTimeView.text).isEqualTo("")
    }

    @Test
    fun seekBarJumpAnimation() {
        val data0 = SeekBarViewModel.Progress(true, true, true, false, 4000, 120000, true)
        val data1 = SeekBarViewModel.Progress(true, true, true, false, 10, 120000, true)

        // Set initial position of progress bar
        observer.onChanged(data0)
        assertThat(seekBarView.progress).isEqualTo(4000)
        assertThat(seekBarView.max).isEqualTo(120000)

        // Change to second data & confirm no change to position (due to animation delay)
        observer.onChanged(data1)
        assertThat(seekBarView.progress).isEqualTo(4000)
        verify(mockSeekbarAnimator).start()
    }

    @Test
    fun seekbarActive_animationsDisabled() {
        // WHEN playing, but animations have been disabled
        observer.animationEnabled = false
        val isPlaying = true
        val isScrubbing = false
        val data = SeekBarViewModel.Progress(true, true, isPlaying, isScrubbing, 3000, 120000, true)
        observer.onChanged(data)

        // THEN progress drawable does not animate
        verify(mockSquigglyProgress).animate = false
    }
}
