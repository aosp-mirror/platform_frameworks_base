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

package com.android.systemui.statusbar.chips.call.ui.viewmodel

import android.view.View
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.Flags
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.call.domain.interactor.CallChipInteractor
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipViewModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** View model for the ongoing phone call chip shown in the status bar. */
@SysUISingleton
open class CallChipViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    interactor: CallChipInteractor,
    systemClock: SystemClock,
    private val activityStarter: ActivityStarter,
    @StatusBarChipsLog private val logger: LogBuffer,
) : OngoingActivityChipViewModel {
    override val chip: StateFlow<OngoingActivityChipModel> =
        interactor.ongoingCallState
            .map { state ->
                when (state) {
                    is OngoingCallModel.NoCall -> OngoingActivityChipModel.Hidden()
                    is OngoingCallModel.InCall -> {
                        val icon =
                            if (
                                Flags.statusBarCallChipNotificationIcon() &&
                                    state.notificationIconView != null
                            ) {
                                OngoingActivityChipModel.ChipIcon.StatusBarView(
                                    state.notificationIconView
                                )
                            } else {
                                OngoingActivityChipModel.ChipIcon.SingleColorIcon(phoneIcon)
                            }

                        // This block mimics OngoingCallController#updateChip.
                        if (state.startTimeMs <= 0L) {
                            // If the start time is invalid, don't show a timer and show just an
                            // icon. See b/192379214.
                            OngoingActivityChipModel.Shown.IconOnly(
                                icon = icon,
                                colors = ColorsModel.Themed,
                                getOnClickListener(state),
                            )
                        } else {
                            val startTimeInElapsedRealtime =
                                state.startTimeMs - systemClock.currentTimeMillis() +
                                    systemClock.elapsedRealtime()
                            OngoingActivityChipModel.Shown.Timer(
                                icon = icon,
                                colors = ColorsModel.Themed,
                                startTimeMs = startTimeInElapsedRealtime,
                                getOnClickListener(state),
                            )
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden())

    private fun getOnClickListener(state: OngoingCallModel.InCall): View.OnClickListener? {
        if (state.intent == null) {
            return null
        }

        return View.OnClickListener { view ->
            logger.log(TAG, LogLevel.INFO, {}, { "Chip clicked" })
            val backgroundView =
                view.requireViewById<ChipBackgroundContainer>(R.id.ongoing_activity_chip_background)
            // This mimics OngoingCallController#updateChipClickListener.
            activityStarter.postStartActivityDismissingKeyguard(
                state.intent,
                ActivityTransitionAnimator.Controller.fromView(
                    backgroundView,
                    InteractionJankMonitor.CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
                )
            )
        }
    }

    companion object {
        private val phoneIcon =
            Icon.Resource(
                com.android.internal.R.drawable.ic_phone,
                ContentDescription.Resource(
                    R.string.ongoing_phone_call_content_description,
                ),
            )
        private const val TAG = "CallVM"
    }
}
