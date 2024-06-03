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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.BiometricUnlockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.phone.DozeServiceHost
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.ScrimState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@SysUISingleton
class ScrimStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val scrimController: ScrimController,
    private val sceneInteractor: SceneInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val occlusionInteractor: SceneContainerOcclusionInteractor,
    private val biometricUnlockInteractor: BiometricUnlockInteractor,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    private val dozeServiceHost: DozeServiceHost,
) : CoreStartable {

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        hydrateScrimState()
    }

    private fun hydrateScrimState() {
        applicationScope.launch {
            combine(
                    deviceEntryInteractor.isDeviceEntered,
                    occlusionInteractor.invisibleDueToOcclusion,
                    sceneInteractor.currentScene,
                    sceneInteractor.transitionState,
                    keyguardInteractor.isDozing,
                    keyguardInteractor.isDreaming,
                    biometricUnlockInteractor.unlockState,
                    shadeInteractor.isAnyExpanded,
                    brightnessMirrorShowingInteractor.isShowing,
                    keyguardInteractor.isPulsing,
                ) { flowValues ->
                    val isDeviceEntered = flowValues[0] as Boolean
                    val isOccluded = flowValues[1] as Boolean
                    val currentScene = flowValues[2] as SceneKey
                    val transitionState = flowValues[3] as ObservableTransitionState
                    val isDozing = flowValues[4] as Boolean
                    val isDreaming = flowValues[5] as Boolean
                    val biometricUnlockState = flowValues[6] as BiometricUnlockModel
                    val isAnyShadeExpanded = flowValues[7] as Boolean
                    val isBrightnessMirrorVisible = flowValues[8] as Boolean
                    val isPulsing = flowValues[9] as Boolean

                    // This is true when the lockscreen scene is either the current scene or
                    // somewhere in the navigation back stack of scenes.
                    val isOnKeyguard = !isDeviceEntered
                    val isCurrentSceneBouncer = currentScene == Scenes.Bouncer
                    // This is true when moving away from one of the keyguard scenes to the gone
                    // scene. It happens only when unlocking or when dismissing a dismissible
                    // lockscreen.
                    val isTransitioningAwayFromKeyguard =
                        transitionState is ObservableTransitionState.Transition &&
                            transitionState.fromScene.isKeyguard() &&
                            transitionState.toScene == Scenes.Gone

                    // This is true when any of the shade scenes is the current scene.
                    val isCurrentSceneShade = currentScene.isShade()
                    // This is true when moving into one of the shade scenes when a non-shade scene.
                    val isTransitioningToShade =
                        transitionState is ObservableTransitionState.Transition &&
                            !transitionState.fromScene.isShade() &&
                            transitionState.toScene.isShade()

                    // This is true after completing a transition to communal.
                    val isIdleOnCommunal = transitionState.isIdle(Scenes.Communal)

                    // This is true during the process of an unlock of the device.
                    // TODO(b/330587738): add support for remote unlock animations. If such an
                    //   animation is underway, unlocking should be true.
                    val unlocking =
                        isOnKeyguard &&
                            (biometricUnlockState.mode == BiometricUnlockMode.WAKE_AND_UNLOCK ||
                                isTransitioningAwayFromKeyguard)

                    if (alternateBouncerInteractor.isVisibleState()) {
                        // This will cancel the keyguardFadingAway animation if it is running. We
                        // need to do this as otherwise it can remain pending and leave keyguard in
                        // a weird state.
                        onKeyguardFadedAway(isTransitioningAwayFromKeyguard)
                        if (!isTransitioningToShade || (isOccluded && !isAnyShadeExpanded)) {
                            // Safeguard which prevents the scrim from being stuck in the wrong
                            // state
                            Model(scrimState = ScrimState.KEYGUARD, unlocking = unlocking)
                        } else {
                            // Assume scrim state for shade is already correct and do nothing
                            null
                        }
                    } else if (isCurrentSceneBouncer && !unlocking) {
                        // Bouncer needs the front scrim when it's on top of an activity, tapping on
                        // a notification, editing QS or being dismissed by
                        // FLAG_DISMISS_KEYGUARD_ACTIVITY.
                        Model(
                            scrimState =
                                if (statusBarKeyguardViewManager.primaryBouncerNeedsScrimming()) {
                                    ScrimState.BOUNCER_SCRIMMED
                                } else {
                                    ScrimState.BOUNCER
                                },
                            unlocking = false,
                        )
                    } else if (isBrightnessMirrorVisible) {
                        Model(scrimState = ScrimState.BRIGHTNESS_MIRROR, unlocking = unlocking)
                    } else if (isCurrentSceneShade && !isDeviceEntered) {
                        Model(scrimState = ScrimState.SHADE_LOCKED, unlocking = unlocking)
                    } else if (isPulsing) {
                        Model(scrimState = ScrimState.PULSING, unlocking = unlocking)
                    } else if (dozeServiceHost.hasPendingScreenOffCallback()) {
                        Model(scrimState = ScrimState.OFF, unlocking = unlocking)
                    } else if (isDozing && !unlocking) {
                        // This will cancel the keyguardFadingAway animation if it is running. We
                        // need to do this as otherwise it can remain pending and leave keyguard in
                        // a weird state.
                        onKeyguardFadedAway(isTransitioningAwayFromKeyguard)
                        Model(scrimState = ScrimState.AOD, unlocking = false)
                    } else if (isIdleOnCommunal) {
                        if (isOnKeyguard && isDreaming && !unlocking) {
                            Model(
                                scrimState = ScrimState.GLANCEABLE_HUB_OVER_DREAM,
                                unlocking = false
                            )
                        } else {
                            Model(scrimState = ScrimState.GLANCEABLE_HUB, unlocking = unlocking)
                        }
                    } else if (isOnKeyguard && !unlocking && !isOccluded) {
                        Model(scrimState = ScrimState.KEYGUARD, unlocking = false)
                    } else if (isOnKeyguard && !unlocking && isDreaming) {
                        Model(scrimState = ScrimState.DREAMING, unlocking = false)
                    } else {
                        Model(scrimState = ScrimState.UNLOCKED, unlocking = unlocking)
                    }
                }
                .filterNotNull()
                .collect { model ->
                    scrimController.setExpansionAffectsAlpha(!model.unlocking)
                    scrimController.transitionTo(model.scrimState)
                }
        }
    }

    private fun onKeyguardFadedAway(isKeyguardGoingAway: Boolean) {
        if (isKeyguardGoingAway) {
            statusBarKeyguardViewManager.onKeyguardFadedAway()
        }
    }

    private fun SceneKey.isKeyguard(): Boolean {
        return this == Scenes.Lockscreen || this == Scenes.Bouncer
    }

    private fun SceneKey.isShade(): Boolean {
        return this == Scenes.Shade ||
            this == Scenes.QuickSettings ||
            this == Scenes.NotificationsShade ||
            this == Scenes.QuickSettingsShade
    }

    private data class Model(
        val scrimState: ScrimState,
        val unlocking: Boolean,
    )
}
