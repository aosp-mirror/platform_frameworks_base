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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.SystemInfoCombinedVisibilityModel
import com.android.systemui.statusbar.pipeline.shared.ui.model.VisibilityModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHomeStatusBarViewModel(
    override val operatorNameViewModel: StatusBarOperatorNameViewModel
) : HomeStatusBarViewModel {
    override val areNotificationsLightsOut = MutableStateFlow(false)

    override val isTransitioningFromLockscreenToOccluded = MutableStateFlow(false)

    override val transitionFromLockscreenToDreamStartedEvent = MutableSharedFlow<Unit>()

    override val primaryOngoingActivityChip: MutableStateFlow<OngoingActivityChipModel> =
        MutableStateFlow(OngoingActivityChipModel.Hidden())

    override val ongoingActivityChips = MutableStateFlow(MultipleOngoingActivityChipsModel())

    override val statusBarPopupChips = MutableStateFlow(emptyList<PopupChipModel.Shown>())

    override val mediaProjectionStopDialogDueToCallEndedState =
        MutableStateFlow(MediaProjectionStopDialogModel.Hidden)

    override val isHomeStatusBarAllowedByScene = MutableStateFlow(false)

    override val shouldHomeStatusBarBeVisible = MutableStateFlow(false)

    override val shouldShowOperatorNameView = MutableStateFlow(false)

    override val isClockVisible =
        MutableStateFlow(VisibilityModel(visibility = View.GONE, shouldAnimateChange = false))

    override val isNotificationIconContainerVisible =
        MutableStateFlow(VisibilityModel(visibility = View.GONE, shouldAnimateChange = false))

    override val systemInfoCombinedVis =
        MutableStateFlow(
            SystemInfoCombinedVisibilityModel(
                VisibilityModel(visibility = View.GONE, shouldAnimateChange = false),
                Idle,
            )
        )

    override val iconBlockList: MutableStateFlow<List<String>> = MutableStateFlow(listOf())

    override val contentArea = MutableStateFlow(Rect(0, 0, 1, 1))

    val darkRegions = mutableListOf<Rect>()

    var darkIconTint = Color.BLACK
    var lightIconTint = Color.WHITE

    override val areaTint: Flow<StatusBarTintColor> =
        MutableStateFlow(
            StatusBarTintColor { viewBounds ->
                if (DarkIconDispatcher.isInAreas(darkRegions, viewBounds)) {
                    lightIconTint
                } else {
                    darkIconTint
                }
            }
        )
}
