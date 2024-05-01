/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.controls.dagger

import android.app.Activity
import android.content.pm.PackageManager
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.ControlsMetricsLoggerImpl
import com.android.systemui.controls.controller.ControlsBindingController
import com.android.systemui.controls.controller.ControlsBindingControllerImpl
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.controller.ControlsFavoritePersistenceWrapper
import com.android.systemui.controls.controller.ControlsTileResourceConfiguration
import com.android.systemui.controls.management.ControlsEditingActivity
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsListingControllerImpl
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.controls.management.ControlsRequestDialog
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.AuthorizedPanelsRepositoryImpl
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.SelectedComponentRepositoryImpl
import com.android.systemui.controls.settings.ControlsSettingsDialogManager
import com.android.systemui.controls.settings.ControlsSettingsDialogManagerImpl
import com.android.systemui.controls.settings.ControlsSettingsRepository
import com.android.systemui.controls.settings.ControlsSettingsRepositoryImpl
import com.android.systemui.controls.ui.ControlActionCoordinator
import com.android.systemui.controls.ui.ControlActionCoordinatorImpl
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.ControlsUiControllerImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.DeviceControlsTile
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Module for injecting classes in `com.android.systemui.controls`-
 *
 * Classes provided by this module should only be injected directly into other classes in this
 * module. For injecting outside of this module (for example, [GlobalActionsDialog], inject
 * [ControlsComponent] and obtain the corresponding optionals from it.
 */
@Module
abstract class ControlsModule {

    @Module
    companion object {
        @JvmStatic
        @Provides
        @SysUISingleton
        @ControlsFeatureEnabled
        fun providesControlsFeatureEnabled(pm: PackageManager): Boolean {
            return pm.hasSystemFeature(PackageManager.FEATURE_CONTROLS)
        }

        const val DEVICE_CONTROLS_SPEC = "controls"

        @Provides
        @IntoMap
        @StringKey(DEVICE_CONTROLS_SPEC)
        fun provideDeviceControlsTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
                QSTileConfig(
                        tileSpec = TileSpec.create(DEVICE_CONTROLS_SPEC),
                        uiConfig =
                        QSTileUIConfig.Resource(
                                iconRes = com.android.systemui.res.R.drawable.controls_icon,
                                labelRes = com.android.systemui.res.R.string.quick_controls_title
                        ),
                        instanceId = uiEventLogger.getNewInstanceId(),
                )
    }

    @Binds
    abstract fun provideControlsListingController(
        controller: ControlsListingControllerImpl
    ): ControlsListingController

    @Binds
    abstract fun provideControlsController(controller: ControlsControllerImpl): ControlsController

    @Binds
    abstract fun provideControlsBindingController(
        controller: ControlsBindingControllerImpl
    ): ControlsBindingController

    @Binds
    abstract fun provideUiController(controller: ControlsUiControllerImpl): ControlsUiController

    @Binds
    abstract fun provideSettingsManager(
            manager: ControlsSettingsRepositoryImpl
    ): ControlsSettingsRepository

    @Binds
    abstract fun provideDialogManager(
            manager: ControlsSettingsDialogManagerImpl
    ): ControlsSettingsDialogManager

    @Binds
    abstract fun provideMetricsLogger(logger: ControlsMetricsLoggerImpl): ControlsMetricsLogger

    @Binds
    abstract fun provideControlActionCoordinator(
        coordinator: ControlActionCoordinatorImpl
    ): ControlActionCoordinator

    @Binds
    abstract fun provideAuthorizedPanelsRepository(
        repository: AuthorizedPanelsRepositoryImpl
    ): AuthorizedPanelsRepository

    @Binds
    abstract fun providePreferredPanelRepository(
        repository: SelectedComponentRepositoryImpl
    ): SelectedComponentRepository

    @BindsOptionalOf
    abstract fun optionalPersistenceWrapper(): ControlsFavoritePersistenceWrapper

    @BindsOptionalOf
    abstract fun provideControlsTileResourceConfiguration(): ControlsTileResourceConfiguration

    @Binds
    @IntoMap
    @ClassKey(ControlsProviderSelectorActivity::class)
    abstract fun provideControlsProviderActivity(
        activity: ControlsProviderSelectorActivity
    ): Activity

    @Binds
    @IntoMap
    @ClassKey(ControlsFavoritingActivity::class)
    abstract fun provideControlsFavoritingActivity(
        activity: ControlsFavoritingActivity
    ): Activity

    @Binds
    @IntoMap
    @ClassKey(ControlsEditingActivity::class)
    abstract fun provideControlsEditingActivity(
        activity: ControlsEditingActivity
    ): Activity

    @Binds
    @IntoMap
    @ClassKey(ControlsRequestDialog::class)
    abstract fun provideControlsRequestDialog(
        activity: ControlsRequestDialog
    ): Activity

    @Binds
    @IntoMap
    @ClassKey(ControlsActivity::class)
    abstract fun provideControlsActivity(activity: ControlsActivity): Activity

    @Binds
    @IntoMap
    @StringKey(DeviceControlsTile.TILE_SPEC)
    abstract fun bindDeviceControlsTile(controlsTile: DeviceControlsTile): QSTileImpl<*>
}
