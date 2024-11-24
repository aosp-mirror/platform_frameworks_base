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

package com.android.systemui.scene.domain.interactor

import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.disableflags.domain.interactor.DisableFlagsInteractor
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DisabledContentInteractor
@Inject
constructor(private val disableFlagsInteractor: DisableFlagsInteractor) {

    /** Returns `true` if the given [key] is disabled; `false` if it's enabled */
    fun isDisabled(
        key: ContentKey,
        disabledFlags: DisableFlagsModel = disableFlagsInteractor.disableFlags.value,
    ): Boolean {
        return with(disabledFlags) {
            when (key) {
                Scenes.Shade,
                Overlays.NotificationsShade -> !isShadeEnabled()
                Scenes.QuickSettings,
                Overlays.QuickSettingsShade -> !isQuickSettingsEnabled()
                else -> false
            }
        }
    }

    /** Runs the given [block] each time that [key] becomes disabled. */
    suspend fun repeatWhenDisabled(key: ContentKey, block: suspend (disabled: ContentKey) -> Unit) {
        disableFlagsInteractor.disableFlags
            .map { isDisabled(key) }
            .distinctUntilChanged()
            .collectLatest { isDisabled ->
                if (isDisabled) {
                    block(key)
                }
            }
    }

    /**
     * Returns a filtered version of [unfiltered], without action-result entries that would navigate
     * to disabled scenes.
     */
    fun filteredUserActions(
        unfiltered: Flow<Map<UserAction, UserActionResult>>
    ): Flow<Map<UserAction, UserActionResult>> {
        return combine(disableFlagsInteractor.disableFlags, unfiltered) {
            disabledFlags,
            unfilteredMap ->
            unfilteredMap.filterValues { actionResult ->
                val destination =
                    when (actionResult) {
                        is UserActionResult.ChangeScene -> actionResult.toScene
                        is UserActionResult.ShowOverlay -> actionResult.overlay
                        is UserActionResult.ReplaceByOverlay -> actionResult.overlay
                        else -> null
                    }
                if (destination != null) {
                    // results that lead to a disabled destination get filtered out.
                    !isDisabled(key = destination, disabledFlags = disabledFlags)
                } else {
                    // Action results that don't lead to a destination are never filtered out.
                    true
                }
            }
        }
    }
}
