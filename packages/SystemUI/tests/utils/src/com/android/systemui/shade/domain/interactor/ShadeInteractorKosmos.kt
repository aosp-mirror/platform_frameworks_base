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
 * limitations under the License.
 */

package com.android.systemui.shade.domain.interactor

import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.sceneContainerFlags
import com.android.systemui.shade.ShadeModule
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.statusbar.disableflags.data.repository.disableFlagsRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.policy.data.repository.userSetupRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.user.domain.interactor.userSwitcherInteractor

var Kosmos.baseShadeInteractor: BaseShadeInteractor by
    Kosmos.Fixture {
        ShadeModule.provideBaseShadeInteractor(
            sceneContainerFlags = sceneContainerFlags,
            sceneContainerOn = { shadeInteractorSceneContainerImpl },
            sceneContainerOff = { shadeInteractorLegacyImpl },
        )
    }
val Kosmos.shadeInteractorSceneContainerImpl by
    Kosmos.Fixture {
        ShadeInteractorSceneContainerImpl(
            scope = applicationCoroutineScope,
            sceneInteractor = sceneInteractor,
            sharedNotificationContainerInteractor = sharedNotificationContainerInteractor,
        )
    }
val Kosmos.shadeInteractorLegacyImpl by
    Kosmos.Fixture {
        ShadeInteractorLegacyImpl(
            scope = applicationCoroutineScope,
            keyguardRepository = keyguardRepository,
            sharedNotificationContainerInteractor = sharedNotificationContainerInteractor,
            repository = shadeRepository
        )
    }
var Kosmos.shadeInteractor: ShadeInteractor by Kosmos.Fixture { shadeInteractorImpl }
val Kosmos.shadeInteractorImpl by
    Kosmos.Fixture {
        ShadeInteractorImpl(
            scope = applicationCoroutineScope,
            deviceProvisioningInteractor = deviceProvisioningInteractor,
            disableFlagsRepository = disableFlagsRepository,
            dozeParams = dozeParameters,
            keyguardRepository = fakeKeyguardRepository,
            keyguardTransitionInteractor = keyguardTransitionInteractor,
            powerInteractor = powerInteractor,
            userSetupRepository = userSetupRepository,
            userSwitcherInteractor = userSwitcherInteractor,
            baseShadeInteractor = baseShadeInteractor,
        )
    }
