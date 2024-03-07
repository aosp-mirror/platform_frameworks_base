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
package com.android.systemui.keyguard.ui.transitions

import com.android.systemui.keyguard.ui.viewmodel.AodToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@Module
abstract class DeviceEntryIconTransitionModule {
    @Binds
    @IntoSet
    abstract fun aodToLockscreen(
        impl: AodToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun aodToGone(impl: AodToGoneTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dozingToLockscreen(
        impl: DozingToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dreamingToLockscreen(
        impl: DreamingToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun lockscreenToAod(
        impl: LockscreenToAodTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun lockscreenToDreaming(
        impl: LockscreenToDreamingTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun lockscreenToOccluded(
        impl: LockscreenToOccludedTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun lockscreenToPrimaryBouncer(
        impl: LockscreenToPrimaryBouncerTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun lockscreenToGone(
        impl: LockscreenToGoneTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun goneToAod(impl: GoneToAodTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun occludedToAod(impl: OccludedToAodTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun occludedToLockscreen(
        impl: OccludedToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun primaryBouncerToAod(
        impl: PrimaryBouncerToAodTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun primaryBouncerToLockscreen(
        impl: PrimaryBouncerToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition
}
