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

import android.content.Context
import android.media.AudioManager
import com.android.settingslib.media.data.repository.SpatializerRepository
import com.android.settingslib.media.data.repository.SpatializerRepositoryImpl
import com.android.settingslib.media.domain.interactor.SpatializerInteractor
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.data.repository.AudioRepositoryImpl
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.settingslib.volume.shared.AudioManagerIntentsReceiver
import com.android.settingslib.volume.shared.AudioManagerIntentsReceiverImpl
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

/** Dagger module for audio code in the volume package */
@Module
interface AudioModule {

    companion object {

        @Provides
        fun provideAudioManagerIntentsReceiver(
            @Application context: Context,
            @Application coroutineScope: CoroutineScope,
        ): AudioManagerIntentsReceiver = AudioManagerIntentsReceiverImpl(context, coroutineScope)

        @Provides
        fun provideAudioRepository(
            intentsReceiver: AudioManagerIntentsReceiver,
            audioManager: AudioManager,
            @Background coroutineContext: CoroutineContext,
            @Application coroutineScope: CoroutineScope,
        ): AudioRepository =
            AudioRepositoryImpl(intentsReceiver, audioManager, coroutineContext, coroutineScope)

        @Provides
        fun provideAudioModeInteractor(repository: AudioRepository): AudioModeInteractor =
            AudioModeInteractor(repository)

        @Provides
        fun provdieSpatializerRepository(
            audioManager: AudioManager,
            @Background backgroundContext: CoroutineContext,
        ): SpatializerRepository =
            SpatializerRepositoryImpl(audioManager.spatializer, backgroundContext)

        @Provides
        fun provideSpatializerInetractor(repository: SpatializerRepository): SpatializerInteractor =
            SpatializerInteractor(repository)
    }
}
