/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.data.repository.DeviceProvisioningRepository
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** Business logic for shade interactions. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    deviceProvisioningRepository: DeviceProvisioningRepository,
    disableFlagsRepository: DisableFlagsRepository,
    dozeParams: DozeParameters,
    sceneContainerFlags: SceneContainerFlags,
    // TODO(b/300258424) convert to direct reference instead of provider
    sceneInteractorProvider: Provider<SceneInteractor>,
    keyguardRepository: KeyguardRepository,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    userSetupRepository: UserSetupRepository,
    userSwitcherInteractor: UserSwitcherInteractor,
    sharedNotificationContainerInteractor: SharedNotificationContainerInteractor,
    private val repository: ShadeRepository,
) {
    /** Emits true if the shade is currently allowed and false otherwise. */
    val isShadeEnabled: StateFlow<Boolean> =
        disableFlagsRepository.disableFlags
            .map { it.isShadeEnabled() }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    /**
     * Whether split shade, the combined notifications and quick settings shade used for large
     * screens, is enabled.
     */
    val isSplitShadeEnabled: Flow<Boolean> =
        sharedNotificationContainerInteractor.configurationBasedDimensions
            .map { dimens -> dimens.useSplitShade }
            .distinctUntilChanged()

    /** The amount [0-1] that the shade has been opened */
    val shadeExpansion: Flow<Float> =
        if (sceneContainerFlags.isEnabled()) {
            sceneBasedExpansion(sceneInteractorProvider.get(), SceneKey.Shade)
        } else {
            combine(
                    repository.lockscreenShadeExpansion,
                    keyguardRepository.statusBarState,
                    repository.legacyShadeExpansion,
                    repository.qsExpansion,
                    isSplitShadeEnabled
                ) {
                    lockscreenShadeExpansion,
                    statusBarState,
                    legacyShadeExpansion,
                    qsExpansion,
                    splitShadeEnabled ->
                    when (statusBarState) {
                        // legacyShadeExpansion is 1 instead of 0 when QS is expanded
                        StatusBarState.SHADE ->
                            if (!splitShadeEnabled && qsExpansion > 0f) 0f else legacyShadeExpansion
                        StatusBarState.KEYGUARD -> lockscreenShadeExpansion
                        // dragDownAmount, which drives lockscreenShadeExpansion resets to 0f when
                        // the pointer is lifted and the lockscreen shade is fully expanded
                        StatusBarState.SHADE_LOCKED -> 1f
                    }
                }
                .distinctUntilChanged()
        }

    /**
     * The amount [0-1] QS has been opened. Normal shade with notifications (QQS) visible will
     * report 0f. If split shade is enabled, value matches shadeExpansion.
     */
    val qsExpansion: StateFlow<Float> =
        if (sceneContainerFlags.isEnabled()) {
            val qsExp = sceneBasedExpansion(sceneInteractorProvider.get(), SceneKey.QuickSettings)
            combine(isSplitShadeEnabled, shadeExpansion, qsExp) {
                    isSplitShadeEnabled,
                    shadeExp,
                    qsExp ->
                    if (isSplitShadeEnabled) {
                        shadeExp
                    } else {
                        qsExp
                    }
                }
                .stateIn(scope, SharingStarted.Eagerly, 0f)
        } else {
            repository.qsExpansion
        }

    /** Whether Quick Settings is expanded a non-zero amount. */
    val isQsExpanded: StateFlow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            qsExpansion
                .map { it > 0 }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, false)
        } else {
            repository.legacyIsQsExpanded
        }

    /** The amount [0-1] either QS or the shade has been opened. */
    val anyExpansion: StateFlow<Float> =
        combine(shadeExpansion, qsExpansion) { shadeExp, qsExp -> maxOf(shadeExp, qsExp) }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    /** Whether either the shade or QS is fully expanded. */
    val isAnyFullyExpanded: Flow<Boolean> = anyExpansion.map { it >= 1f }.distinctUntilChanged()

    /**
     * Whether either the shade or QS is partially or fully expanded, i.e. not fully collapsed. At
     * this time, this is not simply a matter of checking if either value in shadeExpansion and
     * qsExpansion is greater than zero, because it includes the legacy concept of whether input
     * transfer is about to occur. If the scene container flag is enabled, it just checks whether
     * either expansion value is positive.
     *
     * TODO(b/300258424) remove all but the first sentence of this comment
     */
    val isAnyExpanded: StateFlow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
                anyExpansion.map { it > 0f }.distinctUntilChanged()
            } else {
                repository.legacyExpandedOrAwaitingInputTransfer
            }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether the user is expanding or collapsing the shade with user input. This will be true even
     * if the user's input gesture has ended but a transition they initiated is animating.
     */
    val isUserInteractingWithShade: Flow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            sceneBasedInteracting(sceneInteractorProvider.get(), SceneKey.Shade)
        } else {
            combine(
                userInteractingFlow(
                    repository.legacyShadeTracking,
                    repository.legacyShadeExpansion
                ),
                repository.legacyLockscreenShadeTracking
            ) { legacyShadeTracking, legacyLockscreenShadeTracking ->
                legacyShadeTracking || legacyLockscreenShadeTracking
            }
        }

    /**
     * Whether the user is expanding or collapsing quick settings with user input. This will be true
     * even if the user's input gesture has ended but a transition they initiated is still
     * animating.
     */
    val isUserInteractingWithQs: Flow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            sceneBasedInteracting(sceneInteractorProvider.get(), SceneKey.QuickSettings)
        } else {
            userInteractingFlow(repository.legacyQsTracking, repository.qsExpansion)
        }

    /**
     * Whether the user is expanding or collapsing either the shade or quick settings with user
     * input (i.e. dragging a pointer). This will be true even if the user's input gesture had ended
     * but a transition they initiated is still animating.
     */
    val isUserInteracting: Flow<Boolean> =
        combine(isUserInteractingWithShade, isUserInteractingWithQs) { shade, qs -> shade || qs }
            .distinctUntilChanged()

    /** Are touches allowed on the notification panel? */
    val isShadeTouchable: Flow<Boolean> =
        combine(
            powerInteractor.isAsleep,
            keyguardTransitionInteractor.isInTransitionToStateWhere { it == KeyguardState.AOD },
            keyguardRepository.dozeTransitionModel.map { it.to == DozeStateModel.DOZE_PULSING },
            deviceProvisioningRepository.isFactoryResetProtectionActive,
        ) { isAsleep, goingToSleep, isPulsing, isFrpActive ->
            when {
                // Touches are disabled when Factory Reset Protection is active
                isFrpActive -> false
                // If the device is going to sleep, only accept touches if we're still
                // animating
                goingToSleep -> dozeParams.shouldControlScreenOff()
                // If the device is asleep, only accept touches if there's a pulse
                isAsleep -> isPulsing
                else -> true
            }
        }

    /** Emits true if the shade can be expanded from QQS to QS and false otherwise. */
    val isExpandToQsEnabled: Flow<Boolean> =
        combine(
            disableFlagsRepository.disableFlags,
            isShadeEnabled,
            keyguardRepository.isDozing,
            userSetupRepository.isUserSetupFlow,
            deviceProvisioningRepository.isDeviceProvisioned,
        ) { disableFlags, isShadeEnabled, isDozing, isUserSetup, isDeviceProvisioned ->
            isDeviceProvisioned &&
                // Disallow QS during setup if it's a simple user switcher. (The user intends to
                // use the lock screen user switcher, QS is not needed.)
                (isUserSetup || !userSwitcherInteractor.isSimpleUserSwitcher) &&
                isShadeEnabled &&
                disableFlags.isQuickSettingsEnabled() &&
                !isDozing
        }

    fun sceneBasedExpansion(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor.transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        if (state.scene == sceneKey) {
                            flowOf(1f)
                        } else {
                            flowOf(0f)
                        }
                    is ObservableTransitionState.Transition ->
                        if (state.toScene == sceneKey) {
                            state.progress
                        } else if (state.fromScene == sceneKey) {
                            state.progress.map { progress -> 1 - progress }
                        } else {
                            flowOf(0f)
                        }
                }
            }
            .distinctUntilChanged()

    fun sceneBasedInteracting(sceneInteractor: SceneInteractor, sceneKey: SceneKey) =
        sceneInteractor.transitionState
            .map { state ->
                when (state) {
                    is ObservableTransitionState.Idle -> false
                    is ObservableTransitionState.Transition ->
                        state.isInitiatedByUserInput &&
                            (state.toScene == sceneKey || state.fromScene == sceneKey)
                }
            }
            .distinctUntilChanged()

    /**
     * Return a flow for whether a user is interacting with an expandable shade component using
     * tracking and expansion flows. NOTE: expansion must be a `StateFlow` to guarantee that
     * [expansion.first] checks the current value of the flow.
     */
    private fun userInteractingFlow(
        tracking: Flow<Boolean>,
        expansion: StateFlow<Float>
    ): Flow<Boolean> {
        return flow {
            // initial value is false
            emit(false)
            while (currentCoroutineContext().isActive) {
                // wait for tracking to become true
                tracking.first { it }
                emit(true)
                // wait for tracking to become false
                tracking.first { !it }
                // wait for expansion to complete in either direction
                expansion.first { it <= 0f || it >= 1f }
                // interaction complete
                emit(false)
            }
        }
    }
}
