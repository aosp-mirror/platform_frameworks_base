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

package com.android.systemui.volume.dagger

import android.media.session.MediaSessionManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.volume.data.repository.MediaControllerRepository
import com.android.settingslib.volume.data.repository.MediaControllerRepositoryImpl
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.panel.component.mediaoutput.data.repository.LocalMediaRepositoryFactory
import com.android.systemui.volume.panel.component.mediaoutput.data.repository.LocalMediaRepositoryFactoryImpl
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaControllerInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaControllerInteractorImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

@Module
interface MediaDevicesModule {

    @Binds
    fun bindLocalMediaRepositoryFactory(
        impl: LocalMediaRepositoryFactoryImpl
    ): LocalMediaRepositoryFactory

    @Binds
    fun bindMediaControllerInteractor(
        impl: MediaControllerInteractorImpl
    ): MediaControllerInteractor

    companion object {

        @Provides
        @SysUISingleton
        fun provideMediaDeviceSessionRepository(
            intentsReceiver: AudioManagerEventsReceiver,
            mediaSessionManager: MediaSessionManager,
            localBluetoothManager: LocalBluetoothManager?,
            @Application coroutineScope: CoroutineScope,
            @Background backgroundContext: CoroutineContext,
        ): MediaControllerRepository =
            MediaControllerRepositoryImpl(
                intentsReceiver,
                mediaSessionManager,
                localBluetoothManager,
                coroutineScope,
                backgroundContext,
            )
    }
}
