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

package com.android.systemui.deviceentry

import com.android.keyguard.EmptyLockIconViewController
import com.android.keyguard.LegacyLockIconViewController
import com.android.keyguard.LockIconViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepositoryModule
import com.android.systemui.deviceentry.data.repository.FaceWakeUpTriggersConfigModule
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds

@Module(
    includes =
        [
            DeviceEntryRepositoryModule::class,
            FaceWakeUpTriggersConfigModule::class,
        ],
)
abstract class DeviceEntryModule {
    /**
     * A set of DeviceEntryIconTransitions. Ensures that this can be injected even if it's empty.
     */
    @Multibinds abstract fun deviceEntryIconTransitionSet(): Set<DeviceEntryIconTransition>

    companion object {
        @Provides
        @SysUISingleton
        fun provideLockIconViewController(
            legacyLockIconViewController: Lazy<LegacyLockIconViewController>,
            emptyLockIconViewController: Lazy<EmptyLockIconViewController>,
        ): LockIconViewController {
            return if (DeviceEntryUdfpsRefactor.isEnabled) {
                emptyLockIconViewController.get()
            } else {
                legacyLockIconViewController.get()
            }
        }
    }
}
