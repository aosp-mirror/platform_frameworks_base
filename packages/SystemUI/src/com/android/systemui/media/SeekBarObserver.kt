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

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.lifecycle.Observer

import com.android.systemui.R

/**
 * Observer for changes from SeekBarViewModel.
 *
 * <p>Updates the seek bar views in response to changes to the model.
 */
class SeekBarObserver(view: View) : Observer<SeekBarViewModel.Progress> {

    private val seekBarView: SeekBar
    private val elapsedTimeView: TextView
    private val totalTimeView: TextView

    init {
        seekBarView = view.findViewById(R.id.media_progress_bar)
        elapsedTimeView = view.findViewById(R.id.media_elapsed_time)
        totalTimeView = view.findViewById(R.id.media_total_time)
    }

    /** Updates seek bar views when the data model changes. */
    @UiThread
    override fun onChanged(data: SeekBarViewModel.Progress) {

        data.color?.let {
            var tintList = ColorStateList.valueOf(it)
            seekBarView.setThumbTintList(tintList)
            tintList = tintList.withAlpha(192) // 75%
            seekBarView.setProgressTintList(tintList)
            tintList = tintList.withAlpha(128) // 50%
            seekBarView.setProgressBackgroundTintList(tintList)
            elapsedTimeView.setTextColor(it)
            totalTimeView.setTextColor(it)
        }

        if (!data.enabled) {
            seekBarView.setEnabled(false)
            seekBarView.getThumb().setAlpha(0)
            seekBarView.setProgress(0)
            elapsedTimeView.setText("")
            totalTimeView.setText("")
            return
        }

        seekBarView.getThumb().setAlpha(if (data.seekAvailable) 255 else 0)
        seekBarView.setEnabled(data.seekAvailable)

        data.elapsedTime?.let {
            seekBarView.setProgress(it)
            elapsedTimeView.setText(DateUtils.formatElapsedTime(
                    it / DateUtils.SECOND_IN_MILLIS))
        }

        data.duration?.let {
            seekBarView.setMax(it)
            totalTimeView.setText(DateUtils.formatElapsedTime(
                    it / DateUtils.SECOND_IN_MILLIS))
        }
    }
}
