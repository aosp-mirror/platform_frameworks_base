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
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupChipModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHomeStatusBarViewModel(
    override val operatorNameViewModel: StatusBarOperatorNameViewModel
) : HomeStatusBarViewModel {
    private val areNotificationLightsOut = MutableStateFlow(false)

    override val isTransitioningFromLockscreenToOccluded = MutableStateFlow(false)

    override val transitionFromLockscreenToDreamStartedEvent = MutableSharedFlow<Unit>()

    override val primaryOngoingActivityChip: MutableStateFlow<OngoingActivityChipModel> =
        MutableStateFlow(OngoingActivityChipModel.Hidden())

    override val ongoingActivityChips = MutableStateFlow(MultipleOngoingActivityChipsModel())

    override val statusBarPopupChips = MutableStateFlow(emptyList<PopupChipModel.Shown>())

    override val isHomeStatusBarAllowedByScene = MutableStateFlow(false)

    override val shouldShowOperatorNameView = MutableStateFlow(false)

    override val isClockVisible =
        MutableStateFlow(
            HomeStatusBarViewModel.VisibilityModel(
                visibility = View.GONE,
                shouldAnimateChange = false,
            )
        )

    override val isNotificationIconContainerVisible =
        MutableStateFlow(
            HomeStatusBarViewModel.VisibilityModel(
                visibility = View.GONE,
                shouldAnimateChange = false,
            )
        )

    override val systemInfoCombinedVis =
        MutableStateFlow(
            HomeStatusBarViewModel.SystemInfoCombinedVisibilityModel(
                HomeStatusBarViewModel.VisibilityModel(
                    visibility = View.GONE,
                    shouldAnimateChange = false,
                ),
                Idle,
            )
        )

    override val iconBlockList: MutableStateFlow<List<String>> = MutableStateFlow(listOf())

    override fun areNotificationsLightsOut(displayId: Int): Flow<Boolean> = areNotificationLightsOut

    val darkRegions = mutableListOf<Rect>()

    var darkIconTint = Color.BLACK
    var lightIconTint = Color.WHITE

    override fun areaTint(displayId: Int): Flow<StatusBarTintColor> =
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
