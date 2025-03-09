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

import android.bluetooth.BluetoothDevice
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.cachedBluetoothDeviceManager
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingDialogViewModelTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
    @Mock private lateinit var profileManager: LocalBluetoothProfileManager
    @Mock private lateinit var leAudioProfile: LeAudioProfile
    private val updateFlow = MutableSharedFlow<Unit>()
    private lateinit var underTest: AudioSharingDialogViewModel

    @Before
    fun setUp() {
        with(kosmos) {
            // TODO(b/364515243): use real object instead of mock
            whenever(deviceItemInteractor.deviceItemUpdateRequest).thenReturn(updateFlow)
            whenever(deviceItemInteractor.deviceItemUpdate)
                .thenReturn(MutableStateFlow(emptyList()))
            underTest = audioSharingDialogViewModel
        }
    }

    @Test
    fun testDialogState_show() =
        with(kosmos) {
            testScope.runTest {
                val deviceName = "name"
                whenever(cachedBluetoothDevice.name).thenReturn(deviceName)
                val actual by collectLastValue(underTest.dialogState)
                runCurrent()
                assertThat(actual)
                    .isEqualTo(
                        AudioSharingDialogState.Show(
                            context.getString(
                                R.string.quick_settings_bluetooth_audio_sharing_dialog_subtitle,
                                deviceName,
                                ""
                            ),
                            context.getString(
                                R.string
                                    .quick_settings_bluetooth_audio_sharing_dialog_switch_to_button,
                                deviceName
                            )
                        )
                    )
            }
        }

    @Test
    fun testDialogState_showWithActiveDeviceName() =
        with(kosmos) {
            testScope.runTest {
                val deviceName = "name"
                whenever(cachedBluetoothDevice.name).thenReturn(deviceName)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(localBluetoothManager.cachedDeviceManager)
                    .thenReturn(cachedBluetoothDeviceManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(leAudioProfile.activeDevices).thenReturn(listOf(mock<BluetoothDevice>()))
                whenever(cachedBluetoothDeviceManager.findDevice(any()))
                    .thenReturn(cachedBluetoothDevice)
                val actual by collectLastValue(underTest.dialogState)
                runCurrent()
                assertThat(actual)
                    .isEqualTo(
                        AudioSharingDialogState.Show(
                            context.getString(
                                R.string.quick_settings_bluetooth_audio_sharing_dialog_subtitle,
                                deviceName,
                                deviceName
                            ),
                            context.getString(
                                R.string
                                    .quick_settings_bluetooth_audio_sharing_dialog_switch_to_button,
                                deviceName
                            )
                        )
                    )
            }
        }

    @Test
    fun testDialogState_hide() =
        with(kosmos) {
            testScope.runTest {
                val actual by collectLastValue(underTest.dialogState)
                runCurrent()
                updateFlow.emit(Unit)
                assertThat(actual).isEqualTo(AudioSharingDialogState.Hide)
            }
        }
}
