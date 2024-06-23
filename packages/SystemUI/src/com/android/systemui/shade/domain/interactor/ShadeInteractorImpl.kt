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
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import com.android.systemui.util.kotlin.combine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The non-empty SceneInteractor implementation. */
@SysUISingleton
class ShadeInteractorImpl
@Inject
constructor(
    @Application val scope: CoroutineScope,
    deviceProvisioningInteractor: DeviceProvisioningInteractor,
    disableFlagsRepository: DisableFlagsRepository,
    dozeParams: DozeParameters,
    keyguardRepository: KeyguardRepository,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    userSetupRepository: UserSetupRepository,
    userSwitcherInteractor: UserSwitcherInteractor,
    private val baseShadeInteractor: BaseShadeInteractor,
) : ShadeInteractor, BaseShadeInteractor by baseShadeInteractor {
    override val isShadeEnabled: StateFlow<Boolean> =
        combine(
                deviceProvisioningInteractor.isFactoryResetProtectionActive,
                disableFlagsRepository.disableFlags,
            ) { isFrpActive, isDisabledByFlags ->
                isDisabledByFlags.isShadeEnabled() && !isFrpActive
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isAnyFullyExpanded: StateFlow<Boolean> =
        anyExpansion
            .map { it >= 1f }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isShadeFullyExpanded: Flow<Boolean> =
        baseShadeInteractor.shadeExpansion.map { it >= 1f }.distinctUntilChanged()

    override val isUserInteracting: StateFlow<Boolean> =
        combine(isUserInteractingWithShade, isUserInteractingWithQs) { shade, qs -> shade || qs }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val isShadeTouchable: Flow<Boolean> =
        combine(
            powerInteractor.isAsleep,
            keyguardTransitionInteractor.isInTransitionToStateWhere { it == KeyguardState.AOD },
            keyguardRepository.dozeTransitionModel.map { it.to == DozeStateModel.DOZE_PULSING },
            deviceProvisioningInteractor.isFactoryResetProtectionActive,
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

    override val isExpandToQsEnabled: Flow<Boolean> =
        combine(
            disableFlagsRepository.disableFlags,
            isShadeEnabled,
            keyguardRepository.isDozing,
            userSetupRepository.isUserSetUp,
            deviceProvisioningInteractor.isDeviceProvisioned,
        ) { disableFlags, isShadeEnabled, isDozing, isUserSetup, isDeviceProvisioned ->
            isDeviceProvisioned &&
                // Disallow QS during setup if it's a simple user switcher. (The user intends to
                // use the lock screen user switcher, QS is not needed.)
                (isUserSetup || !userSwitcherInteractor.isSimpleUserSwitcher) &&
                isShadeEnabled &&
                disableFlags.isQuickSettingsEnabled() &&
                !isDozing
        }
}
