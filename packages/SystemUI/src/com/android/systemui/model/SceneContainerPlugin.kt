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

package com.android.systemui.model

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_COMMUNAL_HUB_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags
import dagger.Lazy
import javax.inject.Inject

/**
 * A plugin for [SysUiState] that provides overrides for certain state flags that must be pulled
 * from the scene framework when that framework is enabled.
 */
@SysUISingleton
class SceneContainerPlugin
@Inject
constructor(
    private val sceneInteractor: Lazy<SceneInteractor>,
    private val occlusionInteractor: Lazy<SceneContainerOcclusionInteractor>,
) {

    /**
     * Returns an override value for the given [flag] or `null` if the scene framework isn't enabled
     * or if the flag value doesn't need to be overridden.
     */
    fun flagValueOverride(@SystemUiStateFlags flag: Long): Boolean? {
        if (!SceneContainerFlag.isEnabled) {
            return null
        }

        val transitionState = sceneInteractor.get().transitionState.value
        val idleTransitionStateOrNull = transitionState as? ObservableTransitionState.Idle
        val invisibleDueToOcclusion = occlusionInteractor.get().invisibleDueToOcclusion.value
        return idleTransitionStateOrNull?.let { idleState ->
            EvaluatorByFlag[flag]?.invoke(
                SceneContainerPluginState(
                    scene = idleState.currentScene,
                    overlays = idleState.currentOverlays,
                    invisibleDueToOcclusion = invisibleDueToOcclusion,
                )
            )
        }
    }

    companion object {

        /**
         * Value evaluator function by state flag ID.
         *
         * The value evaluator function can be invoked, passing in the current [SceneKey] to know
         * the override value of the flag ID.
         *
         * If the map doesn't contain an entry for a certain flag ID, it means that it doesn't need
         * to be overridden by the scene framework.
         */
        val EvaluatorByFlag =
            mapOf<Long, (SceneContainerPluginState) -> Boolean>(
                SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE to
                    {
                        it.scene != Scenes.Gone || it.overlays.isNotEmpty()
                    },
                SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED to
                    {
                        when {
                            it.invisibleDueToOcclusion -> false
                            it.scene == Scenes.Lockscreen -> true
                            it.scene == Scenes.Shade -> true
                            Overlays.NotificationsShade in it.overlays -> true
                            else -> false
                        }
                    },
                SYSUI_STATE_QUICK_SETTINGS_EXPANDED to
                    {
                        it.scene == Scenes.QuickSettings ||
                            Overlays.QuickSettingsShade in it.overlays
                    },
                SYSUI_STATE_BOUNCER_SHOWING to { it.scene == Scenes.Bouncer },
                SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING to
                    {
                        it.scene == Scenes.Lockscreen && !it.invisibleDueToOcclusion
                    },
                SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED to
                    {
                        it.scene == Scenes.Lockscreen && it.invisibleDueToOcclusion
                    },
                SYSUI_STATE_COMMUNAL_HUB_SHOWING to { it.scene == Scenes.Communal },
            )
    }

    data class SceneContainerPluginState(
        val scene: SceneKey,
        val overlays: Set<OverlayKey>,
        val invisibleDueToOcclusion: Boolean,
    )
}
