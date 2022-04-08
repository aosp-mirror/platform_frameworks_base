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
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.util.concurrency.RepeatableExecutor
import javax.inject.Inject

private const val POSITION_UPDATE_INTERVAL_MILLIS = 100L
private const val MIN_FLING_VELOCITY_SCALE_FACTOR = 10

private fun PlaybackState.isInMotion(): Boolean {
    return this.state == PlaybackState.STATE_PLAYING ||
            this.state == PlaybackState.STATE_FAST_FORWARDING ||
            this.state == PlaybackState.STATE_REWINDING
}

/**
 * Gets the playback position while accounting for the time since the [PlaybackState] was last
 * retrieved.
 *
 * This method closely follows the implementation of
 * [MediaSessionRecord#getStateWithUpdatedPosition].
 */
private fun PlaybackState.computePosition(duration: Long): Long {
    var currentPosition = this.position
    if (this.isInMotion()) {
        val updateTime = this.getLastPositionUpdateTime()
        val currentTime = SystemClock.elapsedRealtime()
        if (updateTime > 0) {
            var position = (this.playbackSpeed * (currentTime - updateTime)).toLong() +
                    this.getPosition()
            if (duration >= 0 && position > duration) {
                position = duration.toLong()
            } else if (position < 0) {
                position = 0
            }
            currentPosition = position
        }
    }
    return currentPosition
}

/** ViewModel for seek bar in QS media player. */
class SeekBarViewModel @Inject constructor(
    @Background private val bgExecutor: RepeatableExecutor
) {
    private var _data = Progress(false, false, false, false, null, 0)
        set(value) {
            field = value
            _progress.postValue(value)
        }
    private val _progress = MutableLiveData<Progress>().apply {
        postValue(_data)
    }
    val progress: LiveData<Progress>
        get() = _progress
    private var controller: MediaController? = null
        set(value) {
            if (field?.sessionToken != value?.sessionToken) {
                field?.unregisterCallback(callback)
                value?.registerCallback(callback)
                field = value
            }
        }
    private var playbackState: PlaybackState? = null
    private var callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playbackState = state
            if (playbackState == null || PlaybackState.STATE_NONE.equals(playbackState)) {
                clearController()
            } else {
                checkIfPollingNeeded()
            }
        }

        override fun onSessionDestroyed() {
            clearController()
        }
    }
    private var cancel: Runnable? = null

    /** Indicates if the seek interaction is considered a false guesture. */
    private var isFalseSeek = false

    /** Listening state (QS open or closed) is used to control polling of progress. */
    var listening = true
        set(value) = bgExecutor.execute {
            if (field != value) {
                field = value
                checkIfPollingNeeded()
            }
        }

    private var scrubbingChangeListener: ScrubbingChangeListener? = null

    /** Set to true when the user is touching the seek bar to change the position. */
    private var scrubbing = false
        set(value) {
            if (field != value) {
                field = value
                checkIfPollingNeeded()
                scrubbingChangeListener?.onScrubbingChanged(value)
                _data = _data.copy(scrubbing = value)
            }
        }

    lateinit var logSeek: () -> Unit

    fun getEnabled() = _data.enabled

    /**
     * Event indicating that the user has started interacting with the seek bar.
     */
    @AnyThread
    fun onSeekStarting() = bgExecutor.execute {
        scrubbing = true
        isFalseSeek = false
    }

    /**
     * Event indicating that the user has moved the seek bar but hasn't yet finished the gesture.
     * @param position Current location in the track.
     */
    @AnyThread
    fun onSeekProgress(position: Long) = bgExecutor.execute {
        if (scrubbing) {
            _data = _data.copy(elapsedTime = position.toInt())
        }
    }

    /**
     * Event indicating that the seek interaction is a false gesture and it should be ignored.
     */
    @AnyThread
    fun onSeekFalse() = bgExecutor.execute {
        if (scrubbing) {
            isFalseSeek = true
        }
    }

    /**
     * Handle request to change the current position in the media track.
     * @param position Place to seek to in the track.
     */
    @AnyThread
    fun onSeek(position: Long) = bgExecutor.execute {
        if (isFalseSeek) {
            scrubbing = false
            checkPlaybackPosition()
        } else {
            logSeek()
            controller?.transportControls?.seekTo(position)
            // Invalidate the cached playbackState to avoid the thumb jumping back to the previous
            // position.
            playbackState = null
            scrubbing = false
        }
    }

    /**
     * Updates media information.
     * @param mediaController controller for media session
     */
    @WorkerThread
    fun updateController(mediaController: MediaController?) {
        controller = mediaController
        playbackState = controller?.playbackState
        val mediaMetadata = controller?.metadata
        val seekAvailable = ((playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L
        val position = playbackState?.position?.toInt()
        val duration = mediaMetadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.toInt() ?: 0
        val playing = NotificationMediaManager
                .isPlayingState(playbackState?.state ?: PlaybackState.STATE_NONE)
        val enabled = if (playbackState == null ||
                playbackState?.getState() == PlaybackState.STATE_NONE ||
                (duration <= 0)) false else true
        _data = Progress(enabled, seekAvailable, playing, scrubbing, position, duration)
        checkIfPollingNeeded()
    }

    /**
     * Puts the seek bar into a resumption state.
     *
     * This should be called when the media session behind the controller has been destroyed.
     */
    @AnyThread
    fun clearController() = bgExecutor.execute {
        controller = null
        playbackState = null
        cancel?.run()
        cancel = null
        _data = _data.copy(enabled = false)
    }

    /**
     * Call to clean up any resources.
     */
    @AnyThread
    fun onDestroy() = bgExecutor.execute {
        controller = null
        playbackState = null
        cancel?.run()
        cancel = null
        scrubbingChangeListener = null
    }

    @WorkerThread
    private fun checkPlaybackPosition() {
        val duration = _data.duration ?: -1
        val currentPosition = playbackState?.computePosition(duration.toLong())?.toInt()
        if (currentPosition != null && _data.elapsedTime != currentPosition) {
            _data = _data.copy(elapsedTime = currentPosition)
        }
    }

    @WorkerThread
    private fun checkIfPollingNeeded() {
        val needed = listening && !scrubbing && playbackState?.isInMotion() ?: false
        if (needed) {
            if (cancel == null) {
                cancel = bgExecutor.executeRepeatedly(this::checkPlaybackPosition, 0L,
                        POSITION_UPDATE_INTERVAL_MILLIS)
            }
        } else {
            cancel?.run()
            cancel = null
        }
    }

    /** Gets a listener to attach to the seek bar to handle seeking. */
    val seekBarListener: SeekBar.OnSeekBarChangeListener
        get() {
            return SeekBarChangeListener(this)
        }

    /** Attach touch handlers to the seek bar view. */
    fun attachTouchHandlers(bar: SeekBar) {
        bar.setOnSeekBarChangeListener(seekBarListener)
        bar.setOnTouchListener(SeekBarTouchListener(this, bar))
    }

    fun setScrubbingChangeListener(listener: ScrubbingChangeListener) {
        scrubbingChangeListener = listener
    }

    fun removeScrubbingChangeListener(listener: ScrubbingChangeListener) {
        if (listener == scrubbingChangeListener) {
            scrubbingChangeListener = null
        }
    }

    /** Listener interface to be notified when the user starts or stops scrubbing. */
    interface ScrubbingChangeListener {
        fun onScrubbingChanged(scrubbing: Boolean)
    }

    private class SeekBarChangeListener(
        val viewModel: SeekBarViewModel
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                viewModel.onSeekProgress(progress.toLong())
            }
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            viewModel.onSeekStarting()
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            viewModel.onSeek(bar.progress.toLong())
        }
    }

    /**
     * Responsible for intercepting touch events before they reach the seek bar.
     *
     * This reduces the gestures seen by the seek bar so that users don't accidentially seek when
     * they intend to scroll the carousel.
     */
    private class SeekBarTouchListener(
        private val viewModel: SeekBarViewModel,
        private val bar: SeekBar
    ) : View.OnTouchListener, GestureDetector.OnGestureListener {

        // Gesture detector helps decide which touch events to intercept.
        private val detector = GestureDetectorCompat(bar.context, this)
        // Velocity threshold used to decide when a fling is considered a false gesture.
        private val flingVelocity: Int = ViewConfiguration.get(bar.context).run {
            getScaledMinimumFlingVelocity() * MIN_FLING_VELOCITY_SCALE_FACTOR
        }
        // Indicates if the gesture should go to the seek bar or if it should be intercepted.
        private var shouldGoToSeekBar = false

        /**
         * Decide which touch events to intercept before they reach the seek bar.
         *
         * Based on the gesture detected, we decide whether we want the event to reach the seek bar.
         * If we want the seek bar to see the event, then we return false so that the event isn't
         * handled here and it will be passed along. If, however, we don't want the seek bar to see
         * the event, then return true so that the event is handled here.
         *
         * When the seek bar is contained in the carousel, the carousel still has the ability to
         * intercept the touch event. So, even though we may handle the event here, the carousel can
         * still intercept the event. This way, gestures that we consider falses on the seek bar can
         * still be used by the carousel for paging.
         *
         * Returns true for events that we don't want dispatched to the seek bar.
         */
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (view != bar) {
                return false
            }
            detector.onTouchEvent(event)
            return !shouldGoToSeekBar
        }

        /**
         * Handle down events that press down on the thumb.
         *
         * On the down action, determine a target box around the thumb to know when a scroll
         * gesture starts by clicking on the thumb. The target box will be used by subsequent
         * onScroll events.
         *
         * Returns true when the down event hits within the target box of the thumb.
         */
        override fun onDown(event: MotionEvent): Boolean {
            val padL = bar.paddingLeft
            val padR = bar.paddingRight
            // Compute the X location of the thumb as a function of the seek bar progress.
            // TODO: account for thumb offset
            val progress = bar.getProgress()
            val range = bar.max - bar.min
            val widthFraction = if (range > 0) {
                (progress - bar.min).toDouble() / range
            } else {
                0.0
            }
            val availableWidth = bar.width - padL - padR
            val thumbX = if (bar.isLayoutRtl()) {
                padL + availableWidth * (1 - widthFraction)
            } else {
                padL + availableWidth * widthFraction
            }
            // Set the min, max boundaries of the thumb box.
            // I'm cheating by using the height of the seek bar as the width of the box.
            val halfHeight: Int = bar.height / 2
            val targetBoxMinX = (Math.round(thumbX) - halfHeight).toInt()
            val targetBoxMaxX = (Math.round(thumbX) + halfHeight).toInt()
            // If the x position of the down event is within the box, then request that the parent
            // not intercept the event.
            val x = Math.round(event.x)
            shouldGoToSeekBar = x >= targetBoxMinX && x <= targetBoxMaxX
            if (shouldGoToSeekBar) {
                bar.parent?.requestDisallowInterceptTouchEvent(true)
            }
            return shouldGoToSeekBar
        }

        /**
         * Always handle single tap up.
         *
         * This enables the user to single tap anywhere on the seek bar to seek to that position.
         */
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            shouldGoToSeekBar = true
            return true
        }

        /**
         * Handle scroll events when the down event is on the thumb.
         *
         * Returns true when the down event of the scroll hits within the target box of the thumb.
         */
        override fun onScroll(
            eventStart: MotionEvent,
            event: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            return shouldGoToSeekBar
        }

        /**
         * Handle fling events when the down event is on the thumb.
         *
         * Gestures that include a fling are considered a false gesture on the seek bar.
         */
        override fun onFling(
            eventStart: MotionEvent,
            event: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (Math.abs(velocityX) > flingVelocity || Math.abs(velocityY) > flingVelocity) {
                viewModel.onSeekFalse()
            }
            return shouldGoToSeekBar
        }

        override fun onShowPress(event: MotionEvent) {}

        override fun onLongPress(event: MotionEvent) {}
    }

    /** State seen by seek bar UI. */
    data class Progress(
        val enabled: Boolean,
        val seekAvailable: Boolean,
        val playing: Boolean,
        val scrubbing: Boolean,
        val elapsedTime: Int?,
        val duration: Int
    )
}
