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
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.ui.adapter.QSSceneAdapterImpl
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.carrier.ShadeCarrierGroupControllerLog
import com.android.systemui.shade.data.repository.PrivacyChipRepository
import com.android.systemui.shade.data.repository.PrivacyChipRepositoryImpl
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepositoryImpl
import com.android.systemui.shade.domain.interactor.BaseShadeInteractor
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorLegacyImpl
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractorSceneContainerImpl
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractor
import com.android.systemui.shade.domain.interactor.ShadeBackActionInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorLegacyImpl
import com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractorImpl
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
            sceneContainerOn: Provider<ShadeInteractorSceneContainerImpl>,
            sceneContainerOff: Provider<ShadeInteractorLegacyImpl>
        ): BaseShadeInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeController(
            sceneContainerOn: Provider<ShadeControllerSceneImpl>,
            sceneContainerOff: Provider<ShadeControllerImpl>
        ): ShadeController {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeAnimationInteractor(
            sceneContainerOn: Provider<ShadeAnimationInteractorSceneContainerImpl>,
            sceneContainerOff: Provider<ShadeAnimationInteractorLegacyImpl>
        ): ShadeAnimationInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeBackActionInteractor(
            sceneContainerOn: Provider<ShadeBackActionInteractorImpl>,
            sceneContainerOff: Provider<NotificationPanelViewController>
        ): ShadeBackActionInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideShadeLockscreenInteractor(
            sceneContainerOn: Provider<ShadeLockscreenInteractorImpl>,
            sceneContainerOff: Provider<NotificationPanelViewController>
        ): ShadeLockscreenInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun providePanelExpansionInteractor(
            sceneContainerOn: Provider<PanelExpansionInteractorImpl>,
            sceneContainerOff: Provider<NotificationPanelViewController>
        ): PanelExpansionInteractor {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun provideQuickSettingsController(
            sceneContainerOn: Provider<QuickSettingsControllerSceneImpl>,
            sceneContainerOff: Provider<QuickSettingsControllerImpl>,
        ): QuickSettingsController {
            return if (SceneContainerFlag.isEnabled) {
                sceneContainerOn.get()
            } else {
                sceneContainerOff.get()
            }
        }

        @Provides
        @SysUISingleton
        fun providesQSContainerController(impl: QSSceneAdapterImpl): QSContainerController {
            return impl
        }

        @Provides
        @SysUISingleton
        @ShadeCarrierGroupControllerLog
        fun provideShadeCarrierLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("ShadeCarrierGroupControllerLog", 400)
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
    abstract fun bindsShadeViewController(shadeSurface: ShadeSurface): ShadeViewController

    @Binds
    @SysUISingleton
    abstract fun bindsPrivacyChipRepository(impl: PrivacyChipRepositoryImpl): PrivacyChipRepository
}
