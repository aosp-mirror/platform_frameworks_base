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

import android.graphics.Color
import android.content.res.ColorStateList
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

    private lateinit var observer: SeekBarObserver
    @Mock private lateinit var mockView: View
    private lateinit var seekBarView: SeekBar
    private lateinit var elapsedTimeView: TextView
    private lateinit var totalTimeView: TextView

    @Before
    fun setUp() {
        mockView = mock(View::class.java)
        seekBarView = SeekBar(context)
        elapsedTimeView = TextView(context)
        totalTimeView = TextView(context)
        whenever<SeekBar>(
                mockView.findViewById(R.id.media_progress_bar)).thenReturn(seekBarView)
        whenever<TextView>(
                mockView.findViewById(R.id.media_elapsed_time)).thenReturn(elapsedTimeView)
        whenever<TextView>(mockView.findViewById(R.id.media_total_time)).thenReturn(totalTimeView)
        observer = SeekBarObserver(mockView)
    }

    @Test
    fun seekBarGone() {
        // WHEN seek bar is disabled
        val isEnabled = false
        val data = SeekBarViewModel.Progress(isEnabled, false, null, null, null)
        observer.onChanged(data)
        // THEN seek bar visibility is set to GONE
        assertThat(seekBarView.getVisibility()).isEqualTo(View.GONE)
        assertThat(elapsedTimeView.getVisibility()).isEqualTo(View.GONE)
        assertThat(totalTimeView.getVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun seekBarVisible() {
        // WHEN seek bar is enabled
        val isEnabled = true
        val data = SeekBarViewModel.Progress(isEnabled, true, 3000, 12000, -1)
        observer.onChanged(data)
        // THEN seek bar is visible
        assertThat(seekBarView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(elapsedTimeView.getVisibility()).isEqualTo(View.VISIBLE)
        assertThat(totalTimeView.getVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun seekBarProgress() {
        // WHEN seek bar progress is about half
        val data = SeekBarViewModel.Progress(true, true, 3000, 120000, -1)
        observer.onChanged(data)
        // THEN seek bar is visible
        assertThat(seekBarView.progress).isEqualTo(100)
        assertThat(seekBarView.max).isEqualTo(120000)
        assertThat(elapsedTimeView.getText()).isEqualTo("00:03")
        assertThat(totalTimeView.getText()).isEqualTo("02:00")
    }

    @Test
    fun seekBarDisabledWhenSeekNotAvailable() {
        // WHEN seek is not available
        val isSeekAvailable = false
        val data = SeekBarViewModel.Progress(true, isSeekAvailable, 3000, 120000, -1)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isFalse()
    }

    @Test
    fun seekBarEnabledWhenSeekNotAvailable() {
        // WHEN seek is available
        val isSeekAvailable = true
        val data = SeekBarViewModel.Progress(true, isSeekAvailable, 3000, 120000, -1)
        observer.onChanged(data)
        // THEN seek bar is not enabled
        assertThat(seekBarView.isEnabled()).isTrue()
    }

    @Test
    fun seekBarColor() {
        // WHEN data included color
        val data = SeekBarViewModel.Progress(true, true, 3000, 120000, Color.RED)
        observer.onChanged(data)
        // THEN seek bar is colored
        val red = ColorStateList.valueOf(Color.RED)
        assertThat(elapsedTimeView.getTextColors()).isEqualTo(red)
        assertThat(totalTimeView.getTextColors()).isEqualTo(red)
    }
}
