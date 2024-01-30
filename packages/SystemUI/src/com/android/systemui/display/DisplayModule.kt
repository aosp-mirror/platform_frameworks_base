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

package com.android.systemui.display

import com.android.systemui.display.data.repository.DeviceStateRepository
import com.android.systemui.display.data.repository.DeviceStateRepositoryImpl
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.DisplayRepositoryImpl
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractorImpl
import dagger.Binds
import dagger.Module

/** Module binding display related classes. */
@Module
interface DisplayModule {
    @Binds
    fun bindConnectedDisplayInteractor(
        provider: ConnectedDisplayInteractorImpl
    ): ConnectedDisplayInteractor

    @Binds fun bindsDisplayRepository(displayRepository: DisplayRepositoryImpl): DisplayRepository

    @Binds
    fun bindsDeviceStateRepository(
        deviceStateRepository: DeviceStateRepositoryImpl
    ): DeviceStateRepository
}
