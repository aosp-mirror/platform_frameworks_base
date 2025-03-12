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

package com.android.systemui.bluetooth.qsdialog.dagger

import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.flags.Flags
import com.android.settingslib.volume.data.repository.AudioSharingRepository as SettingsLibAudioSharingRepository
import com.android.systemui.bluetooth.qsdialog.ActiveMediaDeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.AudioSharingDeviceItemActionInteractorImpl
import com.android.systemui.bluetooth.qsdialog.AudioSharingInteractor
import com.android.systemui.bluetooth.qsdialog.AudioSharingInteractorEmptyImpl
import com.android.systemui.bluetooth.qsdialog.AudioSharingInteractorImpl
import com.android.systemui.bluetooth.qsdialog.AudioSharingMediaDeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.AudioSharingRepository
import com.android.systemui.bluetooth.qsdialog.AudioSharingRepositoryEmptyImpl
import com.android.systemui.bluetooth.qsdialog.AudioSharingRepositoryImpl
import com.android.systemui.bluetooth.qsdialog.AvailableAudioSharingMediaDeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.AvailableMediaDeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.ConnectedDeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.DeviceItemActionInteractor
import com.android.systemui.bluetooth.qsdialog.DeviceItemActionInteractorImpl
import com.android.systemui.bluetooth.qsdialog.DeviceItemFactory
import com.android.systemui.bluetooth.qsdialog.DeviceItemType
import com.android.systemui.bluetooth.qsdialog.SavedDeviceItemFactory
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import dagger.Lazy
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineDispatcher

/** Dagger module for audio sharing code for BT QS dialog */
@Module
interface AudioSharingModule {

    companion object {
        @Provides
        @SysUISingleton
        fun provideAudioSharingRepository(
            localBluetoothManager: LocalBluetoothManager?,
            settingsLibAudioSharingRepository: SettingsLibAudioSharingRepository,
            @Background backgroundDispatcher: CoroutineDispatcher,
        ): AudioSharingRepository =
            if (
                (Flags.enableLeAudioSharing() || Flags.audioSharingDeveloperOption()) &&
                    localBluetoothManager != null
            ) {
                AudioSharingRepositoryImpl(
                    localBluetoothManager,
                    settingsLibAudioSharingRepository,
                    backgroundDispatcher,
                )
            } else {
                AudioSharingRepositoryEmptyImpl()
            }

        @Provides
        @SysUISingleton
        fun provideAudioSharingInteractor(
            localBluetoothManager: LocalBluetoothManager?,
            impl: Lazy<AudioSharingInteractorImpl>,
            emptyImpl: Lazy<AudioSharingInteractorEmptyImpl>,
        ): AudioSharingInteractor =
            if (
                (Flags.enableLeAudioSharing() || Flags.audioSharingDeveloperOption()) &&
                    localBluetoothManager != null
            ) {
                impl.get()
            } else {
                emptyImpl.get()
            }

        @Provides
        @SysUISingleton
        fun provideDeviceItemActionInteractor(
            localBluetoothManager: LocalBluetoothManager?,
            audioSharingImpl: Lazy<AudioSharingDeviceItemActionInteractorImpl>,
            impl: Lazy<DeviceItemActionInteractorImpl>,
        ): DeviceItemActionInteractor =
            if (
                (Flags.enableLeAudioSharing() || Flags.audioSharingDeveloperOption()) &&
                    localBluetoothManager != null
            ) {
                audioSharingImpl.get()
            } else {
                impl.get()
            }

        @Provides
        @SysUISingleton
        fun provideDeviceItemFactoryList(
            localBluetoothManager: LocalBluetoothManager?
        ): List<DeviceItemFactory> = buildList {
            add(ActiveMediaDeviceItemFactory())
            if (
                (Flags.enableLeAudioSharing() || Flags.audioSharingDeveloperOption()) &&
                    localBluetoothManager != null
            ) {
                add(AudioSharingMediaDeviceItemFactory(localBluetoothManager))
                add(AvailableAudioSharingMediaDeviceItemFactory(localBluetoothManager))
            }
            add(AvailableMediaDeviceItemFactory())
            add(ConnectedDeviceItemFactory())
            add(SavedDeviceItemFactory())
        }

        @Provides
        @SysUISingleton
        fun provideDeviceItemDisplayPriority(
            localBluetoothManager: LocalBluetoothManager?
        ): List<DeviceItemType> = buildList {
            add(DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE)
            if (
                (Flags.enableLeAudioSharing() || Flags.audioSharingDeveloperOption()) &&
                    localBluetoothManager != null
            ) {
                add(DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE)
                add(DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE)
            }
            add(DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE)
            add(DeviceItemType.CONNECTED_BLUETOOTH_DEVICE)
            add(DeviceItemType.SAVED_BLUETOOTH_DEVICE)
        }
    }
}
