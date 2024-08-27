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

package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.row.shared.EnRouteContentModel
import com.android.systemui.statusbar.notification.row.shared.IconModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingContentModel
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Interface which provides a [RichOngoingContentModel] for a given [Notification] when one is
 * applicable to the given style.
 */
interface RichOngoingNotificationContentExtractor {
    fun extractContentModel(
        entry: NotificationEntry,
        builder: Notification.Builder,
        systemUIContext: Context,
        packageContext: Context
    ): RichOngoingContentModel?
}

class NoOpRichOngoingNotificationContentExtractor : RichOngoingNotificationContentExtractor {
    override fun extractContentModel(
        entry: NotificationEntry,
        builder: Notification.Builder,
        systemUIContext: Context,
        packageContext: Context
    ): RichOngoingContentModel? = null
}

@SysUISingleton
class RichOngoingNotificationContentExtractorImpl @Inject constructor() :
    RichOngoingNotificationContentExtractor {

    init {
        /* check if */ RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()
    }

    override fun extractContentModel(
        entry: NotificationEntry,
        builder: Notification.Builder,
        systemUIContext: Context,
        packageContext: Context
    ): RichOngoingContentModel? {
        val sbn = entry.sbn
        val notification = sbn.notification
        val icon = IconModel(notification.smallIcon)

        try {
            return if (sbn.packageName == "com.google.android.deskclock") {
                when (notification.channelId) {
                    "Timers v2" -> {
                        parseTimerNotification(notification, icon)
                    }
                    "Stopwatch v2" -> {
                        Log.i("RONs", "Can't process stopwatch yet")
                        null
                    }
                    else -> {
                        Log.i("RONs", "Can't process channel '${notification.channelId}'")
                        null
                    }
                }
            } else if (builder.style is Notification.EnRouteStyle) {
                parseEnRouteNotification(notification, icon)
            } else null
        } catch (e: Exception) {
            Log.e("RONs", "Error parsing RON", e)
            return null
        }
    }

    /**
     * FOR PROTOTYPING ONLY: create a RON TimerContentModel using the time information available
     * inside the sortKey of the clock app's timer notifications.
     */
    private fun parseTimerNotification(
        notification: Notification,
        icon: IconModel
    ): TimerContentModel {
        // sortKey=1 0|↺7|RUNNING|▶16:21:58.523|Σ0:05:00|Δ0:00:03|⏳0:04:57
        // sortKey=1 0|↺7|PAUSED|Σ0:05:00|Δ0:04:54|⏳0:00:06
        // sortKey=1 1|↺7|RUNNING|▶16:30:28.433|Σ0:04:05|Δ0:00:06|⏳0:03:59
        // sortKey=1 0|↺7|RUNNING|▶16:36:18.350|Σ0:05:00|Δ0:01:42|⏳0:03:18
        // sortKey=1 2|↺7|RUNNING|▶16:38:37.816|Σ0:02:00|Δ0:01:09|⏳0:00:51
        // ▶ = "current" time (when updated)
        // Σ = total time
        // Δ = time elapsed
        // ⏳ = time remaining
        val sortKey = notification.sortKey
        val (_, _, state, extra) = sortKey.split("|", limit = 4)
        return when (state) {
            "PAUSED" -> {
                val (total, _, remaining) = extra.split("|")
                val timeRemaining = parseTimeDelta(remaining)
                TimerContentModel(
                    icon = icon,
                    // TODO: b/352142761 - define and use a string resource rather than " Timer".
                    // (The UX isn't final so using " Timer" for now).
                    name = total.replace("Σ", "") + " Timer",
                    state =
                        TimerContentModel.TimerState.Paused(
                            timeRemaining = timeRemaining,
                            resumeIntent = notification.findStartIntent(),
                            addMinuteAction = notification.findAddMinuteAction(),
                            resetAction = notification.findResetAction(),
                        )
                )
            }
            "RUNNING" -> {
                val (current, total, _, remaining) = extra.split("|")
                val finishTime = parseCurrentTime(current) + parseTimeDelta(remaining).toMillis()
                TimerContentModel(
                    icon = icon,
                    // TODO: b/352142761 - define and use a string resource rather than " Timer".
                    // (The UX isn't final so using " Timer" for now).
                    name = total.replace("Σ", "") + " Timer",
                    state =
                        TimerContentModel.TimerState.Running(
                            finishTime = finishTime,
                            pauseIntent = notification.findPauseIntent(),
                            addMinuteAction = notification.findAddMinuteAction(),
                            resetAction = notification.findResetAction(),
                        )
                )
            }
            else -> error("unknown state ($state) in sortKey=$sortKey")
        }
    }

    private fun Notification.findPauseIntent(): PendingIntent? {
        return actions
            .firstOrNull { it.actionIntent.intent?.action?.endsWith(".PAUSE_TIMER") == true }
            ?.actionIntent
    }

    private fun Notification.findStartIntent(): PendingIntent? {
        return actions
            .firstOrNull { it.actionIntent.intent?.action?.endsWith(".START_TIMER") == true }
            ?.actionIntent
    }

    // TODO: b/352142761 - switch to system attributes for label and icon.
    //   - We probably want a consistent look for the Reset button. (Double check with UX.)
    //   - Using the custom assets now since I couldn't an existing "Reset" icon.
    private fun Notification.findResetAction(): Notification.Action? {
        return actions.firstOrNull {
            it.actionIntent.intent?.action?.endsWith(".RESET_TIMER") == true
        }
    }

    // TODO: b/352142761 - check with UX on whether this should be required.
    //   - Alternative is to allow for optional actions in addition to main and reset.
    //   - For optional actions, we should take the custom label and icon.
    private fun Notification.findAddMinuteAction(): Notification.Action? {
        return actions.firstOrNull {
            it.actionIntent.intent?.action?.endsWith(".ADD_MINUTE_TIMER") == true
        }
    }

    private fun parseCurrentTime(current: String): Long {
        val (hour, minute, second, millis) = current.replace("▶", "").split(":", ".")
        // NOTE: this won't work correctly at/around midnight.  It's just for prototyping.
        val localDateTime =
            LocalDateTime.of(
                LocalDate.now(),
                LocalTime.of(hour.toInt(), minute.toInt(), second.toInt(), millis.toInt() * 1000000)
            )
        val offset = ZoneId.systemDefault().rules.getOffset(localDateTime)
        return localDateTime.toInstant(offset).toEpochMilli()
    }

    private fun parseTimeDelta(delta: String): Duration {
        val (hour, minute, second) = delta.replace("Σ", "").replace("⏳", "").split(":")
        return Duration.ofHours(hour.toLong())
            .plusMinutes(minute.toLong())
            .plusSeconds(second.toLong())
    }

    private fun parseEnRouteNotification(
        notification: Notification,
        icon: IconModel,
    ): EnRouteContentModel {
        return EnRouteContentModel(
            smallIcon = icon,
            title = notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
    }
}
