/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import com.android.settingslib.Utils
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** Models the UI state for the device entry icon background view. */
@ExperimentalCoroutinesApi
class DeviceEntryBackgroundViewModel
@Inject
constructor(
    val context: Context,
    configurationRepository: ConfigurationRepository, // TODO (b/309655554): create & use interactor
    lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel,
    aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    primaryBouncerToAodTransitionViewModel: PrimaryBouncerToAodTransitionViewModel,
    occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
) {
    private val color: Flow<Int> =
        configurationRepository.onAnyConfigurationChange
            .map {
                Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.colorSurface)
            }
            .onStart {
                emit(
                    Utils.getColorAttrDefaultColor(
                        context,
                        com.android.internal.R.attr.colorSurface
                    )
                )
            }
    private val alpha: Flow<Float> =
        setOf(
                lockscreenToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                aodToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                goneToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                primaryBouncerToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                occludedToAodTransitionViewModel.deviceEntryBackgroundViewAlpha,
                occludedToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
                dreamingToLockscreenTransitionViewModel.deviceEntryBackgroundViewAlpha,
            )
            .merge()

    val viewModel: Flow<BackgroundViewModel> =
        combine(color, alpha) { color, alpha ->
            BackgroundViewModel(
                alpha = alpha,
                tint = color,
            )
        }

    data class BackgroundViewModel(
        val alpha: Float,
        val tint: Int,
    )
}
