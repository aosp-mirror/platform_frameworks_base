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

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData

import com.android.systemui.util.concurrency.DelayableExecutor

private const val POSITION_UPDATE_INTERVAL_MILLIS = 100L

/** ViewModel for seek bar in QS media player. */
class SeekBarViewModel(val bgExecutor: DelayableExecutor) {

    private val _progress = MutableLiveData<Progress>().apply {
        postValue(Progress(false, false, null, null, null))
    }
    val progress: LiveData<Progress>
        get() = _progress
    private var controller: MediaController? = null
    private var playbackState: PlaybackState? = null

    /** Listening state (QS open or closed) is used to control polling of progress. */
    var listening = true
        set(value) {
            if (value) {
                checkPlaybackPosition()
            }
        }

    /**
     * Handle request to change the current position in the media track.
     * @param position Place to seek to in the track.
     */
    @WorkerThread
    fun onSeek(position: Long) {
        controller?.transportControls?.seekTo(position)
    }

    /**
     * Updates media information.
     * @param mediaController controller for media session
     * @param color foreground color for UI elements
     */
    @WorkerThread
    fun updateController(mediaController: MediaController?, color: Int) {
        controller = mediaController
        playbackState = controller?.playbackState
        val mediaMetadata = controller?.metadata
        val seekAvailable = ((playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        val position = playbackState?.position?.toInt()
        val duration = mediaMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.toInt()
        val enabled = if (duration != null && duration <= 0) false else true
        _progress.postValue(Progress(enabled, seekAvailable, position, duration, color))
        if (shouldPollPlaybackPosition()) {
            checkPlaybackPosition()
        }
    }

    @AnyThread
    private fun checkPlaybackPosition(): Runnable = bgExecutor.executeDelayed({
        val currentPosition = controller?.playbackState?.position?.toInt()
        if (currentPosition != null && _progress.value!!.elapsedTime != currentPosition) {
            _progress.postValue(_progress.value!!.copy(elapsedTime = currentPosition))
        }
        if (shouldPollPlaybackPosition()) {
            checkPlaybackPosition()
        }
    }, POSITION_UPDATE_INTERVAL_MILLIS)

    @WorkerThread
    private fun shouldPollPlaybackPosition(): Boolean {
        val state = playbackState?.state
        val moving = if (state == null) false else
                state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_FAST_FORWARDING ||
                state == PlaybackState.STATE_REWINDING
        return moving && listening
    }

    /** Gets a listener to attach to the seek bar to handle seeking. */
    val seekBarListener: SeekBar.OnSeekBarChangeListener
        get() {
            return SeekBarChangeListener(this, bgExecutor)
        }

    /** Gets a listener to attach to the seek bar to disable touch intercepting. */
    val seekBarTouchListener: View.OnTouchListener
        get() {
            return SeekBarTouchListener()
        }

    private class SeekBarChangeListener(
        val viewModel: SeekBarViewModel,
        val bgExecutor: DelayableExecutor
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                bgExecutor.execute {
                    viewModel.onSeek(progress.toLong())
                }
            }
        }
        override fun onStartTrackingTouch(bar: SeekBar) {
        }
        override fun onStopTrackingTouch(bar: SeekBar) {
            val pos = bar.progress.toLong()
            bgExecutor.execute {
                viewModel.onSeek(pos)
            }
        }
    }

    private class SeekBarTouchListener : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            view.parent.requestDisallowInterceptTouchEvent(true)
            return view.onTouchEvent(event)
        }
    }

    /** State seen by seek bar UI. */
    data class Progress(
        val enabled: Boolean,
        val seekAvailable: Boolean,
        val elapsedTime: Int?,
        val duration: Int?,
        val color: Int?
    )
}
