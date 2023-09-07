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
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Business logic for shade interactions. */
@SysUISingleton
class ShadeInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    disableFlagsRepository: DisableFlagsRepository,
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
        combine(
            repository.lockscreenShadeExpansion,
            keyguardRepository.statusBarState,
            repository.legacyShadeExpansion,
            repository.qsExpansion,
            splitShadeEnabled
        ) { dragDownAmount, statusBarState, legacyShadeExpansion, qsExpansion, splitShadeEnabled ->
            when (statusBarState) {
                // legacyShadeExpansion is 1 instead of 0 when QS is expanded
                StatusBarState.SHADE ->
                    if (!splitShadeEnabled && qsExpansion > 0f) 0f else legacyShadeExpansion
                StatusBarState.KEYGUARD -> dragDownAmount
                // This is required, as shadeExpansion gets reset to 0f even with the shade open
                StatusBarState.SHADE_LOCKED -> 1f
            }
        }

    /**
     * The amount [0-1] QS has been opened. Normal shade with notifications (QQS) visible will
     * report 0f.
     */
    val qsExpansion: StateFlow<Float> = repository.qsExpansion

    /** The amount [0-1] either QS or the shade has been opened */
    val anyExpansion: StateFlow<Float> =
        combine(shadeExpansion, qsExpansion) { shadeExp, qsExp -> maxOf(shadeExp, qsExp) }
            .stateIn(scope, SharingStarted.Eagerly, 0f)

    /** Whether either the shade or QS is expanding from a fully collapsed state. */
    val anyExpanding =
        anyExpansion
            .pairwise(1f)
            .map { (prev, curr) -> curr > 0f && curr < 1f && prev < 1f }
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
}
