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

import android.text.format.DateUtils
import androidx.annotation.UiThread
import androidx.lifecycle.Observer
import com.android.systemui.R

/**
 * Observer for changes from SeekBarViewModel.
 *
 * <p>Updates the seek bar views in response to changes to the model.
 */
class SeekBarObserver(private val holder: PlayerViewHolder) : Observer<SeekBarViewModel.Progress> {

    val seekBarEnabledMaxHeight = holder.seekBar.context.resources
        .getDimensionPixelSize(R.dimen.qs_media_enabled_seekbar_height)
    val seekBarDisabledHeight = holder.seekBar.context.resources
        .getDimensionPixelSize(R.dimen.qs_media_disabled_seekbar_height)
    val seekBarEnabledVerticalPadding = holder.seekBar.context.resources
            .getDimensionPixelSize(R.dimen.qs_media_enabled_seekbar_vertical_padding)
    val seekBarDisabledVerticalPadding = holder.seekBar.context.resources
            .getDimensionPixelSize(R.dimen.qs_media_disabled_seekbar_vertical_padding)

    /** Updates seek bar views when the data model changes. */
    @UiThread
    override fun onChanged(data: SeekBarViewModel.Progress) {
        if (!data.enabled) {
            if (holder.seekBar.maxHeight != seekBarDisabledHeight) {
                holder.seekBar.maxHeight = seekBarDisabledHeight
                setVerticalPadding(seekBarDisabledVerticalPadding)
            }
            holder.seekBar.setEnabled(false)
            holder.seekBar.getThumb().setAlpha(0)
            holder.seekBar.setProgress(0)
            holder.elapsedTimeView.setText("")
            holder.totalTimeView.setText("")
            return
        }

        holder.seekBar.getThumb().setAlpha(if (data.seekAvailable) 255 else 0)
        holder.seekBar.setEnabled(data.seekAvailable)

        if (holder.seekBar.maxHeight != seekBarEnabledMaxHeight) {
            holder.seekBar.maxHeight = seekBarEnabledMaxHeight
            setVerticalPadding(seekBarEnabledVerticalPadding)
        }

        data.duration?.let {
            holder.seekBar.setMax(it)
            holder.totalTimeView.setText(DateUtils.formatElapsedTime(
                    it / DateUtils.SECOND_IN_MILLIS))
        }

        data.elapsedTime?.let {
            holder.seekBar.setProgress(it)
            holder.elapsedTimeView.setText(DateUtils.formatElapsedTime(
                    it / DateUtils.SECOND_IN_MILLIS))
        }
    }

    @UiThread
    fun setVerticalPadding(padding: Int) {
        val leftPadding = holder.seekBar.paddingLeft
        val rightPadding = holder.seekBar.paddingRight
        holder.seekBar.setPadding(leftPadding, padding, rightPadding, padding)
    }
}
