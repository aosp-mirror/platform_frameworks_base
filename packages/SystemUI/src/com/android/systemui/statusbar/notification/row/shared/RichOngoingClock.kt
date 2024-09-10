/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.shared

import android.app.Notification
import android.app.PendingIntent
import java.time.Duration

/**
 * Represents a simple timer that counts down to a time.
 *
 * @param name the label for the timer
 * @param state state of the timer, including time and whether it is paused or running
 */
data class TimerContentModel(
    val icon: IconModel,
    val name: String,
    val state: TimerState,
) : RichOngoingContentModel {
    /** The state (paused or running) of the timer, and relevant time */
    sealed interface TimerState {
        val addMinuteAction: Notification.Action?
        val resetAction: Notification.Action?

        /**
         * Indicates a running timer
         *
         * @param finishTime the time in ms since epoch that the timer will finish
         * @param pauseIntent the action for pausing the timer
         */
        data class Running(
            val finishTime: Long,
            val pauseIntent: PendingIntent?,
            override val addMinuteAction: Notification.Action?,
            override val resetAction: Notification.Action?,
        ) : TimerState

        /**
         * Indicates a paused timer
         *
         * @param timeRemaining the time in ms remaining on the paused timer
         * @param resumeIntent the action for resuming the timer
         */
        data class Paused(
            val timeRemaining: Duration,
            val resumeIntent: PendingIntent?,
            override val addMinuteAction: Notification.Action?,
            override val resetAction: Notification.Action?,
        ) : TimerState
    }
}

/**
 * Represents a simple stopwatch that counts up and allows tracking laps.
 *
 * @param state state of the stopwatch, including time and whether it is paused or running
 * @param lapDurations a list of durations of each completed lap
 */
data class StopwatchContentModel(
    val icon: IconModel,
    val state: StopwatchState,
    val lapDurations: List<Long>,
) : RichOngoingContentModel {
    /** The state (paused or running) of the stopwatch, and relevant time */
    sealed interface StopwatchState {
        /**
         * Indicates a running stopwatch
         *
         * @param startTime the time in ms since epoch that the stopwatch started, plus any
         *   accumulated pause time
         * @param pauseIntent the action for pausing the stopwatch
         */
        data class Running(
            val startTime: Long,
            val pauseIntent: PendingIntent,
        ) : StopwatchState

        /**
         * Indicates a paused stopwatch
         *
         * @param timeElapsed the time in ms elapsed on the stopwatch
         * @param resumeIntent the action for resuming the stopwatch
         */
        data class Paused(
            val timeElapsed: Duration,
            val resumeIntent: PendingIntent,
        ) : StopwatchState
    }
}
