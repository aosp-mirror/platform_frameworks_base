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
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorEmptyImpl
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
    abstract fun bindsShadeController(sc: ShadeControllerEmptyImpl): ShadeController

    @Binds
    @SysUISingleton
    abstract fun bindsShadeInteractor(si: ShadeInteractorEmptyImpl): ShadeInteractor
}
