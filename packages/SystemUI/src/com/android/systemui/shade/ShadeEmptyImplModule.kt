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

package com.android.systemui.shade

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.data.repository.PrivacyChipRepository
import com.android.systemui.shade.data.repository.PrivacyChipRepositoryImpl
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepositoryImpl
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorEmptyImpl
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorEmptyImpl
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractorEmptyImpl
import dagger.Binds
import dagger.Module

/** Fulfills dependencies on the shade with empty implementations for variants with no shade. */
@Module
abstract class ShadeEmptyImplModule {
    @Binds
    @SysUISingleton
    abstract fun bindsShadeViewController(svc: ShadeViewControllerEmptyImpl): ShadeViewController

    @Binds
    @SysUISingleton
    abstract fun bindsShadeBack(sbai: ShadeViewControllerEmptyImpl): ShadeBackActionInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsShadeLockscreenInteractor(
        slsi: ShadeViewControllerEmptyImpl
    ): ShadeLockscreenInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsShadeController(sc: ShadeControllerEmptyImpl): ShadeController

    @Binds
    @SysUISingleton
    abstract fun bindsShadeInteractor(si: ShadeInteractorEmptyImpl): ShadeInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsShadeRepository(impl: ShadeRepositoryImpl): ShadeRepository

    @Binds
    @SysUISingleton
    abstract fun bindsShadeAnimationInteractor(
        sai: ShadeAnimationInteractorEmptyImpl
    ): ShadeAnimationInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsPanelExpansionInteractor(
        sbai: ShadeViewControllerEmptyImpl
    ): PanelExpansionInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsPrivacyChipRepository(impl: PrivacyChipRepositoryImpl): PrivacyChipRepository

    @Binds
    @SysUISingleton
    abstract fun bindShadeModeInteractor(impl: ShadeModeInteractorEmptyImpl): ShadeModeInteractor
}
