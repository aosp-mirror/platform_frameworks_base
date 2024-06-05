/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.model

import com.android.systemui.dagger.qualifiers.DisplayId

/**
 * In-bulk updates multiple flag values and commits the update.
 *
 * Example:
 * ```
 * sysuiState.updateFlags(
 *     displayId,
 *     SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE to (sceneKey != Scenes.Gone),
 *     SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED to (sceneKey == Scenes.Shade),
 *     SYSUI_STATE_QUICK_SETTINGS_EXPANDED to (sceneKey == Scenes.QuickSettings),
 *     SYSUI_STATE_BOUNCER_SHOWING to (sceneKey == Scenes.Bouncer),
 *     SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING to (sceneKey == Scenes.Lockscreen),
 * )
 * ```
 *
 * You can inject [displayId] by injecting it using:
 * ```
 *     @DisplayId private val displayId: Int`,
 * ```
 */
fun SysUiState.updateFlags(
    @DisplayId displayId: Int,
    vararg flagValuePairs: Pair<Long, Boolean>,
) {
    flagValuePairs.forEach { (flag, enabled) -> setFlag(flag, enabled) }
    commitUpdate(displayId)
}
