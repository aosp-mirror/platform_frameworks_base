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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.annotation.DrawableRes
import android.annotation.StringRes
import android.app.PendingIntent
import android.graphics.drawable.Icon
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.row.domain.interactor.NotificationRowInteractor
import com.android.systemui.statusbar.notification.row.shared.RichOngoingNotificationFlag
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel.TimerState
import com.android.systemui.util.kotlin.FlowDumperImpl
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** A view model for Timer notifications. */
class TimerViewModel
@Inject
constructor(
    dumpManager: DumpManager,
    rowInteractor: NotificationRowInteractor,
) : FlowDumperImpl(dumpManager) {
    init {
        /* check if */ RichOngoingNotificationFlag.isUnexpectedlyInLegacyMode()
    }

    private val state: Flow<TimerState> = rowInteractor.timerContentModel.mapNotNull { it.state }

    val icon: Flow<Icon?> = rowInteractor.timerContentModel.mapNotNull { it.icon.icon }

    val label: Flow<String> = rowInteractor.timerContentModel.mapNotNull { it.name }

    val countdownTime: Flow<Long?> = state.map { (it as? TimerState.Running)?.finishTime }

    val pausedTime: Flow<String?> =
        state.map { (it as? TimerState.Paused)?.timeRemaining?.format() }

    val mainButtonModel: Flow<ButtonViewModel> =
        state.map {
            when (it) {
                is TimerState.Paused ->
                    ButtonViewModel.WithSystemAttrs(
                        it.resumeIntent,
                        com.android.systemui.res.R.string.controls_media_resume, // "Resume",
                        com.android.systemui.res.R.drawable.ic_media_play
                    )
                is TimerState.Running ->
                    ButtonViewModel.WithSystemAttrs(
                        it.pauseIntent,
                        com.android.systemui.res.R.string.controls_media_button_pause, // "Pause",
                        com.android.systemui.res.R.drawable.ic_media_pause
                    )
            }
        }

    val altButtonModel: Flow<ButtonViewModel?> =
        state.map {
            it.addMinuteAction?.let { action ->
                ButtonViewModel.WithCustomAttrs(
                    action.actionIntent,
                    action.title, // "1:00",
                    action.getIcon()
                )
            }
        }

    val resetButtonModel: Flow<ButtonViewModel?> =
        state.map {
            it.resetAction?.let { action ->
                ButtonViewModel.WithCustomAttrs(
                    action.actionIntent,
                    action.title, // "Reset",
                    action.getIcon()
                )
            }
        }

    sealed interface ButtonViewModel {
        val pendingIntent: PendingIntent?

        data class WithSystemAttrs(
            override val pendingIntent: PendingIntent?,
            @StringRes val labelRes: Int,
            @DrawableRes val iconRes: Int,
        ) : ButtonViewModel

        data class WithCustomAttrs(
            override val pendingIntent: PendingIntent?,
            val label: CharSequence,
            val icon: Icon,
        ) : ButtonViewModel
    }
}

private fun Duration.format(): String {
    val hours = this.toHours()
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, toMinutesPart(), toSecondsPart())
    } else {
        String.format("%d:%02d", toMinutes(), toSecondsPart())
    }
}
