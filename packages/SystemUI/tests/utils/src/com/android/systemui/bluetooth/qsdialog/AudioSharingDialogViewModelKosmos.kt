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

package com.android.systemui.bluetooth.qsdialog

import android.content.applicationContext
import com.android.internal.logging.uiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.phone.systemUIDialogDotFactory
import kotlinx.coroutines.CoroutineScope
import org.mockito.kotlin.mock

val Kosmos.cachedBluetoothDevice: CachedBluetoothDevice by Kosmos.Fixture { mock {} }

val Kosmos.audioSharingDialogViewModel: AudioSharingDialogViewModel by
    Kosmos.Fixture {
        AudioSharingDialogViewModel(
            deviceItemInteractor,
            audioSharingInteractor,
            applicationContext,
            localBluetoothManager,
            cachedBluetoothDevice,
            testScope.backgroundScope,
            testDispatcher
        )
    }

val Kosmos.audioSharingDialogViewModelFactory: AudioSharingDialogViewModel.Factory by
    Kosmos.Fixture {
        object : AudioSharingDialogViewModel.Factory {
            override fun create(
                cachedBluetoothDevice: CachedBluetoothDevice,
                coroutineScope: CoroutineScope
            ): AudioSharingDialogViewModel {
                return audioSharingDialogViewModel
            }
        }
    }

val Kosmos.audioSharingDialogDelegate: AudioSharingDialogDelegate by
    Kosmos.Fixture {
        AudioSharingDialogDelegate(
            cachedBluetoothDevice,
            testScope.backgroundScope,
            audioSharingDialogViewModelFactory,
            systemUIDialogDotFactory,
            uiEventLogger
        )
    }
