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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.NotificationPanelUnfoldAnimationController
import com.android.systemui.statusbar.phone.StatusBarMoveFromCenterAnimationController
import com.android.systemui.unfold.util.NaturalRotationUnfoldProgressProvider
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.util.kotlin.getOrNull
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
 * [@SysUIUnfoldScope].
 *
 * Since [SysUIUnfoldComponent] depends upon:
 * * [Optional<UnfoldTransitionProgressProvider>]
 * * [Optional<ScopedUnfoldTransitionProgressProvider>]
 * * [Optional<NaturalRotationProgressProvider>]
 *
 * no objects will get constructed if these parameters are empty.
 */
@Module(subcomponents = [SysUIUnfoldComponent::class])
class SysUIUnfoldModule {

    @Provides
    @SysUISingleton
    fun provideSysUIUnfoldComponent(
        provider: Optional<UnfoldTransitionProgressProvider>,
        rotationProvider: Optional<NaturalRotationUnfoldProgressProvider>,
        @Named(UNFOLD_STATUS_BAR) scopedProvider: Optional<ScopedUnfoldTransitionProgressProvider>,
        factory: SysUIUnfoldComponent.Factory
    ): Optional<SysUIUnfoldComponent> {
        val p1 = provider.getOrNull()
        val p2 = rotationProvider.getOrNull()
        val p3 = scopedProvider.getOrNull()
        return if (p1 == null || p2 == null || p3 == null) {
            Optional.empty()
        } else {
            Optional.of(factory.create(p1, p2, p3))
        }
    }
}

@SysUIUnfoldScope
@Subcomponent
interface SysUIUnfoldComponent {

    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance p1: UnfoldTransitionProgressProvider,
            @BindsInstance p2: NaturalRotationUnfoldProgressProvider,
            @BindsInstance p3: ScopedUnfoldTransitionProgressProvider
        ): SysUIUnfoldComponent
    }

    fun getKeyguardUnfoldTransition(): KeyguardUnfoldTransition

    fun getStatusBarMoveFromCenterAnimationController(): StatusBarMoveFromCenterAnimationController

    fun getNotificationPanelUnfoldAnimationController(): NotificationPanelUnfoldAnimationController

    fun getFoldAodAnimationController(): FoldAodAnimationController

    fun getUnfoldTransitionWallpaperController(): UnfoldTransitionWallpaperController

    fun getUnfoldLightRevealOverlayAnimation(): UnfoldLightRevealOverlayAnimation
}
