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

import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepositoryImpl
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxyImpl
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import dagger.Binds
import dagger.Module

@Module
abstract class StatusBarPipelineModule {
    @Binds
    abstract fun airplaneModeRepository(impl: AirplaneModeRepositoryImpl): AirplaneModeRepository

    @Binds
    abstract fun airplaneModeViewModel(impl: AirplaneModeViewModelImpl): AirplaneModeViewModel

    @Binds
    abstract fun connectivityRepository(impl: ConnectivityRepositoryImpl): ConnectivityRepository

    @Binds
    abstract fun wifiRepository(impl: WifiRepositoryImpl): WifiRepository

    @Binds
    abstract fun wifiInteractor(impl: WifiInteractorImpl): WifiInteractor

    @Binds
    abstract fun mobileConnectionsRepository(
        impl: MobileConnectionsRepositoryImpl
    ): MobileConnectionsRepository

    @Binds
    abstract fun userSetupRepository(impl: UserSetupRepositoryImpl): UserSetupRepository

    @Binds
    abstract fun mobileMappingsProxy(impl: MobileMappingsProxyImpl): MobileMappingsProxy

    @Binds
    abstract fun mobileIconsInteractor(impl: MobileIconsInteractorImpl): MobileIconsInteractor
}
