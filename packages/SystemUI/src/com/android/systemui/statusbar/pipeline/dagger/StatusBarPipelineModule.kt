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
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.dagger

import android.net.wifi.WifiManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepositoryImpl
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.icons.shared.BindableIconsRegistry
import com.android.systemui.statusbar.pipeline.icons.shared.BindableIconsRegistryImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigCoreStartable
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileRepositorySwitcher
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxyImpl
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxyImpl
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepositorySwitcher
import com.android.systemui.statusbar.pipeline.satellite.data.RealDeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.data.prod.DeviceBasedSatelliteRepositoryImpl
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModel
import com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel.DeviceBasedSatelliteViewModelImpl
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.shared.ui.binder.CollapsedStatusBarViewBinder
import com.android.systemui.statusbar.pipeline.shared.ui.binder.CollapsedStatusBarViewBinderImpl
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.CollapsedStatusBarViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.CollapsedStatusBarViewModelImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.RealWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositorySwitcher
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.DisabledWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepository
import com.android.systemui.statusbar.policy.data.repository.UserSetupRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.util.function.Supplier
import javax.inject.Named
import kotlinx.coroutines.flow.Flow

@Module
abstract class StatusBarPipelineModule {
    @Binds
    abstract fun airplaneModeRepository(impl: AirplaneModeRepositoryImpl): AirplaneModeRepository

    @Binds
    abstract fun airplaneModeViewModel(impl: AirplaneModeViewModelImpl): AirplaneModeViewModel

    @Binds
    abstract fun bindableIconsRepository(impl: BindableIconsRegistryImpl): BindableIconsRegistry

    @Binds
    abstract fun connectivityRepository(impl: ConnectivityRepositoryImpl): ConnectivityRepository

    @Binds
    abstract fun realDeviceBasedSatelliteRepository(
        impl: DeviceBasedSatelliteRepositoryImpl
    ): RealDeviceBasedSatelliteRepository

    @Binds
    abstract fun deviceBasedSatelliteRepository(
        impl: DeviceBasedSatelliteRepositorySwitcher
    ): DeviceBasedSatelliteRepository

    @Binds
    abstract fun deviceBasedSatelliteViewModel(
        impl: DeviceBasedSatelliteViewModelImpl
    ): DeviceBasedSatelliteViewModel

    @Binds abstract fun wifiRepository(impl: WifiRepositorySwitcher): WifiRepository

    @Binds abstract fun wifiInteractor(impl: WifiInteractorImpl): WifiInteractor

    @Binds
    abstract fun mobileConnectionsRepository(
        impl: MobileRepositorySwitcher
    ): MobileConnectionsRepository

    @Binds abstract fun userSetupRepository(impl: UserSetupRepositoryImpl): UserSetupRepository

    @Binds abstract fun mobileMappingsProxy(impl: MobileMappingsProxyImpl): MobileMappingsProxy

    @Binds
    abstract fun subscriptionManagerProxy(
        impl: SubscriptionManagerProxyImpl
    ): SubscriptionManagerProxy

    @Binds
    abstract fun mobileIconsInteractor(impl: MobileIconsInteractorImpl): MobileIconsInteractor

    @Binds
    @IntoMap
    @ClassKey(MobileUiAdapter::class)
    abstract fun bindFeature(impl: MobileUiAdapter): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(CarrierConfigCoreStartable::class)
    abstract fun bindCarrierConfigStartable(impl: CarrierConfigCoreStartable): CoreStartable

    @Binds
    abstract fun collapsedStatusBarViewModel(
        impl: CollapsedStatusBarViewModelImpl
    ): CollapsedStatusBarViewModel

    @Binds
    abstract fun collapsedStatusBarViewBinder(
        impl: CollapsedStatusBarViewBinderImpl
    ): CollapsedStatusBarViewBinder

    companion object {

        @Provides
        @SysUISingleton
        fun provideRealWifiRepository(
            wifiManager: WifiManager?,
            disabledWifiRepository: DisabledWifiRepository,
            wifiRepositoryImplFactory: WifiRepositoryImpl.Factory,
        ): RealWifiRepository {
            // If we have a null [WifiManager], then the wifi repository should be permanently
            // disabled.
            return if (wifiManager == null) {
                disabledWifiRepository
            } else {
                wifiRepositoryImplFactory.create(wifiManager)
            }
        }

        @Provides
        @SysUISingleton
        @Named(FIRST_MOBILE_SUB_SHOWING_NETWORK_TYPE_ICON)
        fun provideFirstMobileSubShowingNetworkTypeIconProvider(
            mobileIconsViewModel: MobileIconsViewModel,
        ): Supplier<Flow<Boolean>> {
            return Supplier<Flow<Boolean>> {
                mobileIconsViewModel.firstMobileSubShowingNetworkTypeIcon
            }
        }

        @Provides
        @SysUISingleton
        @WifiInputLog
        fun provideWifiLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("WifiInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @WifiTableLog
        fun provideWifiTableLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("WifiTableLog", 100)
        }

        @Provides
        @SysUISingleton
        @AirplaneTableLog
        fun provideAirplaneTableLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("AirplaneTableLog", 30)
        }

        @Provides
        @SysUISingleton
        @SharedConnectivityInputLog
        fun provideSharedConnectivityTableLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("SharedConnectivityInputLog", 60)
        }

        @Provides
        @SysUISingleton
        @MobileSummaryLog
        fun provideMobileSummaryLogBuffer(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("MobileSummaryLog", 100)
        }

        @Provides
        @SysUISingleton
        @MobileInputLog
        fun provideMobileInputLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("MobileInputLog", 300)
        }

        @Provides
        @SysUISingleton
        @MobileViewLog
        fun provideMobileViewLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("MobileViewLog", 100)
        }

        @Provides
        @SysUISingleton
        @VerboseMobileViewLog
        fun provideVerboseMobileViewLogBuffer(factory: LogBufferFactory): LogBuffer {
            return factory.create("VerboseMobileViewLog", 100)
        }

        @Provides
        @SysUISingleton
        @DeviceBasedSatelliteInputLog
        fun provideDeviceBasedSatelliteInputLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("DeviceBasedSatelliteInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @VerboseDeviceBasedSatelliteInputLog
        fun provideVerboseDeviceBasedSatelliteInputLog(factory: LogBufferFactory): LogBuffer {
            return factory.create("VerboseDeviceBasedSatelliteInputLog", 200)
        }

        @Provides
        @SysUISingleton
        @DeviceBasedSatelliteTableLog
        fun provideDeviceBasedSatelliteTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("DeviceBasedSatelliteTableLog", 200)
        }

        const val FIRST_MOBILE_SUB_SHOWING_NETWORK_TYPE_ICON =
            "FirstMobileSubShowingNetworkTypeIcon"
    }
}
