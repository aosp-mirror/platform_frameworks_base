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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorActual
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.model.SceneContainerPlugin
import com.android.systemui.model.SysUiState
import com.android.systemui.model.updateFlags
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import dagger.Lazy
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val bouncerInteractor: BouncerInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val flags: SceneContainerFlags,
    private val sysUiState: SysUiState,
    @DisplayId private val displayId: Int,
    private val sceneLogger: SceneLogger,
    @FalsingCollectorActual private val falsingCollector: FalsingCollector,
    private val powerInteractor: PowerInteractor,
    private val simBouncerInteractor: Lazy<SimBouncerInteractor>,
    private val authenticationInteractor: Lazy<AuthenticationInteractor>,
    private val windowController: NotificationShadeWindowController,
) : CoreStartable {

    override fun start() {
        if (flags.isEnabled()) {
            sceneLogger.logFrameworkEnabled(isEnabled = true)
            hydrateVisibility()
            automaticallySwitchScenes()
            hydrateSystemUiState()
            collectFalsingSignals()
            hydrateWindowFocus()
        } else {
            sceneLogger.logFrameworkEnabled(
                isEnabled = false,
                reason = flags.requirementDescription(),
            )
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        pw.asIndenting().run {
            printSection("SceneContainerFlags") {
                println("isEnabled", flags.isEnabled())
                printSection("requirementDescription") { println(flags.requirementDescription()) }
                println("flexiNotifsEnabled", flags.flexiNotifsEnabled())
            }
        }

    /** Updates the visibility of the scene container. */
    private fun hydrateVisibility() {
        applicationScope.launch {
            // TODO(b/296114544): Combine with some global hun state to make it visible!
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
            // TODO (b/308001302): Move this to a bouncer specific interactor.
            bouncerInteractor.onImeHiddenByUser.collectLatest {
                if (sceneInteractor.desiredScene.value.key == SceneKey.Bouncer) {
                    sceneInteractor.changeScene(
                        scene = SceneModel(SceneKey.Lockscreen),
                        loggingReason = "IME hidden",
                    )
                }
            }
        }
        applicationScope.launch {
            simBouncerInteractor.get().isAnySimSecure.collect { isAnySimLocked ->
                val canSwipeToEnter = deviceEntryInteractor.canSwipeToEnter.value
                val isUnlocked = deviceEntryInteractor.isUnlocked.value

                when {
                    isAnySimLocked -> {
                        switchToScene(
                            targetSceneKey = SceneKey.Bouncer,
                            loggingReason = "Need to authenticate locked SIM card."
                        )
                    }
                    isUnlocked && canSwipeToEnter == false -> {
                        switchToScene(
                            targetSceneKey = SceneKey.Gone,
                            loggingReason =
                                "All SIM cards unlocked and device already" +
                                    " unlocked and lockscreen doesn't require a swipe to dismiss."
                        )
                    }
                    else -> {
                        switchToScene(
                            targetSceneKey = SceneKey.Lockscreen,
                            loggingReason =
                                "All SIM cards unlocked and device still locked" +
                                    " or lockscreen still requires a swipe to dismiss."
                        )
                    }
                }
            }
        }
        applicationScope.launch {
            deviceEntryInteractor.isUnlocked
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
                    val isOnLockscreen = renderedScenes.contains(SceneKey.Lockscreen)
                    val isOnBouncer = renderedScenes.contains(SceneKey.Bouncer)
                    if (!isUnlocked) {
                        return@mapNotNull if (isOnLockscreen || isOnBouncer) {
                            // Already on lockscreen or bouncer, no need to change scenes.
                            null
                        } else {
                            // The device locked while on a scene that's not Lockscreen or Bouncer,
                            // go to Lockscreen.
                            SceneKey.Lockscreen to
                                "device locked in non-Lockscreen and non-Bouncer scene"
                        }
                    }

                    val isBypassEnabled = deviceEntryInteractor.isBypassEnabled.value
                    val canSwipeToEnter = deviceEntryInteractor.canSwipeToEnter.value
                    when {
                        isOnBouncer ->
                            // When the device becomes unlocked in Bouncer, go to Gone.
                            SceneKey.Gone to "device was unlocked in Bouncer scene"
                        isOnLockscreen ->
                            // The lockscreen should be dismissed automatically in 2 scenarios:
                            // 1. When face auth bypass is enabled and authentication happens while
                            //    the user is on the lockscreen.
                            // 2. Whenever the user authenticates using an active authentication
                            //    mechanism like fingerprint auth. Since canSwipeToEnter is true
                            //    when the user is passively authenticated, the false value here
                            //    when the unlock state changes indicates this is an active
                            //    authentication attempt.
                            when {
                                isBypassEnabled ->
                                    SceneKey.Gone to
                                        "device has been unlocked on lockscreen with bypass" +
                                            " enabled"
                                canSwipeToEnter == false ->
                                    SceneKey.Gone to
                                        "device has been unlocked on lockscreen using an active" +
                                            " authentication mechanism"
                                else -> null
                            }
                        // Not on lockscreen or bouncer, so remain in the current scene.
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
            powerInteractor.isAsleep.collect { isAsleep ->
                if (isAsleep) {
                    switchToScene(
                        targetSceneKey = SceneKey.Lockscreen,
                        loggingReason = "device is starting to sleep",
                    )
                } else {
                    val canSwipeToEnter = deviceEntryInteractor.canSwipeToEnter.value
                    val isUnlocked = deviceEntryInteractor.isUnlocked.value
                    if (isUnlocked && canSwipeToEnter == false) {
                        switchToScene(
                            targetSceneKey = SceneKey.Gone,
                            loggingReason =
                                "device is waking up while unlocked without the ability" +
                                    " to swipe up on lockscreen to enter.",
                        )
                    } else if (
                        authenticationInteractor.get().getAuthenticationMethod() ==
                            AuthenticationMethodModel.Sim
                    ) {
                        switchToScene(
                            targetSceneKey = SceneKey.Bouncer,
                            loggingReason = "device is starting to wake up with a locked sim"
                        )
                    }
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
                        *SceneContainerPlugin.EvaluatorByFlag.map { (flag, evaluator) ->
                                flag to evaluator.invoke(sceneKey)
                            }
                            .toTypedArray(),
                    )
                }
        }
    }

    /** Collects and reports signals into the falsing system. */
    private fun collectFalsingSignals() {
        applicationScope.launch {
            deviceEntryInteractor.isDeviceEntered.collect { isLockscreenDismissed ->
                if (isLockscreenDismissed) {
                    falsingCollector.onSuccessfulUnlock()
                }
            }
        }

        applicationScope.launch {
            keyguardInteractor.isDozing.distinctUntilChanged().collect { isDozing ->
                falsingCollector.setShowingAod(isDozing)
            }
        }

        applicationScope.launch {
            keyguardInteractor.isAodAvailable
                .flatMapLatest { isAodAvailable ->
                    if (!isAodAvailable) {
                        powerInteractor.detailedWakefulness
                    } else {
                        emptyFlow()
                    }
                }
                .distinctUntilChangedBy { it.isAwake() }
                .collect { wakefulness ->
                    when {
                        wakefulness.isAwakeFromTouch() -> falsingCollector.onScreenOnFromTouch()
                        wakefulness.isAwake() -> falsingCollector.onScreenTurningOn()
                        wakefulness.isAsleep() -> falsingCollector.onScreenOff()
                    }
                }
        }

        applicationScope.launch {
            sceneInteractor.desiredScene
                .map { it.key == SceneKey.Bouncer }
                .distinctUntilChanged()
                .collect { switchedToBouncerScene ->
                    if (switchedToBouncerScene) {
                        falsingCollector.onBouncerShown()
                    } else {
                        falsingCollector.onBouncerHidden()
                    }
                }
        }
    }

    /** Keeps the focus state of the window view up-to-date. */
    private fun hydrateWindowFocus() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .mapNotNull { transitionState ->
                    (transitionState as? ObservableTransitionState.Idle)?.scene
                }
                .distinctUntilChanged()
                .collect { sceneKey ->
                    windowController.setNotificationShadeFocusable(sceneKey != SceneKey.Gone)
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
