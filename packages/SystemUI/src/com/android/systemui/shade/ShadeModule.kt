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
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepositoryImpl
import com.android.systemui.shade.domain.interactor.BaseShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorSceneContainerImpl
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Provider

/** Module for classes related to the notification shade. */
@Module(includes = [StartShadeModule::class, ShadeViewProviderModule::class])
abstract class ShadeModule {
    companion object {
        @Provides
        @SysUISingleton
        fun provideBaseShadeInteractor(
            sceneContainerFlags: SceneContainerFlags,
            sceneContainerOn: Provider<ShadeInteractorSceneContainerImpl>,
            sceneContainerOff: Provider<ShadeInteractorLegacyImpl>
        ): BaseShadeInteractor {
            return if (sceneContainerFlags.isEnabled()) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeController(
            sceneContainerFlags: SceneContainerFlags,
            sceneContainerOn: Provider<ShadeControllerSceneImpl>,
            sceneContainerOff: Provider<ShadeControllerImpl>
        ): ShadeController {
            return if (sceneContainerFlags.isEnabled()) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeAnimationInteractor(
            sceneContainerFlags: SceneContainerFlags,
            sceneContainerOn: Provider<ShadeAnimationInteractorSceneContainerImpl>,
            sceneContainerOff: Provider<ShadeAnimationInteractorLegacyImpl>
        ): ShadeAnimationInteractor {
            return if (sceneContainerFlags.isEnabled()) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeBackActionInteractor(
            sceneContainerFlags: SceneContainerFlags,
            sceneContainerOn: Provider<ShadeBackActionInteractorImpl>,
            sceneContainerOff: Provider<NotificationPanelViewController>
        ): ShadeBackActionInteractor {
            return if (sceneContainerFlags.isEnabled()) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }
    }

    @Binds
    @SysUISingleton
    abstract fun bindsShadeRepository(impl: ShadeRepositoryImpl): ShadeRepository

    @Binds
    @SysUISingleton
    abstract fun bindsShadeInteractor(si: ShadeInteractorImpl): ShadeInteractor

    @Binds
    @SysUISingleton
    abstract fun bindsShadeViewController(
        notificationPanelViewController: NotificationPanelViewController
    ): ShadeViewController
}
