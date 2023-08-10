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

package com.android.systemui.scene.domain.startable

import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.domain.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.model.SysUiState
import com.android.systemui.model.updateFlags
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Hooks up business logic that manipulates the state of the [SceneInteractor] for the system UI
 * scene container based on state from other systems.
 */
@SysUISingleton
class SceneContainerStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val featureFlags: FeatureFlags,
    private val sysUiState: SysUiState,
    @DisplayId private val displayId: Int,
    private val sceneLogger: SceneLogger,
) : CoreStartable {

    override fun start() {
        if (featureFlags.isEnabled(Flags.SCENE_CONTAINER)) {
            sceneLogger.logFrameworkEnabled(isEnabled = true)
            hydrateVisibility()
            automaticallySwitchScenes()
            hydrateSystemUiState()
        } else {
            sceneLogger.logFrameworkEnabled(isEnabled = false)
        }
    }

    /** Updates the visibility of the scene container. */
    private fun hydrateVisibility() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .mapNotNull { state ->
                    when (state) {
                        is ObservableTransitionState.Idle -> {
                            if (state.scene != SceneKey.Gone) {
                                true to "scene is not Gone"
                            } else {
                                false to "scene is Gone"
                            }
                        }
                        is ObservableTransitionState.Transition -> {
                            if (state.fromScene == SceneKey.Gone) {
                                true to "scene transitioning away from Gone"
                            } else {
                                null
                            }
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { (isVisible, loggingReason) ->
                    sceneInteractor.setVisible(isVisible, loggingReason)
                }
        }
    }

    /** Switches between scenes based on ever-changing application state. */
    private fun automaticallySwitchScenes() {
        applicationScope.launch {
            authenticationInteractor.isUnlocked
                .mapNotNull { isUnlocked ->
                    val renderedScenes =
                        when (val transitionState = sceneInteractor.transitionState.value) {
                            is ObservableTransitionState.Idle -> setOf(transitionState.scene)
                            is ObservableTransitionState.Transition ->
                                setOf(
                                    transitionState.progress,
                                    transitionState.toScene,
                                )
                        }
                    val isBypassEnabled = authenticationInteractor.isBypassEnabled()
                    when {
                        isUnlocked ->
                            when {
                                // When the device becomes unlocked in Bouncer, go to Gone.
                                renderedScenes.contains(SceneKey.Bouncer) ->
                                    SceneKey.Gone to "device unlocked in Bouncer scene"

                                // When the device becomes unlocked in Lockscreen, go to Gone if
                                // bypass is enabled.
                                renderedScenes.contains(SceneKey.Lockscreen) ->
                                    if (isBypassEnabled) {
                                        SceneKey.Gone to
                                            "device unlocked in Lockscreen scene with bypass"
                                    } else {
                                        null
                                    }

                                // We got unlocked while on a scene that's not Lockscreen or
                                // Bouncer, no need to change scenes.
                                else -> null
                            }

                        // When the device becomes locked, to Lockscreen.
                        !isUnlocked ->
                            when {
                                // Already on lockscreen or bouncer, no need to change scenes.
                                renderedScenes.contains(SceneKey.Lockscreen) ||
                                    renderedScenes.contains(SceneKey.Bouncer) -> null

                                // We got locked while on a scene that's not Lockscreen or Bouncer,
                                // go to Lockscreen.
                                else ->
                                    SceneKey.Lockscreen to
                                        "device locked in non-Lockscreen and non-Bouncer scene"
                            }
                        else -> null
                    }
                }
                .collect { (targetSceneKey, loggingReason) ->
                    switchToScene(
                        targetSceneKey = targetSceneKey,
                        loggingReason = loggingReason,
                    )
                }
        }

        applicationScope.launch {
            keyguardInteractor.wakefulnessModel
                .map { wakefulnessModel -> wakefulnessModel.state }
                .distinctUntilChanged()
                .collect { wakefulnessState ->
                    when (wakefulnessState) {
                        WakefulnessState.STARTING_TO_SLEEP -> {
                            switchToScene(
                                targetSceneKey = SceneKey.Lockscreen,
                                loggingReason = "device is starting to sleep",
                            )
                        }
                        WakefulnessState.STARTING_TO_WAKE -> {
                            val authMethod = authenticationInteractor.getAuthenticationMethod()
                            val isUnlocked = authenticationInteractor.isUnlocked.value
                            when {
                                authMethod == AuthenticationMethodModel.None -> {
                                    switchToScene(
                                        targetSceneKey = SceneKey.Gone,
                                        loggingReason =
                                            "device is starting to wake up while auth method is" +
                                                " none",
                                    )
                                }
                                authMethod.isSecure && isUnlocked -> {
                                    switchToScene(
                                        targetSceneKey = SceneKey.Gone,
                                        loggingReason =
                                            "device is starting to wake up while unlocked with a" +
                                                " secure auth method",
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
        }
    }

    /** Keeps [SysUiState] up-to-date */
    private fun hydrateSystemUiState() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .mapNotNull { it as? ObservableTransitionState.Idle }
                .map { it.scene }
                .distinctUntilChanged()
                .collect { sceneKey ->
                    sysUiState.updateFlags(
                        displayId,
                        SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE to (sceneKey != SceneKey.Gone),
                        SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED to (sceneKey == SceneKey.Shade),
                        SYSUI_STATE_QUICK_SETTINGS_EXPANDED to (sceneKey == SceneKey.QuickSettings),
                        SYSUI_STATE_BOUNCER_SHOWING to (sceneKey == SceneKey.Bouncer),
                        SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING to
                            (sceneKey == SceneKey.Lockscreen),
                    )
                }
        }
    }

    private fun switchToScene(targetSceneKey: SceneKey, loggingReason: String) {
        sceneInteractor.changeScene(
            scene = SceneModel(targetSceneKey),
            loggingReason = loggingReason,
        )
    }
}
