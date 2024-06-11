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

package com.android.systemui.keyguard.data.quickaffordance

import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.communal.data.repository.CommunalSceneRepository
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Shortcut that opens the glanceable hub. */
// TODO(b/339667383): delete or properly implement this once a product decision is made
@SysUISingleton
class GlanceableHubQuickAffordanceConfig
@Inject
constructor(
    private val communalRepository: CommunalSceneRepository,
) : KeyguardQuickAffordanceConfig {

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.GLANCEABLE_HUB

    override fun pickerName(): String = "Glanceable hub"

    override val pickerIconResourceId = R.drawable.ic_widgets

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState> by lazy {
        if (Flags.glanceableHubShortcutButton()) {
            val contentDescription = ContentDescription.Loaded(pickerName())
            val icon = Icon.Resource(pickerIconResourceId, contentDescription)
            flowOf(KeyguardQuickAffordanceConfig.LockScreenState.Visible(icon))
        } else {
            flowOf(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }
    }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
    }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        communalRepository.changeScene(CommunalScenes.Communal, null)
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }
}
