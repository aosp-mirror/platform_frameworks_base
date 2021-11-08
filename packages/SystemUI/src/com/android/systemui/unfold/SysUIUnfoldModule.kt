/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.unfold

import com.android.keyguard.KeyguardUnfoldTransition
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.StatusBarMoveFromCenterAnimationController
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import java.util.Optional
import javax.inject.Named
import javax.inject.Scope

@Scope
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class SysUIUnfoldScope

/**
 * Creates an injectable [SysUIUnfoldComponent] that provides objects that have been scoped with
 * [@SysUIUnfoldScope]. Since [SysUIUnfoldComponent] depends upon:
 * * [Optional<UnfoldTransitionProgressProvider>]
 * * [Optional<ScopedUnfoldTransitionProgressProvider>]
 * no objects will get constructed if these parameters are empty.
 */
@Module(subcomponents = [SysUIUnfoldComponent::class])
object SysUIUnfoldModule {
    @Provides
    @SysUISingleton
    fun provideSysUIUnfoldComponent(
        provider: Optional<UnfoldTransitionProgressProvider>,
        @Named(UNFOLD_STATUS_BAR) scopedProvider: Optional<ScopedUnfoldTransitionProgressProvider>,
        factory: SysUIUnfoldComponent.Factory
    ) =
        provider.flatMap {
            p -> scopedProvider.map { sp -> factory.create(p, sp) }
        }
}

@SysUIUnfoldScope
@Subcomponent
interface SysUIUnfoldComponent {

    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance provider: UnfoldTransitionProgressProvider,
            @BindsInstance scopedProvider: ScopedUnfoldTransitionProgressProvider
        ): SysUIUnfoldComponent
    }

    fun getKeyguardUnfoldTransition(): KeyguardUnfoldTransition

    fun getStatusBarMoveFromCenterAnimationController(): StatusBarMoveFromCenterAnimationController

    fun getUnfoldTransitionWallpaperController(): UnfoldTransitionWallpaperController

    fun getUnfoldLightRevealOverlayAnimation(): UnfoldLightRevealOverlayAnimation
}
