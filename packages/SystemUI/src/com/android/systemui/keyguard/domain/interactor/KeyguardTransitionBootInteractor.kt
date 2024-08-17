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

package com.android.systemui.keyguard.domain.interactor

import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OFF
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Handles initialization of the KeyguardTransitionRepository on boot. */
@SysUISingleton
class KeyguardTransitionBootInteractor
@Inject
constructor(
    @Application val scope: CoroutineScope,
    val deviceEntryInteractor: DeviceEntryInteractor,
    val deviceProvisioningInteractor: DeviceProvisioningInteractor,
    val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    val internalTransitionInteractor: InternalKeyguardTransitionInteractor,
    val repository: KeyguardTransitionRepository,
) : CoreStartable {

    /**
     * Whether the lockscreen should be showing when the device starts up for the first time. If not
     * then we'll seed the repository with a transition from OFF -> GONE.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val showLockscreenOnBoot: Flow<Boolean> by lazy {
        deviceProvisioningInteractor.isDeviceProvisioned.map { provisioned ->
            (provisioned || deviceEntryInteractor.isAuthenticationRequired()) &&
                deviceEntryInteractor.isLockscreenEnabled()
        }
    }

    override fun start() {
        scope.launch {
            if (internalTransitionInteractor.currentTransitionInfoInternal.value.from != OFF) {
                Log.e(
                    "KeyguardTransitionInteractor",
                    "showLockscreenOnBoot emitted, but we've already " +
                        "transitioned to a state other than OFF. We'll respect that " +
                        "transition, but this should not happen."
                )
            } else {
                if (SceneContainerFlag.isEnabled) {
                    // TODO(b/360372242): Some part of the transition implemented for flag off is
                    //  missing here. There are two things achieved with this:
                    //  1. Keyguard is hidden when the setup wizard is shown. This part is already
                    //     implemented in scene container by disabling visibility instead of going
                    //     to Gone. See [SceneContainerStartable.hydrateVisibility]. We might want
                    //     to unify this logic here.
                    //  2. When the auth method is set to NONE device boots into Gone (Launcher).
                    //     For this we would just need to call changeScene(Scene.Gone).
                    //     Unfortunately STL doesn't seem to be initialized at this point, therefore
                    //     it needs a different solution.
                    repository.emitInitialStepsFromOff(LOCKSCREEN)
                } else {
                    repository.emitInitialStepsFromOff(
                        if (showLockscreenOnBoot.first()) LOCKSCREEN else GONE
                    )
                }
            }
        }
    }
}
