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

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.communal.data.repository.CommunalSceneRepository
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalTransitionKeys
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Lockscreen affordance that opens the glanceable hub. */
@SysUISingleton
class GlanceableHubQuickAffordanceConfig
@Inject
constructor(
    @Application private val context: Context,
    private val communalSceneRepository: CommunalSceneRepository,
    private val communalInteractor: CommunalInteractor,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val sceneInteractor: SceneInteractor,
) : KeyguardQuickAffordanceConfig {

    private val pickerNameResourceId = R.string.glanceable_hub_lockscreen_affordance_label

    override val key: String = BuiltInKeyguardQuickAffordanceKeys.GLANCEABLE_HUB

    override fun pickerName(): String = context.getString(pickerNameResourceId)

    override val pickerIconResourceId: Int
        get() = R.drawable.ic_widgets

    override val lockScreenState: Flow<KeyguardQuickAffordanceConfig.LockScreenState>
        get() = flow {
            emit(
                if (!communalSettingsInteractor.isV2FlagEnabled()) {
                    Log.i(TAG, "Button hidden on lockscreen: flag not enabled.")
                    KeyguardQuickAffordanceConfig.LockScreenState.Hidden
                } else if (!communalInteractor.isCommunalEnabled.value) {
                    Log.i(TAG, "Button hidden on lockscreen: hub not enabled in settings.")
                    KeyguardQuickAffordanceConfig.LockScreenState.Hidden
                } else {
                    KeyguardQuickAffordanceConfig.LockScreenState.Visible(
                        icon =
                            Icon.Resource(
                                pickerIconResourceId,
                                ContentDescription.Resource(pickerNameResourceId),
                            )
                    )
                }
            )
        }

    override suspend fun getPickerScreenState(): KeyguardQuickAffordanceConfig.PickerScreenState {
        return if (!communalSettingsInteractor.isV2FlagEnabled()) {
            Log.i(TAG, "Button unavailable in picker: flag not enabled.")
            KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice
        } else if (!communalInteractor.isCommunalEnabled.value) {
            Log.i(TAG, "Button disabled in picker: hub not enabled in settings.")
            KeyguardQuickAffordanceConfig.PickerScreenState.Disabled(
                explanation =
                    context.getString(R.string.glanceable_hub_lockscreen_affordance_disabled_text),
                actionText =
                    context.getString(
                        R.string.glanceable_hub_lockscreen_affordance_action_button_label
                    ),
                actionIntent = Intent(Settings.ACTION_LOCKSCREEN_SETTINGS),
            )
        } else {
            KeyguardQuickAffordanceConfig.PickerScreenState.Default()
        }
    }

    override fun onTriggered(
        expandable: Expandable?
    ): KeyguardQuickAffordanceConfig.OnTriggeredResult {
        if (SceneContainerFlag.isEnabled) {
            sceneInteractor.changeScene(Scenes.Communal, "lockscreen to communal from shortcut")
        } else {
            communalSceneRepository.changeScene(
                CommunalScenes.Communal,
                transitionKey = CommunalTransitionKeys.SimpleFade,
            )
        }
        return KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled
    }

    companion object {
        private const val TAG = "GlanceableHubQuickAffordanceConfig"
    }
}
