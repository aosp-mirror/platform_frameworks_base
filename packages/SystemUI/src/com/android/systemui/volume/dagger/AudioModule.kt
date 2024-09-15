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

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.statusbar.notification.domain.interactor.NotificationsSoundPolicyInteractor
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.data.repository.AudioRepositoryImpl
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.settingslib.volume.data.repository.AudioSharingRepositoryImpl
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.settingslib.volume.shared.AudioManagerEventsReceiverImpl
import com.android.systemui.dagger.SysUISingleton
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
        @SysUISingleton
        fun provideAudioManagerIntentsReceiver(
            @Application context: Context,
            @Application coroutineScope: CoroutineScope,
        ): AudioManagerEventsReceiver = AudioManagerEventsReceiverImpl(context, coroutineScope)

        @Provides
        @SysUISingleton
        fun provideAudioRepository(
            intentsReceiver: AudioManagerEventsReceiver,
            audioManager: AudioManager,
            contentResolver: ContentResolver,
            @Background coroutineContext: CoroutineContext,
            @Application coroutineScope: CoroutineScope,
        ): AudioRepository =
            AudioRepositoryImpl(
                intentsReceiver,
                audioManager,
                contentResolver,
                coroutineContext,
                coroutineScope,
            )

        @Provides
        @SysUISingleton
        fun provideAudioSharingRepository(
            localBluetoothManager: LocalBluetoothManager?,
            @Background coroutineContext: CoroutineContext,
        ): AudioSharingRepository =
            AudioSharingRepositoryImpl(localBluetoothManager, coroutineContext)

        @Provides
        @SysUISingleton
        fun provideAudioModeInteractor(repository: AudioRepository): AudioModeInteractor =
            AudioModeInteractor(repository)

        @Provides
        @SysUISingleton
        fun provideAudioVolumeInteractor(
            audioRepository: AudioRepository,
            notificationsSoundPolicyInteractor: NotificationsSoundPolicyInteractor,
        ): AudioVolumeInteractor =
            AudioVolumeInteractor(audioRepository, notificationsSoundPolicyInteractor)
    }
}
