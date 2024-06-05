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

import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OffToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToDozingTransitionViewModel
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
    abstract fun alternateBouncerToAod(
        impl: AlternateBouncerToAodTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun alternateBouncerToDozing(
        impl: AlternateBouncerToDozingTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun alternateBouncerToGone(
        impl: AlternateBouncerToGoneTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun alternateBouncerToOccluded(
        impl: AlternateBouncerToOccludedTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun alternateBouncerToPrimaryBouncer(
        impl: AlternateBouncerToPrimaryBouncerTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun aodToGone(impl: AodToGoneTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun aodToLockscreen(
        impl: AodToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun aodToOccluded(impl: AodToOccludedTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun aodToPrimaryBouncer(
        impl: AodToPrimaryBouncerTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dozingToGone(impl: DozingToGoneTransitionViewModel): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dozingToLockscreen(
        impl: DozingToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dozingToOccluded(
        impl: DozingToOccludedTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dozingToPrimaryBouncer(
        impl: DozingToPrimaryBouncerTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dreamingToAod(impl: DreamingToAodTransitionViewModel): DeviceEntryIconTransition

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
    abstract fun lockscreenToDozing(
        impl: LockscreenToDozingTransitionViewModel
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
    abstract fun goneToLockscreen(
        impl: GoneToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun goneToDozing(impl: GoneToDozingTransitionViewModel): DeviceEntryIconTransition

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
    abstract fun offToLockscreen(
        impl: OffToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun primaryBouncerToAod(
        impl: PrimaryBouncerToAodTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun primaryBouncerToDozing(
        impl: PrimaryBouncerToDozingTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun primaryBouncerToLockscreen(
        impl: PrimaryBouncerToLockscreenTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun dreamingToGlanceableHub(
        impl: DreamingToGlanceableHubTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun glanceableHubToDreaming(
        impl: GlanceableHubToDreamingTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun glanceableHubToOccluded(
        impl: GlanceableHubToOccludedTransitionViewModel
    ): DeviceEntryIconTransition

    @Binds
    @IntoSet
    abstract fun occludedToGlanceableHub(
        impl: OccludedToGlanceableHubTransitionViewModel
    ): DeviceEntryIconTransition
}
