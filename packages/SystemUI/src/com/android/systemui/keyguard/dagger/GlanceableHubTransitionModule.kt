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

import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.keyguard.ui.viewmodel.DozingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToGlanceableHubTransitionViewModel
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Module(subcomponents = [GlanceableHubBlurComponent::class])
interface GlanceableHubTransitionModule {
    @Multibinds fun glanceableHubTransition(): Set<GlanceableHubTransition>
}

@ExperimentalCoroutinesApi
@Module
interface GlanceableHubTransitionImplModule {
    @Binds
    @IntoSet
    fun fromLockscreen(impl: LockscreenToGlanceableHubTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun toLockScreen(impl: GlanceableHubToLockscreenTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun fromOccluded(impl: OccludedToGlanceableHubTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun toOccluded(impl: GlanceableHubToOccludedTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun fromDream(impl: DreamingToGlanceableHubTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun toDream(impl: GlanceableHubToDreamingTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun fromDozing(impl: DozingToGlanceableHubTransitionViewModel): GlanceableHubTransition

    @Binds
    @IntoSet
    fun toDozing(impl: GlanceableHubToDozingTransitionViewModel): GlanceableHubTransition
}
