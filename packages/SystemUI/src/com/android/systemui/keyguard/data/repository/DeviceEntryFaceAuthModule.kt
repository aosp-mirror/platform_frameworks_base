/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.data.repository

import android.hardware.face.FaceManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository
import com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.NoopDeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.ui.binder.LiftToRunFaceAuthBinder
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
interface DeviceEntryFaceAuthModule {
    @Binds
    fun deviceEntryFaceAuthRepository(
        impl: DeviceEntryFaceAuthRepositoryImpl
    ): DeviceEntryFaceAuthRepository

    @Binds
    @IntoMap
    @ClassKey(DeviceEntryFaceAuthInteractor::class)
    fun bindFaceAuthStartable(impl: DeviceEntryFaceAuthInteractor): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(LiftToRunFaceAuthBinder::class)
    fun bindLiftToRunFaceAuthBinder(impl: LiftToRunFaceAuthBinder): CoreStartable

    companion object {

        @Provides
        @SysUISingleton
        fun providesFaceAuthInteractorInstance(
            faceManager: FaceManager?,
            systemUIDeviceEntryFaceAuthInteractor:
                dagger.Lazy<SystemUIDeviceEntryFaceAuthInteractor>,
            noopDeviceEntryFaceAuthInteractor: dagger.Lazy<NoopDeviceEntryFaceAuthInteractor>,
        ): DeviceEntryFaceAuthInteractor {
            return if (faceManager != null) {
                systemUIDeviceEntryFaceAuthInteractor.get()
            } else {
                noopDeviceEntryFaceAuthInteractor.get()
            }
        }

        @Provides
        @SysUISingleton
        @FaceAuthTableLog
        fun provideFaceAuthTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("FaceAuthTableLog", 400)
        }

        @Provides
        @SysUISingleton
        @FaceDetectTableLog
        fun provideFaceDetectTableLog(factory: TableLogBufferFactory): TableLogBuffer {
            return factory.create("FaceDetectTableLog", 400)
        }
    }
}
