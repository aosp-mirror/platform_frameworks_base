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
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.kotlin.pairwise
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
import kotlinx.coroutines.flow.isActive
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
    disableFlagsRepository: DisableFlagsRepository,
    sceneContainerFlags: SceneContainerFlags,
    // TODO(b/300258424) convert to direct reference instead of provider
    sceneInteractorProvider: Provider<SceneInteractor>,
    keyguardRepository: KeyguardRepository,
    userSetupRepository: UserSetupRepository,
    deviceProvisionedController: DeviceProvisionedController,
    userInteractor: UserInteractor,
    sharedNotificationContainerInteractor: SharedNotificationContainerInteractor,
    repository: ShadeRepository,
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
    val splitShadeEnabled: Flow<Boolean> =
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
                    splitShadeEnabled
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
     * report 0f.
     */
    val qsExpansion: StateFlow<Float> =
        if (sceneContainerFlags.isEnabled()) {
            sceneBasedExpansion(sceneInteractorProvider.get(), SceneKey.QuickSettings)
                .stateIn(scope, SharingStarted.Eagerly, 0f)
        } else {
            repository.qsExpansion
        }

    /** The amount [0-1] either QS or the shade has been opened. */
    val anyExpansion: StateFlow<Float> =
        combine(shadeExpansion, qsExpansion) { shadeExp, qsExp -> maxOf(shadeExp, qsExp) }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    /** Whether either the shade or QS is expanding from a fully collapsed state. */
    val isAnyExpanding =
        anyExpansion
            .pairwise(1f)
            .map { (prev, curr) -> curr > 0f && curr < 1f && prev < 1f }
            .distinctUntilChanged()

    /**
     * Whether the user is expanding or collapsing the shade with user input. This will be true even
     * if the user's input gesture has ended but a transition they initiated is animating.
     */
    val isUserInteractingWithShade: Flow<Boolean> =
        if (sceneContainerFlags.isEnabled()) {
            sceneBasedInteracting(sceneInteractorProvider.get(), SceneKey.Shade)
        } else {
            userInteractingFlow(repository.legacyShadeTracking, repository.legacyShadeExpansion)
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
        combine(isUserInteractingWithShade, isUserInteractingWithShade) { shade, qs -> shade || qs }
            .distinctUntilChanged()

    /** Emits true if the shade can be expanded from QQS to QS and false otherwise. */
    val isExpandToQsEnabled: Flow<Boolean> =
        combine(
            disableFlagsRepository.disableFlags,
            isShadeEnabled,
            keyguardRepository.isDozing,
            userSetupRepository.isUserSetupFlow,
        ) { disableFlags, isShadeEnabled, isDozing, isUserSetup ->
            deviceProvisionedController.isDeviceProvisioned &&
                // Disallow QS during setup if it's a simple user switcher. (The user intends to
                // use the lock screen user switcher, QS is not needed.)
                (isUserSetup || !userInteractor.isSimpleUserSwitcher) &&
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
                        state.isUserInputDriven &&
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
