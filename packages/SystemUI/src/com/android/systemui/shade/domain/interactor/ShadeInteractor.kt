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
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.domain.interactor.UserInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Business logic for shade interactions. */
@SysUISingleton
class ShadeInteractor
@Inject
constructor(
    disableFlagsRepository: DisableFlagsRepository,
    keyguardRepository: KeyguardRepository,
    userSetupRepository: UserSetupRepository,
    deviceProvisionedController: DeviceProvisionedController,
    userInteractor: UserInteractor,
) {
    /** Emits true if the shade can be expanded from QQS to QS and false otherwise. */
    val isExpandToQsEnabled: Flow<Boolean> =
        combine(
            disableFlagsRepository.disableFlags,
            keyguardRepository.isDozing,
            userSetupRepository.isUserSetupFlow,
        ) { disableFlags, isDozing, isUserSetup ->
            deviceProvisionedController.isDeviceProvisioned &&
                // Disallow QS during setup if it's a simple user switcher. (The user intends to
                // use the lock screen user switcher, QS is not needed.)
                (isUserSetup || !userInteractor.isSimpleUserSwitcher) &&
                disableFlags.isShadeEnabled() &&
                disableFlags.isQuickSettingsEnabled() &&
                !isDozing
        }
}
