/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard.dagger

import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToOccludedTransitionViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Base module that defines the [PrimaryBouncerTransition] multibinding. All variants of SystemUI
 * can install this module to get the default empty version of the multibinding
 */
@Module
interface PrimaryBouncerTransitionModule {
    @Multibinds fun primaryBouncerTransitions(): Set<PrimaryBouncerTransition>
}

/**
 * Module that installs all the implementations of [PrimaryBouncerTransition] from different
 * keyguard states to and away from the primary bouncer.
 */
@Module
interface PrimaryBouncerTransitionImplModule {
    @Binds
    @IntoSet
    fun fromAod(impl: AodToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromAlternateBouncer(
        impl: AlternateBouncerToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromDozing(impl: DozingToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromLockscreen(
        impl: LockscreenToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromGlanceableHub(
        impl: GlanceableHubToPrimaryBouncerTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun fromOccluded(impl: OccludedToPrimaryBouncerTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toAod(impl: PrimaryBouncerToAodTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toLockscreen(impl: PrimaryBouncerToLockscreenTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toDozing(impl: PrimaryBouncerToDozingTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toGlanceableHub(
        impl: PrimaryBouncerToGlanceableHubTransitionViewModel
    ): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toGone(impl: PrimaryBouncerToGoneTransitionViewModel): PrimaryBouncerTransition

    @Binds
    @IntoSet
    fun toOccluded(impl: PrimaryBouncerToOccludedTransitionViewModel): PrimaryBouncerTransition
}
