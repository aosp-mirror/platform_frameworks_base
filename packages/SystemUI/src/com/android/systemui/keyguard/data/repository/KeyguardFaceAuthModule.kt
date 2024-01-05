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
 *
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface KeyguardFaceAuthModule {
    @Binds
    fun deviceEntryFaceAuthRepository(
        impl: DeviceEntryFaceAuthRepositoryImpl
    ): DeviceEntryFaceAuthRepository

    @Binds
    @IntoMap
    @ClassKey(SystemUIDeviceEntryFaceAuthInteractor::class)
    fun bind(impl: SystemUIDeviceEntryFaceAuthInteractor): CoreStartable

    @Binds
    fun keyguardFaceAuthInteractor(
        impl: SystemUIDeviceEntryFaceAuthInteractor
    ): DeviceEntryFaceAuthInteractor

    companion object {
        @Provides
        @SysUISingleton
        @FaceAuthTableLog
        fun provideFaceAuthTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("FaceAuthTableLog", 100)
        }

        @Provides
        @SysUISingleton
        @FaceDetectTableLog
        fun provideFaceDetectTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("FaceDetectTableLog", 100)
        }
    }
}
