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

import android.app.StatusBarManager
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.internal.logging.UiEventLogger
import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.bouncer.shared.logging.BouncerUiEvent
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.FalsingCollectorActual
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.DisplayId
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.model.SceneContainerPlugin
import com.android.systemui.model.SysUiState
import com.android.systemui.model.updateFlags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.FalsingBeliefListener
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.util.asIndenting
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import dagger.Lazy
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
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
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val bouncerInteractor: BouncerInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val sysUiState: SysUiState,
    @DisplayId private val displayId: Int,
    private val sceneLogger: SceneLogger,
    @FalsingCollectorActual private val falsingCollector: FalsingCollector,
    private val falsingManager: FalsingManager,
    private val powerInteractor: PowerInteractor,
    private val simBouncerInteractor: Lazy<SimBouncerInteractor>,
    private val authenticationInteractor: Lazy<AuthenticationInteractor>,
    private val windowController: NotificationShadeWindowController,
    private val deviceProvisioningInteractor: DeviceProvisioningInteractor,
    private val centralSurfacesOptLazy: Lazy<Optional<CentralSurfaces>>,
    private val headsUpInteractor: HeadsUpNotificationInteractor,
    private val occlusionInteractor: SceneContainerOcclusionInteractor,
    private val faceUnlockInteractor: DeviceEntryFaceAuthInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val uiEventLogger: UiEventLogger,
    private val sceneBackInteractor: SceneBackInteractor,
    private val shadeSessionStorage: SessionStorage,
) : CoreStartable {
    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    override fun start() {
        if (SceneContainerFlag.isEnabled) {
            sceneLogger.logFrameworkEnabled(isEnabled = true)
            hydrateVisibility()
            automaticallySwitchScenes()
            hydrateSystemUiState()
            collectFalsingSignals()
            respondToFalsingDetections()
            hydrateInteractionState()
            handleBouncerOverscroll()
            hydrateWindowController()
            hydrateBackStack()
            resetShadeSessions()
        } else {
            sceneLogger.logFrameworkEnabled(
                isEnabled = false,
                reason = SceneContainerFlag.requirementDescription(),
            )
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        pw.asIndenting().run {
            printSection("SceneContainerFlag") {
                println("isEnabled", SceneContainerFlag.isEnabled)
                printSection("requirementDescription") {
                    println(SceneContainerFlag.requirementDescription())
                }
            }
        }

    private fun resetShadeSessions() {
        applicationScope.launch {
            sceneBackInteractor.backStack
                // We are in a session if either Shade or QuickSettings is on the back stack
                .map { backStack ->
                    backStack.asIterable().any { it == Scenes.Shade || it == Scenes.QuickSettings }
                }
                .distinctUntilChanged()
                // Once a session has ended, clear the session storage.
                .filter { inSession -> !inSession }
                .collect { shadeSessionStorage.clear() }
        }
    }

    /** Updates the visibility of the scene container. */
    private fun hydrateVisibility() {
        applicationScope.launch {
            // TODO(b/296114544): Combine with some global hun state to make it visible!
            deviceProvisioningInteractor.isDeviceProvisioned
                .distinctUntilChanged()
                .flatMapLatest { isAllowedToBeVisible ->
                    if (isAllowedToBeVisible) {
                        combine(
                                sceneInteractor.transitionState.mapNotNull { state ->
                                    when (state) {
                                        is ObservableTransitionState.Idle -> {
                                            if (state.currentScene != Scenes.Gone) {
                                                true to "scene is not Gone"
                                            } else {
                                                false to "scene is Gone"
                                            }
                                        }
                                        is ObservableTransitionState.Transition -> {
                                            if (state.fromScene == Scenes.Gone) {
                                                true to "scene transitioning away from Gone"
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                },
                                headsUpInteractor.isHeadsUpOrAnimatingAway,
                                occlusionInteractor.invisibleDueToOcclusion,
                            ) {
                                visibilityForTransitionState,
                                isHeadsUpOrAnimatingAway,
                                invisibleDueToOcclusion,
                                ->
                                when {
                                    isHeadsUpOrAnimatingAway -> true to "showing a HUN"
                                    invisibleDueToOcclusion -> false to "invisible due to occlusion"
                                    else -> visibilityForTransitionState
                                }
                            }
                            .distinctUntilChanged()
                    } else {
                        flowOf(false to "Device not provisioned or Factory Reset Protection active")
                    }
                }
                .collect { (isVisible, loggingReason) ->
                    sceneInteractor.setVisible(isVisible, loggingReason)
                }
        }
    }

    /** Switches between scenes based on ever-changing application state. */
    private fun automaticallySwitchScenes() {
        handleBouncerImeVisibility()
        handleSimUnlock()
        handleDeviceUnlockStatus()
        handlePowerState()
        handleShadeTouchability()
    }

    private fun handleBouncerImeVisibility() {
        applicationScope.launch {
            // TODO (b/308001302): Move this to a bouncer specific interactor.
            bouncerInteractor.onImeHiddenByUser.collectLatest {
                if (sceneInteractor.currentScene.value == Scenes.Bouncer) {
                    sceneInteractor.changeScene(
                        toScene = Scenes.Lockscreen, // TODO(b/336581871): add sceneState?
                        loggingReason = "IME hidden",
                    )
                }
            }
        }
    }

    private fun handleSimUnlock() {
        applicationScope.launch {
            simBouncerInteractor
                .get()
                .isAnySimSecure
                .sample(deviceUnlockedInteractor.deviceUnlockStatus, ::Pair)
                .collect { (isAnySimLocked, unlockStatus) ->
                    when {
                        isAnySimLocked -> {
                            switchToScene(
                                // TODO(b/336581871): add sceneState?
                                targetSceneKey = Scenes.Bouncer,
                                loggingReason = "Need to authenticate locked SIM card."
                            )
                        }
                        unlockStatus.isUnlocked &&
                            deviceEntryInteractor.canSwipeToEnter.value == false -> {
                            switchToScene(
                                // TODO(b/336581871): add sceneState?
                                targetSceneKey = Scenes.Gone,
                                loggingReason =
                                    "All SIM cards unlocked and device already unlocked and " +
                                        "lockscreen doesn't require a swipe to dismiss."
                            )
                        }
                        else -> {
                            switchToScene(
                                // TODO(b/336581871): add sceneState?
                                targetSceneKey = Scenes.Lockscreen,
                                loggingReason =
                                    "All SIM cards unlocked and device still locked" +
                                        " or lockscreen still requires a swipe to dismiss."
                            )
                        }
                    }
                }
        }
    }

    private fun handleDeviceUnlockStatus() {
        applicationScope.launch {
            // Track the previous scene (sans Bouncer), so that we know where to go when the device
            // is unlocked whilst on the bouncer.
            val previousScene =
                sceneBackInteractor.backScene
                    .filterNot { it == Scenes.Bouncer }
                    .stateIn(this, SharingStarted.Eagerly, initialValue = null)
            deviceUnlockedInteractor.deviceUnlockStatus
                .mapNotNull { deviceUnlockStatus ->
                    val renderedScenes =
                        when (val transitionState = sceneInteractor.transitionState.value) {
                            is ObservableTransitionState.Idle -> setOf(transitionState.currentScene)
                            is ObservableTransitionState.Transition ->
                                setOf(
                                    transitionState.fromScene,
                                    transitionState.toScene,
                                )
                        }
                    val isOnLockscreen = renderedScenes.contains(Scenes.Lockscreen)
                    val isOnBouncer = renderedScenes.contains(Scenes.Bouncer)
                    if (!deviceUnlockStatus.isUnlocked) {
                        return@mapNotNull if (isOnLockscreen || isOnBouncer) {
                            // Already on lockscreen or bouncer, no need to change scenes.
                            null
                        } else {
                            // The device locked while on a scene that's not Lockscreen or Bouncer,
                            // go to Lockscreen.
                            Scenes.Lockscreen to
                                "device locked in non-Lockscreen and non-Bouncer scene"
                        }
                    }

                    if (
                        isOnBouncer &&
                            deviceUnlockStatus.deviceUnlockSource == DeviceUnlockSource.TrustAgent
                    ) {
                        uiEventLogger.log(BouncerUiEvent.BOUNCER_DISMISS_EXTENDED_ACCESS)
                    }
                    when {
                        isOnBouncer ->
                            // When the device becomes unlocked in Bouncer, go to previous scene,
                            // or Gone.
                            if (previousScene.value == Scenes.Lockscreen) {
                                Scenes.Gone to "device was unlocked in Bouncer scene"
                            } else {
                                val prevScene = previousScene.value
                                (prevScene
                                    ?: Scenes.Gone) to
                                    "device was unlocked in Bouncer scene, from sceneKey=$prevScene"
                            }
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
                                deviceUnlockStatus.deviceUnlockSource?.dismissesLockscreen ==
                                    true ->
                                    Scenes.Gone to
                                        "device has been unlocked on lockscreen with bypass " +
                                            "enabled or using an active authentication " +
                                            "mechanism: ${deviceUnlockStatus.deviceUnlockSource}"
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
    }

    private fun handlePowerState() {
        applicationScope.launch {
            powerInteractor.isAsleep.collect { isAsleep ->
                if (isAsleep) {
                    switchToScene(
                        // TODO(b/336581871): add sceneState?
                        targetSceneKey = Scenes.Lockscreen,
                        loggingReason = "device is starting to sleep",
                    )
                } else {
                    val canSwipeToEnter = deviceEntryInteractor.canSwipeToEnter.value
                    val isUnlocked = deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked
                    if (isUnlocked && canSwipeToEnter == false) {
                        val isTransitioningToLockscreen =
                            sceneInteractor.transitioningTo.value == Scenes.Lockscreen
                        if (!isTransitioningToLockscreen) {
                            switchToScene(
                                targetSceneKey = Scenes.Gone,
                                loggingReason =
                                    "device is waking up while unlocked without the ability to" +
                                        " swipe up on lockscreen to enter and not on or" +
                                        " transitioning to, the lockscreen scene.",
                            )
                        }
                    } else if (
                        authenticationInteractor.get().getAuthenticationMethod() ==
                            AuthenticationMethodModel.Sim
                    ) {
                        switchToScene(
                            targetSceneKey = Scenes.Bouncer,
                            loggingReason = "device is starting to wake up with a locked sim",
                        )
                    }
                }
            }
        }
    }

    private fun handleShadeTouchability() {
        applicationScope.launch {
            shadeInteractor.isShadeTouchable
                .distinctUntilChanged()
                .filter { !it }
                .collect {
                    switchToScene(
                        targetSceneKey = Scenes.Lockscreen,
                        loggingReason = "device became non-interactive",
                    )
                }
        }
    }

    /** Keeps [SysUiState] up-to-date */
    private fun hydrateSystemUiState() {
        applicationScope.launch {
            combine(
                    sceneInteractor.transitionState
                        .mapNotNull { it as? ObservableTransitionState.Idle }
                        .map { it.currentScene }
                        .distinctUntilChanged(),
                    occlusionInteractor.invisibleDueToOcclusion,
                ) { sceneKey, invisibleDueToOcclusion ->
                    SceneContainerPlugin.SceneContainerPluginState(
                        scene = sceneKey,
                        invisibleDueToOcclusion = invisibleDueToOcclusion,
                    )
                }
                .collect { sceneContainerPluginState ->
                    sysUiState.updateFlags(
                        displayId,
                        *SceneContainerPlugin.EvaluatorByFlag.map { (flag, evaluator) ->
                                flag to evaluator.invoke(sceneContainerPluginState)
                            }
                            .toTypedArray(),
                    )
                }
        }
    }

    private fun hydrateWindowController() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .mapNotNull { transitionState ->
                    (transitionState as? ObservableTransitionState.Idle)?.currentScene
                }
                .distinctUntilChanged()
                .collect { sceneKey ->
                    windowController.setNotificationShadeFocusable(sceneKey != Scenes.Gone)
                }
        }

        applicationScope.launch {
            deviceEntryInteractor.isDeviceEntered.collect { isDeviceEntered ->
                windowController.setKeyguardShowing(!isDeviceEntered)
            }
        }

        applicationScope.launch {
            sceneInteractor.currentScene
                .map { it == Scenes.Bouncer }
                .distinctUntilChanged()
                .collect { isBouncerShowing ->
                    windowController.setBouncerShowing(isBouncerShowing)
                }
        }

        applicationScope.launch {
            occlusionInteractor.invisibleDueToOcclusion.collect { invisibleDueToOcclusion ->
                windowController.setKeyguardOccluded(invisibleDueToOcclusion)
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
            keyguardInteractor.isDozing.collect { isDozing ->
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
            sceneInteractor.currentScene
                .map { it == Scenes.Bouncer }
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

    /** Switches to the lockscreen when falsing is detected. */
    private fun respondToFalsingDetections() {
        applicationScope.launch {
            conflatedCallbackFlow {
                    val listener = FalsingBeliefListener { trySend(Unit) }
                    falsingManager.addFalsingBeliefListener(listener)
                    awaitClose { falsingManager.removeFalsingBeliefListener(listener) }
                }
                .collect { switchToScene(Scenes.Lockscreen, "Falsing detected.") }
        }
    }

    /** Keeps the interaction state of [CentralSurfaces] up-to-date. */
    private fun hydrateInteractionState() {
        applicationScope.launch {
            deviceUnlockedInteractor.deviceUnlockStatus
                .map { !it.isUnlocked }
                .flatMapLatest { isDeviceLocked ->
                    if (isDeviceLocked) {
                        sceneInteractor.transitionState
                            .mapNotNull { it as? ObservableTransitionState.Idle }
                            .map { it.currentScene }
                            .distinctUntilChanged()
                            .map { sceneKey ->
                                when (sceneKey) {
                                    // When locked, showing the lockscreen scene should be reported
                                    // as "interacting" while showing other scenes should report as
                                    // "not interacting".
                                    //
                                    // This is done here in order to match the legacy
                                    // implementation. The real reason why is lost to lore and myth.
                                    Scenes.Lockscreen -> true
                                    Scenes.Bouncer -> false
                                    Scenes.Shade -> false
                                    Scenes.NotificationsShade -> false
                                    else -> null
                                }
                            }
                    } else {
                        flowOf(null)
                    }
                }
                .collect { isInteractingOrNull ->
                    isInteractingOrNull?.let { isInteracting ->
                        centralSurfaces?.setInteracting(
                            StatusBarManager.WINDOW_STATUS_BAR,
                            isInteracting,
                        )
                    }
                }
        }
    }

    private fun handleBouncerOverscroll() {
        applicationScope.launch {
            sceneInteractor.transitionState
                // Only consider transitions.
                .filterIsInstance<ObservableTransitionState.Transition>()
                // Only consider user-initiated (e.g. drags) that go from bouncer to lockscreen.
                .filter { transition ->
                    transition.fromScene == Scenes.Bouncer &&
                        transition.toScene == Scenes.Lockscreen &&
                        transition.isInitiatedByUserInput
                }
                .flatMapLatest { it.progress }
                // Figure out the direction of scrolling.
                .map { progress ->
                    when {
                        progress > 0 -> 1
                        progress < 0 -> -1
                        else -> 0
                    }
                }
                .distinctUntilChanged()
                // Only consider negative scrolling, AKA overscroll.
                .filter { it == -1 }
                .collect { faceUnlockInteractor.onSwipeUpOnBouncer() }
        }
    }

    private fun switchToScene(targetSceneKey: SceneKey, loggingReason: String) {
        sceneInteractor.changeScene(
            toScene = targetSceneKey,
            loggingReason = loggingReason,
        )
    }

    private fun hydrateBackStack() {
        applicationScope.launch {
            sceneInteractor.currentScene.pairwise().collect { (from, to) ->
                sceneBackInteractor.onSceneChange(from = from, to = to)
            }
        }
    }
}
