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

import androidx.annotation.VisibleForTesting
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
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
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor
import com.android.systemui.statusbar.phone.DozeServiceHost
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.ScrimState
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@SysUISingleton
class ScrimStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val scrimController: ScrimController,
    sceneInteractor: SceneInteractor,
    deviceEntryInteractor: DeviceEntryInteractor,
    keyguardInteractor: KeyguardInteractor,
    occlusionInteractor: SceneContainerOcclusionInteractor,
    biometricUnlockInteractor: BiometricUnlockInteractor,
    private val statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    brightnessMirrorShowingInteractor: BrightnessMirrorShowingInteractor,
    private val dozeServiceHost: DozeServiceHost,
) : CoreStartable {

    @VisibleForTesting
    val scrimState: Flow<ScrimState?> =
        combine(
                deviceEntryInteractor.isDeviceEntered,
                occlusionInteractor.invisibleDueToOcclusion,
                sceneInteractor.currentScene,
                sceneInteractor.currentOverlays,
                sceneInteractor.transitionState,
                keyguardInteractor.isDozing,
                keyguardInteractor.isDreaming,
                biometricUnlockInteractor.unlockState,
                brightnessMirrorShowingInteractor.isShowing,
                keyguardInteractor.isPulsing,
                conflatedCallbackFlow {
                    val listener =
                        DozeServiceHost.HasPendingScreenOffCallbackChangeListener {
                            hasPendingScreenOffCallback ->
                            trySend(hasPendingScreenOffCallback)
                        }
                    dozeServiceHost.setHasPendingScreenOffCallbackChangeListener(listener)
                    awaitClose {
                        dozeServiceHost.setHasPendingScreenOffCallbackChangeListener(null)
                    }
                },
            ) { flowValues ->
                val isDeviceEntered = flowValues[0] as Boolean
                val isOccluded = flowValues[1] as Boolean
                val currentScene = flowValues[2] as SceneKey
                val currentOverlays = flowValues[3] as Set<OverlayKey>
                val transitionState = flowValues[4] as ObservableTransitionState
                val isDozing = flowValues[5] as Boolean
                val isDreaming = flowValues[6] as Boolean
                val biometricUnlockState = flowValues[7] as BiometricUnlockModel
                val isBrightnessMirrorVisible = flowValues[8] as Boolean
                val isPulsing = flowValues[9] as Boolean
                val hasPendingScreenOffCallback = flowValues[10] as Boolean

                // This is true when the lockscreen scene is either the current scene or somewhere
                // in the navigation back stack of scenes.
                val isOnKeyguard = !isDeviceEntered
                val isCurrentSceneBouncer = currentScene == Scenes.Bouncer
                // This is true when moving away from one of the keyguard scenes to the gone scene.
                // It happens only when unlocking or when dismissing a dismissible lockscreen.
                val isTransitioningAwayFromKeyguard =
                    transitionState is ObservableTransitionState.Transition.ChangeScene &&
                        transitionState.fromScene.isKeyguard() &&
                        transitionState.toScene == Scenes.Gone

                // This is true when any of the shade scenes or overlays is the current content.
                val isCurrentContentShade =
                    currentScene.isShade() || currentOverlays.any { it.isShade() }

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
                    // This will cancel the keyguardFadingAway animation if it is running. We need
                    // to do this as otherwise it can remain pending and leave keyguard in a weird
                    // state.
                    onKeyguardFadedAway(isTransitioningAwayFromKeyguard)
                    if (!transitionState.isTransitioningToShade()) {
                        // Safeguard which prevents the scrim from being stuck in the wrong
                        // state
                        Model(scrimState = ScrimState.KEYGUARD, unlocking = unlocking)
                    } else {
                        // Assume scrim state for shade is already correct and do nothing
                        null
                    }
                } else if (isCurrentSceneBouncer && !unlocking) {
                    // Bouncer needs the front scrim when it's on top of an activity, tapping on a
                    // notification, editing QS or being dismissed by
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
                } else if (isCurrentContentShade && !isDeviceEntered) {
                    Model(scrimState = ScrimState.SHADE_LOCKED, unlocking = unlocking)
                } else if (isPulsing) {
                    Model(scrimState = ScrimState.PULSING, unlocking = unlocking)
                } else if (hasPendingScreenOffCallback) {
                    Model(scrimState = ScrimState.OFF, unlocking = unlocking)
                } else if (isDozing && !unlocking) {
                    // This will cancel the keyguardFadingAway animation if it is running. We need
                    // to do this as otherwise it can remain pending and leave keyguard in a weird
                    // state.
                    onKeyguardFadedAway(isTransitioningAwayFromKeyguard)
                    Model(scrimState = ScrimState.AOD, unlocking = false)
                } else if (isIdleOnCommunal) {
                    if (isOnKeyguard && isDreaming && !unlocking) {
                        Model(scrimState = ScrimState.GLANCEABLE_HUB_OVER_DREAM, unlocking = false)
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
            .onEach { model ->
                if (model != null) {
                    scrimController.setExpansionAffectsAlpha(!model.unlocking)
                }
            }
            .map { model -> model?.scrimState }

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        hydrateScrimState()
    }

    private fun hydrateScrimState() {
        applicationScope.launch {
            scrimState.filterNotNull().collect { scrimState ->
                scrimController.transitionTo(scrimState)
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

    private fun ContentKey.isShade(): Boolean {
        return this == Scenes.Shade ||
            this == Scenes.QuickSettings ||
            this == Overlays.NotificationsShade ||
            this == Overlays.QuickSettingsShade
    }

    private fun ObservableTransitionState.isTransitioningToShade(): Boolean {
        return when (this) {
            is ObservableTransitionState.Idle -> false
            is ObservableTransitionState.Transition.ChangeScene ->
                !fromScene.isShade() && toScene.isShade()
            is ObservableTransitionState.Transition.ReplaceOverlay ->
                !fromOverlay.isShade() && toOverlay.isShade()
            is ObservableTransitionState.Transition.ShowOrHideOverlay ->
                !fromContent.isShade() && toContent.isShade()
        }
    }

    private data class Model(val scrimState: ScrimState, val unlocking: Boolean)
}
