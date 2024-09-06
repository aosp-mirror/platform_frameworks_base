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
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LeAudioProfile
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
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
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceItemActionInteractorTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val kosmos = testKosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
    private lateinit var actionInteractorImpl: DeviceItemActionInteractor
    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var activeMediaDeviceItem: DeviceItem
    private lateinit var notConnectedDeviceItem: DeviceItem
    private lateinit var connectedMediaDeviceItem: DeviceItem
    private lateinit var connectedOtherDeviceItem: DeviceItem
    @Mock private lateinit var dialog: SystemUIDialog
    @Mock private lateinit var profileManager: LocalBluetoothProfileManager
    @Mock private lateinit var leAudioProfile: LeAudioProfile
    @Mock private lateinit var assistantProfile: LocalBluetoothLeBroadcastAssistant
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var bluetoothDeviceGroupId2: BluetoothDevice
    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession().initMocks(this).mockStatic(BluetoothUtils::class.java).startMocking()
        activeMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null
            )
        notConnectedDeviceItem =
            DeviceItem(
                type = DeviceItemType.SAVED_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null
            )
        connectedMediaDeviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null
            )
        connectedOtherDeviceItem =
            DeviceItem(
                type = DeviceItemType.CONNECTED_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = null,
                background = null
            )
        actionInteractorImpl = kosmos.deviceItemActionInteractor
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun testOnClick_connectedMedia_setActive() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)
                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(cachedBluetoothDevice).setActive()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedBluetoothDevice.address,
                        DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_activeMedia_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)
                actionInteractorImpl.onClick(activeMediaDeviceItem, dialog)
                verify(cachedBluetoothDevice).disconnect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedBluetoothDevice.address,
                        DeviceItemType.ACTIVE_MEDIA_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_connectedOtherDevice_disconnect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)
                actionInteractorImpl.onClick(connectedOtherDeviceItem, dialog)
                verify(cachedBluetoothDevice).disconnect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedBluetoothDevice.address,
                        DeviceItemType.CONNECTED_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_saved_connect() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)
                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(cachedBluetoothDevice).connect()
                verify(bluetoothTileDialogLogger)
                    .logDeviceClick(
                        cachedBluetoothDevice.address,
                        DeviceItemType.SAVED_BLUETOOTH_DEVICE
                    )
            }
        }
    }

    @Test
    fun testOnClick_audioSharingDisabled_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(false)

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_inAudioSharing_clickedDeviceHasSource_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(cachedBluetoothDevice.connectableProfiles)
                        .thenReturn(listOf(leAudioProfile))

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(BluetoothUtils.isBroadcasting(ArgumentMatchers.any())).thenReturn(true)
                whenever(
                        BluetoothUtils.hasConnectedBroadcastSource(
                            ArgumentMatchers.any(),
                            ArgumentMatchers.any()
                        )
                    )
                    .thenReturn(true)

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_inAudioSharing_clickedDeviceNoSource_shouldLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(cachedBluetoothDevice.device).thenReturn(bluetoothDevice)
                whenever(cachedBluetoothDevice.connectableProfiles)
                        .thenReturn(listOf(leAudioProfile))

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(BluetoothUtils.isBroadcasting(ArgumentMatchers.any())).thenReturn(true)
                whenever(
                    BluetoothUtils.hasConnectedBroadcastSource(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()
                    )
                )
                        .thenReturn(false)

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter)
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_noConnectedLeDevice_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_hasOneConnectedLeDevice_clickedNonLe_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(
                        assistantProfile.getDevicesMatchingConnectionStates(ArgumentMatchers.any())
                    )
                    .thenReturn(listOf(bluetoothDevice))

                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_hasOneConnectedLeDevice_clickedLe_shouldLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.device).thenReturn(bluetoothDevice)
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(cachedBluetoothDevice.profiles).thenReturn(listOf(leAudioProfile))
                whenever(leAudioProfile.isEnabled(ArgumentMatchers.any())).thenReturn(true)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(
                        assistantProfile.getDevicesMatchingConnectionStates(ArgumentMatchers.any())
                    )
                    .thenReturn(listOf(bluetoothDevice))

                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(activityStarter)
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_hasOneConnectedLeDevice_clickedConnectedLe_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(
                        assistantProfile.getDevicesMatchingConnectionStates(ArgumentMatchers.any())
                    )
                    .thenReturn(listOf(bluetoothDevice))

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_hasTwoConnectedLeDevice_clickedNotConnectedLe_shouldNotLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(
                        assistantProfile.getDevicesMatchingConnectionStates(ArgumentMatchers.any())
                    )
                    .thenReturn(listOf(bluetoothDevice, bluetoothDeviceGroupId2))
                whenever(leAudioProfile.getGroupId(ArgumentMatchers.any())).thenAnswer {
                    val device = it.arguments.first() as BluetoothDevice
                    if (device == bluetoothDevice) GROUP_ID_1 else GROUP_ID_2
                }

                actionInteractorImpl.onClick(notConnectedDeviceItem, dialog)
                verify(activityStarter, Mockito.never())
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    @Test
    fun testOnClick_hasTwoConnectedLeDevice_clickedConnectedLe_shouldLaunchSettings() {
        with(kosmos) {
            testScope.runTest {
                whenever(cachedBluetoothDevice.device).thenReturn(bluetoothDevice)
                whenever(cachedBluetoothDevice.address).thenReturn(DEVICE_ADDRESS)
                whenever(cachedBluetoothDevice.profiles).thenReturn(listOf(leAudioProfile))
                whenever(leAudioProfile.isEnabled(ArgumentMatchers.any())).thenReturn(true)

                whenever(BluetoothUtils.isAudioSharingEnabled()).thenReturn(true)
                whenever(localBluetoothManager.profileManager).thenReturn(profileManager)
                whenever(profileManager.leAudioProfile).thenReturn(leAudioProfile)
                whenever(profileManager.leAudioBroadcastAssistantProfile)
                    .thenReturn(assistantProfile)

                whenever(
                        assistantProfile.getDevicesMatchingConnectionStates(ArgumentMatchers.any())
                    )
                    .thenReturn(listOf(bluetoothDevice, bluetoothDeviceGroupId2))
                whenever(leAudioProfile.getGroupId(ArgumentMatchers.any())).thenAnswer {
                    val device = it.arguments.first() as BluetoothDevice
                    if (device == bluetoothDevice) GROUP_ID_1 else GROUP_ID_2
                }

                actionInteractorImpl.onClick(connectedMediaDeviceItem, dialog)
                verify(activityStarter)
                    .postStartActivityDismissingKeyguard(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(),
                        ArgumentMatchers.any()
                    )
            }
        }
    }

    private companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
        const val DEVICE_ADDRESS = "address"
        const val GROUP_ID_1 = 1
        const val GROUP_ID_2 = 2
    }
}
