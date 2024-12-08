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

package com.android.systemui.statusbar.dagger

import android.content.Context
import com.android.systemui.CameraProtectionLoader
import com.android.systemui.CoreStartable
import com.android.systemui.SysUICutoutProvider
import com.android.systemui.SysUICutoutProviderImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.StatusBarDataLayerModule
import com.android.systemui.statusbar.data.repository.LightBarControllerStore
import com.android.systemui.statusbar.phone.AutoHideController
import com.android.systemui.statusbar.phone.AutoHideControllerImpl
import com.android.systemui.statusbar.phone.LightBarController
import com.android.systemui.statusbar.phone.LightBarControllerImpl
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProviderImpl
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallLog
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.ui.SystemBarUtilsProxyImpl
import com.android.systemui.statusbar.window.MultiDisplayStatusBarWindowControllerStore
import com.android.systemui.statusbar.window.SingleDisplayStatusBarWindowControllerStore
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.StatusBarWindowControllerImpl
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/**
 * A module for **only** classes related to the status bar **UI element**. This module specifically
 * should **not** include:
 * - Classes in the `statusbar` package that are unrelated to the status bar UI.
 * - Status bar classes that are already provided by other modules
 *   ([com.android.systemui.statusbar.pipeline.dagger.StatusBarPipelineModule],
 *   [com.android.systemui.statusbar.policy.dagger.StatusBarPolicyModule], etc.).
 */
@Module(includes = [StatusBarDataLayerModule::class, SystemBarUtilsProxyImpl.Module::class])
interface StatusBarModule {

    @Binds
    @IntoMap
    @ClassKey(LightBarController::class)
    fun lightBarControllerAsCoreStartable(controller: LightBarController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(StatusBarSignalPolicy::class)
    fun bindStatusBarSignalPolicy(impl: StatusBarSignalPolicy): CoreStartable

    @Binds
    @SysUISingleton
    fun statusBarWindowControllerFactory(
        implFactory: StatusBarWindowControllerImpl.Factory
    ): StatusBarWindowController.Factory

    @Binds @SysUISingleton fun autoHideController(impl: AutoHideControllerImpl): AutoHideController

    @Binds
    fun lightBarControllerFactory(
        legacyFactory: LightBarControllerImpl.LegacyFactory
    ): LightBarController.Factory

    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(OngoingCallController::class)
        fun ongoingCallController(
            controller: OngoingCallController
        ): CoreStartable =
            if (StatusBarChipsModernization.isEnabled) {
                CoreStartable.NOP
            } else {
                controller
            }

        @Provides
        @SysUISingleton
        fun lightBarController(store: LightBarControllerStore): LightBarController {
            return store.defaultDisplay
        }

        @Provides
        @SysUISingleton
        fun windowControllerStore(
            multiDisplayImplLazy: Lazy<MultiDisplayStatusBarWindowControllerStore>,
            singleDisplayImplLazy: Lazy<SingleDisplayStatusBarWindowControllerStore>,
        ): StatusBarWindowControllerStore {
            return if (StatusBarConnectedDisplays.isEnabled) {
                multiDisplayImplLazy.get()
            } else {
                singleDisplayImplLazy.get()
            }
        }

        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(MultiDisplayStatusBarWindowControllerStore::class)
        fun multiDisplayControllerStoreAsCoreStartable(
            storeLazy: Lazy<MultiDisplayStatusBarWindowControllerStore>
        ): CoreStartable {
            return if (StatusBarConnectedDisplays.isEnabled) {
                storeLazy.get()
            } else {
                CoreStartable.NOP
            }
        }

        @Provides
        @SysUISingleton
        @OngoingCallLog
        fun provideOngoingCallLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("OngoingCall", 75)
        }

        @Provides
        @SysUISingleton
        fun sysUiCutoutProvider(
            factory: SysUICutoutProviderImpl.Factory,
            context: Context,
            cameraProtectionLoader: CameraProtectionLoader,
        ): SysUICutoutProvider {
            return factory.create(context, cameraProtectionLoader)
        }

        @Provides
        @SysUISingleton
        fun contentInsetsProvider(
            factory: StatusBarContentInsetsProviderImpl.Factory,
            context: Context,
            configurationController: ConfigurationController,
            sysUICutoutProvider: SysUICutoutProvider,
        ): StatusBarContentInsetsProvider {
            return factory.create(context, configurationController, sysUICutoutProvider)
        }
    }
}
