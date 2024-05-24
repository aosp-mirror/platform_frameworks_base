/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet

@Module
abstract class StartKeyguardTransitionModule {

    @Binds
    @IntoMap
    @ClassKey(KeyguardTransitionCoreStartable::class)
    abstract fun bind(impl: KeyguardTransitionCoreStartable): CoreStartable

    @Binds
    @IntoSet
    abstract fun fromPrimaryBouncer(
        impl: FromPrimaryBouncerTransitionInteractor
    ): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromLockscreen(impl: FromLockscreenTransitionInteractor): TransitionInteractor

    @Binds @IntoSet abstract fun fromAod(impl: FromAodTransitionInteractor): TransitionInteractor

    @Binds @IntoSet abstract fun fromGone(impl: FromGoneTransitionInteractor): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromDreaming(impl: FromDreamingTransitionInteractor): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromDreamingLockscreenHosted(
        impl: FromDreamingLockscreenHostedTransitionInteractor
    ): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromOccluded(impl: FromOccludedTransitionInteractor): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromDozing(impl: FromDozingTransitionInteractor): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromAlternateBouncer(
        impl: FromAlternateBouncerTransitionInteractor
    ): TransitionInteractor

    @Binds
    @IntoSet
    abstract fun fromGlanceableHub(
        impl: FromGlanceableHubTransitionInteractor
    ): TransitionInteractor
}
