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

package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class SeekBarObserverTest : SysuiTestCase() {

    private val disabledHeight = 1
    private val enabledHeight = 2

    private lateinit var observer: SeekBarObserver
    @Mock private lateinit var mockHolder: PlayerViewHolder
    private lateinit var seekBarView: SeekBar
    private lateinit var elapsedTimeView: TextView
    private lateinit var totalTimeView: TextView

    @Before
    fun setUp() {
        mockHolder = mock(PlayerViewHolder::class.java)

        context.orCreateTestableResources
            .addOverride(R.dimen.qs_media_enabled_seekbar_height, enabledHeight)
        context.orCreateTestableResources
            .addOverride(R.dimen.qs_media_disabled_seekbar_height, disabledHeight)

        seekBarView = SeekBar(context)
        elapsedTimeView = TextView(context)
        totalTimeView = TextView(context)
        whenever(mockHolder.seekBar).thenReturn(seekBarView)
        whenever(mockHolder.elapsedTimeView).thenReturn(elapsedTimeView)
        whenever(mockHolder.totalTimeView).thenReturn(totalTimeView)

        observer = SeekBarObserver(mockHolder)
    }

    @Test
    fun seekBarGone() {
        // WHEN seek bar is disabled
        val isEnabled = false
        val data = SeekBarViewModel.Progress(isEnabled, false, null, 0)
        observer.onChanged(data)
        // THEN seek bar shows just a thin line with no text
        assertThat(seekBarView.isEnabled()).isFalse()
        assertThat(seekBarView.getThumb().getAlpha()).isEqualTo(0)
        assertThat(elapsedTimeView.getText()).isEqualTo("")
        assertThat(totalTimeView.getText()).isEqualTo("")
        assertThat(seekBarView.maxHeight).isEqualTo(disabledHeight)
    }

    @Test
    fun seekBarVisible() {
        // WHEN seek bar is enabled
        val isEnabled = true
        val data = SeekBarViewModel.Progress(isEnabled, true, 3000, 12000)
        observer.onChanged(data)
        // THEN seek bar is visible and thick
        assertThat(seekBarView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(elapsedTimeView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(totalTimeView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(seekBarView.maxHeight).isEqualTo(enabledHeight)
    }

    @Test
    fun seekBarProgress() {
        // WHEN part of the track has been played
        val data = SeekBarViewModel.Progress(true, true, 3000, 120000)
        observer.onChanged(data)
        // THEN seek bar shows the progress
        assertThat(seekBarView.progress).isEqualTo(3000)
        assertThat(seekBarView.max).isEqualTo(120000)
        assertThat(elapsedTimeView.getText()).isEqualTo("00:03")
        assertThat(totalTimeView.getText()).isEqualTo("02:00")
    }

    @Test
    fun seekBarDisabledWhenSeekNotAvailable() {
        // WHEN seek is not available
        val isSeekAvailable = false
        val data = SeekBarViewModel.Progress(true, isSeekAvailable, 3000, 120000)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isFalse()
    }

    @Test
    fun seekBarEnabledWhenSeekNotAvailable() {
        // WHEN seek is available
        val isSeekAvailable = true
        val data = SeekBarViewModel.Progress(true, isSeekAvailable, 3000, 120000)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isTrue()
    }
}
