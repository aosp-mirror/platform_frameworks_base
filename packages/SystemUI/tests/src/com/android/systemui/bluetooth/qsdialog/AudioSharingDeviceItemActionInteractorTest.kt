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
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingDeviceItemActionInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
    private lateinit var actionInteractorImpl: DeviceItemActionInteractor
    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var connectedAudioSharingMediaDeviceItem: DeviceItem
    private lateinit var connectedMediaDeviceItem: DeviceItem
    private lateinit var inAudioSharingMediaDeviceItem: DeviceItem
    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var leAudioProfile: LeAudioProfile
    @Mock private lateinit var bluetoothDevice: BluetoothDevice

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession().initMocks(this).mockStatic(BluetoothUtils::class.java).startMocking()
        connectedMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        inAudioSharingMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        connectedAudioSharingMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_AUDIO_SHARING_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = kosmos.cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null,
            )
        actionInteractorImpl = kosmos.audioSharingDeviceItemActionInteractorImpl
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    @EnableFlags(Flags.FLAG_AUDIO_SHARING_QS_DIALOG_IMPROVEMENT)
    fun testOnClick_connectedAudioSharingMediaDevice_flagOn_createDialog() {
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                actionInteractorImpl.onClick(connectedAudioSharingMediaDeviceItem, dialog)
                verify(dialogTransitionAnimator)
                    .showFromDialog(any(), any(), eq(null), anyBoolean())
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_AUDIO_SHARING_QS_DIALOG_IMPROVEMENT)
    fun testOnClick_connectedAudioSharingMediaDevice_flagOff_previewOn_createDialog() {
        with(kosmos) {
            testScope.runTest {
                whenever(BluetoothUtils.isAudioSharingPreviewEnabled(any())).thenReturn(true)
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                actionInteractorImpl.onClick(connectedAudioSharingMediaDeviceItem, dialog)
                verify(dialogTransitionAnimator)
                    .showFromDialog(any(), any(), eq(null), anyBoolean())
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_AUDIO_SHARING_QS_DIALOG_IMPROVEMENT)
    fun testOnClick_connectedAudioSharingMediaDevice_flagOff_shouldLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(BluetoothUtils.isAudioSharingPreviewEnabled(any())).thenReturn(false)
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                whenever(cachedBluetoothDevice.device).thenReturn(bluetoothDevice)
                actionInteractorImpl.onClick(connectedAudioSharingMediaDeviceItem, dialog)
                verify(activityStarter)
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any(),
                    )
                verify(dialogTransitionAnimator, never())
                    .showFromDialog(any(), any(), eq(null), anyBoolean())
            }
        }
    }

    @Test
    fun testOnClick_inAudioSharingMediaDevice_doNothing() {
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                actionInteractorImpl.onClick(inAudioSharingMediaDeviceItem, dialog)

                verify(dialogTransitionAnimator, never())
                    .showFromDialog(any(), any(), eq(null), anyBoolean())
            }
        }
    }

    @Test
    fun testOnClick_inAudioSharing_clickedDeviceHasSource_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                whenever(cachedBluetoothDevice.uiAccessibleProfiles)
                    .thenReturn(listOf(leAudioProfile))
                whenever(BluetoothUtils.isBroadcasting(ArgumentMatchers.any())).thenReturn(true)
                whenever(
                        BluetoothUtils.hasConnectedBroadcastSource(
                            ArgumentMatchers.any(),
                            ArgumentMatchers.any(),
                        )
                    )
                    .thenReturn(true)

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any(),
                    )
            }
        }
    }

    @Test
    fun testOnClick_inAudioSharing_clickedDeviceNoSource_shouldLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                bluetoothTileDialogAudioSharingRepository.setAudioSharingAvailable(true)
                whenever(cachedBluetoothDevice.device).thenReturn(bluetoothDevice)
                whenever(cachedBluetoothDevice.uiAccessibleProfiles)
                    .thenReturn(listOf(leAudioProfile))

                whenever(BluetoothUtils.isBroadcasting(ArgumentMatchers.any())).thenReturn(true)
                whenever(
                        BluetoothUtils.hasConnectedBroadcastSource(
                            ArgumentMatchers.any(),
                            ArgumentMatchers.any(),
                        )
                    )
                    .thenReturn(false)

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter)
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any(),
                    )
            }
        }
    }

    private companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
    }
}
