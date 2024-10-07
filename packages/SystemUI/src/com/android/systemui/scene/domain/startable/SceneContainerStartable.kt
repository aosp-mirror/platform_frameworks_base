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
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.CoreStartable
import com.android.systemui.Flags
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
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
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.KeyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.WindowManagerLockscreenVisibilityInteractor
import com.android.systemui.model.SceneContainerPlugin
import com.android.systemui.model.SysUiState
import com.android.systemui.model.updateFlags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.FalsingBeliefListener
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.data.model.sceneStackOf
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.session.shared.SessionStorage
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.logger.SceneLogger
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.util.asIndenting
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer
import dagger.Lazy
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class SceneContainerStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val sceneInteractor: SceneInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val deviceEntryHapticsInteractor: DeviceEntryHapticsInteractor,
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
    private val windowMgrLockscreenVisInteractor: WindowManagerLockscreenVisibilityInteractor,
    private val keyguardEnabledInteractor: KeyguardEnabledInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val vibratorHelper: VibratorHelper,
    private val msdlPlayer: MSDLPlayer,
) : CoreStartable {
    private val centralSurfaces: CentralSurfaces?
        get() = centralSurfacesOptLazy.get().getOrNull()

    private val authInteractionProperties = AuthInteractionProperties()

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
            handleDeviceEntryHapticsWhileDeviceLocked()
            hydrateWindowController()
            hydrateBackStack()
            resetShadeSessions()
            handleKeyguardEnabledness()
            notifyKeyguardDismissCancelledCallbacks()
            refreshLockscreenEnabled()
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
            combine(
                    sceneBackInteractor.backStack
                        // We are in a session if either Shade or QuickSettings is on the back stack
                        .map { backStack ->
                            backStack.asIterable().any {
                                // TODO(b/356596436): Include overlays in the back stack as well.
                                it == Scenes.Shade || it == Scenes.QuickSettings
                            }
                        }
                        .distinctUntilChanged(),
                    // We are also in a session if either Notifications Shade or QuickSettings Shade
                    // is currently shown (whether idle or animating).
                    shadeInteractor.isAnyExpanded,
                ) { inBackStack, isShadeShown ->
                    inBackStack || isShadeShown
                }
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
                .flatMapLatest { isAllowedToBeVisible ->
                    if (isAllowedToBeVisible) {
                        combine(
                                sceneInteractor.transitionState.mapNotNull { state ->
                                    when (state) {
                                        is ObservableTransitionState.Idle -> {
                                            if (state.currentScene != Scenes.Gone) {
                                                true to "scene is not Gone"
                                            } else if (state.currentOverlays.isNotEmpty()) {
                                                true to "overlay is shown"
                                            } else {
                                                false to "scene is Gone and no overlays are shown"
                                            }
                                        }
                                        is ObservableTransitionState.Transition -> {
                                            if (state.fromContent == Scenes.Gone) {
                                                true to "scene transitioning away from Gone"
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                },
                                headsUpInteractor.isHeadsUpOrAnimatingAway,
                                occlusionInteractor.invisibleDueToOcclusion,
                                alternateBouncerInteractor.isVisible,
                            ) {
                                visibilityForTransitionState,
                                isHeadsUpOrAnimatingAway,
                                invisibleDueToOcclusion,
                                isAlternateBouncerVisible ->
                                when {
                                    isHeadsUpOrAnimatingAway -> true to "showing a HUN"
                                    isAlternateBouncerVisible -> true to "showing alternate bouncer"
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
        handleSurfaceBehindKeyguardVisibility()
    }

    private fun handleSurfaceBehindKeyguardVisibility() {
        applicationScope.launch {
            sceneInteractor.currentScene.collectLatest { currentScene ->
                if (currentScene == Scenes.Lockscreen) {
                    // Wait for the screen to be on
                    powerInteractor.isAwake.first { it }
                    // Wait for surface to become visible
                    windowMgrLockscreenVisInteractor.surfaceBehindVisibility.first { it }
                    // Make sure the device is actually unlocked before force-changing the scene
                    deviceUnlockedInteractor.deviceUnlockStatus.first { it.isUnlocked }
                    // Override the current transition, if any, by forcing the scene to Gone
                    sceneInteractor.changeScene(
                        toScene = Scenes.Gone,
                        loggingReason = "surface behind keyguard is visible",
                    )
                }
            }
        }
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
                                loggingReason = "Need to authenticate locked SIM card.",
                            )
                        }
                        unlockStatus.isUnlocked &&
                            deviceEntryInteractor.canSwipeToEnter.value == false -> {
                            switchToScene(
                                // TODO(b/336581871): add sceneState?
                                targetSceneKey = Scenes.Gone,
                                loggingReason =
                                    "All SIM cards unlocked and device already unlocked and " +
                                        "lockscreen doesn't require a swipe to dismiss.",
                            )
                        }
                        else -> {
                            switchToScene(
                                // TODO(b/336581871): add sceneState?
                                targetSceneKey = Scenes.Lockscreen,
                                loggingReason =
                                    "All SIM cards unlocked and device still locked" +
                                        " or lockscreen still requires a swipe to dismiss.",
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
                                setOf(transitionState.fromContent, transitionState.toContent)
                        }
                    val isOnLockscreen = renderedScenes.contains(Scenes.Lockscreen)
                    val isAlternateBouncerVisible = alternateBouncerInteractor.isVisibleState()
                    val isOnPrimaryBouncer = renderedScenes.contains(Scenes.Bouncer)
                    if (!deviceUnlockStatus.isUnlocked) {
                        return@mapNotNull if (isOnLockscreen || isOnPrimaryBouncer) {
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
                        isOnPrimaryBouncer &&
                            deviceUnlockStatus.deviceUnlockSource == DeviceUnlockSource.TrustAgent
                    ) {
                        uiEventLogger.log(BouncerUiEvent.BOUNCER_DISMISS_EXTENDED_ACCESS)
                    }
                    when {
                        isAlternateBouncerVisible -> {
                            // When the device becomes unlocked when the alternate bouncer is
                            // showing, always hide the alternate bouncer and notify dismiss
                            // succeeded
                            alternateBouncerInteractor.hide()
                            dismissCallbackRegistry.notifyDismissSucceeded()

                            // ... and go to Gone or stay on the current scene
                            if (
                                isOnLockscreen ||
                                    !statusBarStateController.leaveOpenOnKeyguardHide()
                            ) {
                                Scenes.Gone to
                                    "device was unlocked with alternate bouncer showing" +
                                        " and shade didn't need to be left open"
                            } else {
                                null
                            }
                        }
                        isOnPrimaryBouncer -> {
                            // When the device becomes unlocked in primary Bouncer,
                            // notify dismiss succeeded and go to previous scene or Gone.
                            dismissCallbackRegistry.notifyDismissSucceeded()
                            if (
                                previousScene.value == Scenes.Lockscreen ||
                                    !statusBarStateController.leaveOpenOnKeyguardHide()
                            ) {
                                Scenes.Gone to
                                    "device was unlocked with bouncer showing and shade" +
                                        " didn't need to be left open"
                            } else {
                                val prevScene = previousScene.value
                                val targetScene = prevScene ?: Scenes.Gone
                                if (targetScene != Scenes.Gone) {
                                    sceneBackInteractor.updateBackStack { stack ->
                                        val list = stack.asIterable().toMutableList()
                                        check(list.last() == Scenes.Lockscreen) {
                                            "The bottommost/last SceneKey of the back stack isn't" +
                                                " the Lockscreen scene like expected. The back" +
                                                " stack is $stack."
                                        }
                                        list[list.size - 1] = Scenes.Gone
                                        sceneStackOf(*list.toTypedArray())
                                    }
                                }
                                targetScene to
                                    "device was unlocked with primary bouncer showing," +
                                        " from sceneKey=$prevScene"
                            }
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
                    switchToScene(targetSceneKey = targetSceneKey, loggingReason = loggingReason)
                }
        }
    }

    private fun handlePowerState() {
        applicationScope.launch {
            powerInteractor.detailedWakefulness.collect { wakefulness ->
                // Detect a double-tap-power-button gesture that was started while the device was
                // still awake.
                if (wakefulness.isAsleep()) return@collect
                if (!wakefulness.powerButtonLaunchGestureTriggered) return@collect
                if (wakefulness.lastSleepReason != WakeSleepReason.POWER_BUTTON) return@collect

                // If we're mid-transition from Gone to Lockscreen due to the first power button
                // press, then return to Gone.
                val transition: ObservableTransitionState.Transition =
                    sceneInteractor.transitionState.value as? ObservableTransitionState.Transition
                        ?: return@collect
                if (
                    transition.fromContent == Scenes.Gone &&
                        transition.toContent == Scenes.Lockscreen
                ) {
                    switchToScene(
                        targetSceneKey = Scenes.Gone,
                        loggingReason = "double-tap power gesture",
                    )
                }
            }
        }
        applicationScope.launch {
            powerInteractor.isAsleep.collect { isAsleep ->
                if (isAsleep) {
                    alternateBouncerInteractor.hide()
                    dismissCallbackRegistry.notifyDismissCancelled()

                    switchToScene(
                        targetSceneKey = Scenes.Lockscreen,
                        loggingReason = "device is starting to sleep",
                        sceneState = keyguardInteractor.asleepKeyguardState.value,
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

    private fun handleDeviceEntryHapticsWhileDeviceLocked() {
        applicationScope.launch {
            deviceEntryInteractor.isDeviceEntered.collectLatest { isDeviceEntered ->
                // Only check for haptics signals before device is entered
                if (!isDeviceEntered) {
                    coroutineScope {
                        launch {
                            deviceEntryHapticsInteractor.playSuccessHaptic
                                .sample(sceneInteractor.currentScene)
                                .collect { currentScene ->
                                    if (Flags.msdlFeedback()) {
                                        msdlPlayer.playToken(
                                            MSDLToken.UNLOCK,
                                            authInteractionProperties,
                                        )
                                    } else {
                                        vibratorHelper.vibrateAuthSuccess(
                                            "$TAG, $currentScene device-entry::success"
                                        )
                                    }
                                }
                        }

                        launch {
                            deviceEntryHapticsInteractor.playErrorHaptic
                                .sample(sceneInteractor.currentScene)
                                .collect { currentScene ->
                                    if (Flags.msdlFeedback()) {
                                        msdlPlayer.playToken(
                                            MSDLToken.FAILURE,
                                            authInteractionProperties,
                                        )
                                    } else {
                                        vibratorHelper.vibrateAuthError(
                                            "$TAG, $currentScene device-entry::error"
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    /** Keeps [SysUiState] up-to-date */
    private fun hydrateSystemUiState() {
        applicationScope.launch {
            combine(
                    sceneInteractor.transitionState
                        .mapNotNull { it as? ObservableTransitionState.Idle }
                        .distinctUntilChanged(),
                    occlusionInteractor.invisibleDueToOcclusion,
                ) { idleState, invisibleDueToOcclusion ->
                    SceneContainerPlugin.SceneContainerPluginState(
                        scene = idleState.currentScene,
                        overlays = idleState.currentOverlays,
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
                            .map { it.currentScene to it.currentOverlays }
                            .distinctUntilChanged()
                            .map { (sceneKey, currentOverlays) ->
                                when {
                                    // When locked, showing the lockscreen scene should be reported
                                    // as "interacting" while showing other scenes should report as
                                    // "not interacting".
                                    //
                                    // This is done here in order to match the legacy
                                    // implementation. The real reason why is lost to lore and myth.
                                    Overlays.NotificationsShade in currentOverlays -> false
                                    Overlays.QuickSettingsShade in currentOverlays -> null
                                    sceneKey == Scenes.Lockscreen -> true
                                    sceneKey == Scenes.Bouncer -> false
                                    sceneKey == Scenes.Shade -> false
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
                    transition.fromContent == Scenes.Bouncer &&
                        transition.toContent == Scenes.Lockscreen &&
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

    private fun handleKeyguardEnabledness() {
        // Automatically switches scenes when keyguard is enabled or disabled, as needed.
        applicationScope.launch {
            keyguardEnabledInteractor.isKeyguardEnabled
                .sample(
                    combine(
                        deviceUnlockedInteractor.isInLockdown,
                        deviceEntryInteractor.isDeviceEntered,
                        ::Pair,
                    )
                ) { isKeyguardEnabled, (isInLockdown, isDeviceEntered) ->
                    when {
                        !isKeyguardEnabled && !isInLockdown && !isDeviceEntered -> {
                            keyguardEnabledInteractor.setShowKeyguardWhenReenabled(true)
                            Scenes.Gone to "Keyguard became disabled"
                        }
                        isKeyguardEnabled &&
                            keyguardEnabledInteractor.isShowKeyguardWhenReenabled() -> {
                            keyguardEnabledInteractor.setShowKeyguardWhenReenabled(false)
                            Scenes.Lockscreen to "Keyguard became enabled"
                        }
                        else -> null
                    }
                }
                .filterNotNull()
                .collect { (targetScene, loggingReason) ->
                    switchToScene(targetScene, loggingReason)
                }
        }

        // Clears the showKeyguardWhenReenabled if the auth method changes to an insecure one.
        applicationScope.launch {
            authenticationInteractor
                .get()
                .authenticationMethod
                .map { it.isSecure }
                .distinctUntilChanged()
                .collect { isAuthenticationMethodSecure ->
                    if (!isAuthenticationMethodSecure) {
                        keyguardEnabledInteractor.setShowKeyguardWhenReenabled(false)
                    }
                }
        }
    }

    private fun switchToScene(
        targetSceneKey: SceneKey,
        loggingReason: String,
        sceneState: Any? = null,
    ) {
        sceneInteractor.changeScene(
            toScene = targetSceneKey,
            loggingReason = loggingReason,
            sceneState = sceneState,
        )
    }

    private fun hydrateBackStack() {
        applicationScope.launch {
            sceneInteractor.currentScene.pairwise().collect { (from, to) ->
                sceneBackInteractor.onSceneChange(from = from, to = to)
            }
        }
    }

    private fun notifyKeyguardDismissCancelledCallbacks() {
        applicationScope.launch {
            combine(deviceEntryInteractor.isUnlocked, sceneInteractor.currentScene.pairwise()) {
                    isUnlocked,
                    (from, to) ->
                    when {
                        from != Scenes.Bouncer -> false
                        to != Scenes.Gone && !isUnlocked -> true
                        else -> false
                    }
                }
                .collect { notifyKeyguardDismissCancelled ->
                    if (notifyKeyguardDismissCancelled) {
                        dismissCallbackRegistry.notifyDismissCancelled()
                    }
                }
        }
    }

    /**
     * Keeps the value of [DeviceEntryInteractor.isLockscreenEnabled] fresh.
     *
     * This is needed because that value is sourced from a non-observable data source
     * (`LockPatternUtils`, which doesn't expose a listener or callback for this value). Therefore,
     * every time a transition to the `Lockscreen` scene is started, the value is re-fetched and
     * cached.
     */
    private fun refreshLockscreenEnabled() {
        applicationScope.launch {
            sceneInteractor.transitionState
                .map { it.isTransitioning(to = Scenes.Lockscreen) }
                .distinctUntilChanged()
                .filter { it }
                .collectLatest { deviceEntryInteractor.refreshLockscreenEnabled() }
        }
    }

    companion object {
        private const val TAG = "SceneContainerStartable"
    }
}
