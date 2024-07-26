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

package com.android.settingslib.bluetooth.devicesettings.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSettingRepositoryTest {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var cachedDevice: CachedBluetoothDevice
    @Mock private lateinit var bluetoothDevice: BluetoothDevice
    @Mock private lateinit var context: Context
    @Mock private lateinit var bluetoothAdapter: BluetoothAdapter
    @Mock private lateinit var configService: IDeviceSettingsConfigProviderService.Stub
    @Mock private lateinit var settingProviderService1: IDeviceSettingsProviderService.Stub
    @Mock private lateinit var settingProviderService2: IDeviceSettingsProviderService.Stub
    @Captor
    private lateinit var metadataChangeCaptor:
        ArgumentCaptor<BluetoothAdapter.OnMetadataChangedListener>

    private lateinit var underTest: DeviceSettingRepository
    private val testScope = TestScope()

    @Before
    fun setUp() {
        `when`(cachedDevice.device).thenReturn(bluetoothDevice)
        `when`(cachedDevice.address).thenReturn(BLUETOOTH_ADDRESS)
        `when`(
                bluetoothDevice.getMetadata(
                    DeviceSettingServiceConnection.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS
                )
            )
            .thenReturn(BLUETOOTH_DEVICE_METADATA.toByteArray())

        `when`(configService.queryLocalInterface(anyString())).thenReturn(configService)
        `when`(settingProviderService1.queryLocalInterface(anyString()))
            .thenReturn(settingProviderService1)
        `when`(settingProviderService2.queryLocalInterface(anyString()))
            .thenReturn(settingProviderService2)

        `when`(context.bindService(any(), any(), anyInt())).then { input ->
            val intent = input.getArgument<Intent?>(0)
            val connection = input.getArgument<ServiceConnection>(1)

            when (intent?.action) {
                CONFIG_SERVICE_INTENT_ACTION ->
                    connection.onServiceConnected(
                        ComponentName(CONFIG_SERVICE_PACKAGE_NAME, CONFIG_SERVICE_CLASS_NAME),
                        configService,
                    )
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_1 ->
                    connection.onServiceConnected(
                        ComponentName(
                            SETTING_PROVIDER_SERVICE_PACKAGE_NAME_1,
                            SETTING_PROVIDER_SERVICE_CLASS_NAME_1
                        ),
                        settingProviderService1,
                    )
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_2 ->
                    connection.onServiceConnected(
                        ComponentName(
                            SETTING_PROVIDER_SERVICE_PACKAGE_NAME_2,
                            SETTING_PROVIDER_SERVICE_CLASS_NAME_2,
                        ),
                        settingProviderService2,
                    )
            }
            true
        }
        underTest =
            DeviceSettingRepositoryImpl(
                context,
                bluetoothAdapter,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @After
    fun clean() {
        DeviceSettingServiceConnection.services.clear()
    }

    @Test
    fun getDeviceSettingsConfig_withMetadata_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertThat(config).isSameInstanceAs(DEVICE_SETTING_CONFIG)
        }
    }

    @Test
    fun getDeviceSettingsConfig_waitMetadataChange_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(
                    bluetoothDevice.getMetadata(
                        DeviceSettingServiceConnection.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS
                    )
                )
                .thenReturn("".toByteArray())

            var config: DeviceSettingsConfig? = null
            val job = launch { config = underTest.getDeviceSettingsConfig(cachedDevice) }
            delay(1000)
            verify(bluetoothAdapter)
                .addOnMetadataChangedListener(
                    eq(bluetoothDevice),
                    any(),
                    metadataChangeCaptor.capture()
                )
            metadataChangeCaptor.value.onMetadataChanged(
                bluetoothDevice,
                DeviceSettingServiceConnection.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS,
                BLUETOOTH_DEVICE_METADATA.toByteArray(),
            )
            `when`(
                    bluetoothDevice.getMetadata(
                        DeviceSettingServiceConnection.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS
                    )
                )
                .thenReturn(BLUETOOTH_DEVICE_METADATA.toByteArray())

            job.join()
            assertThat(config).isSameInstanceAs(DEVICE_SETTING_CONFIG)
        }
    }

    @Test
    fun getDeviceSettingsConfig_bindingServiceFail_returnNull() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            doReturn(false).`when`(context).bindService(any(), any(), anyInt())

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertThat(config).isNull()
        }
    }

    @Test
    fun getDeviceSettingList_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            `when`(settingProviderService2.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_2))
            }
            var settings: List<DeviceSetting>? = null

            underTest
                .getDeviceSettingList(cachedDevice)
                .onEach { settings = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertThat(settings?.map { it.settingId })
                .containsExactly(
                    DeviceSettingId.DEVICE_SETTING_ID_HEADER,
                    DeviceSettingId.DEVICE_SETTING_ID_ANC
                )
            assertThat(settings?.map { (it.preference as ActionSwitchPreference).title })
                .containsExactly(
                    "title1",
                    "title2",
                )
        }
    }

    @Test
    fun getDeviceSetting_oneServiceFailed_returnPartialResult() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            var settings: List<DeviceSetting>? = null

            underTest
                .getDeviceSettingList(cachedDevice)
                .onEach { settings = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertThat(settings?.map { it.settingId })
                .containsExactly(
                    DeviceSettingId.DEVICE_SETTING_ID_HEADER,
                )
            assertThat(settings?.map { (it.preference as ActionSwitchPreference).title })
                .containsExactly(
                    "title1",
                )
        }
    }

    @Test
    fun getDeviceSetting_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            var setting: DeviceSetting? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertThat(setting?.settingId).isEqualTo(DeviceSettingId.DEVICE_SETTING_ID_HEADER)
            assertThat((setting?.preference as ActionSwitchPreference).title).isEqualTo("title1")
        }
    }

    @Test
    fun updateDeviceSetting_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }

            underTest.updateDeviceSettingState(
                cachedDevice,
                DeviceSettingId.DEVICE_SETTING_ID_HEADER,
                ActionSwitchPreferenceState.Builder().build()
            )
            runCurrent()

            verify(settingProviderService1)
                .updateDeviceSettings(
                    DEVICE_INFO,
                    DeviceSettingState.Builder()
                        .setSettingId(DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                        .setPreferenceState(ActionSwitchPreferenceState.Builder().build())
                        .build()
                )
        }
    }

    private companion object {
        const val BLUETOOTH_ADDRESS = "12:34:56:78"
        const val CONFIG_SERVICE_PACKAGE_NAME = "com.android.fake.configservice"
        const val CONFIG_SERVICE_CLASS_NAME = "com.android.fake.configservice.Service"
        const val CONFIG_SERVICE_INTENT_ACTION = "com.android.fake.configservice.BIND"
        const val SETTING_PROVIDER_SERVICE_PACKAGE_NAME_1 =
            "com.android.fake.settingproviderservice1"
        const val SETTING_PROVIDER_SERVICE_CLASS_NAME_1 =
            "com.android.fake.settingproviderservice1.Service"
        const val SETTING_PROVIDER_SERVICE_INTENT_ACTION_1 =
            "com.android.fake.settingproviderservice1.BIND"
        const val SETTING_PROVIDER_SERVICE_PACKAGE_NAME_2 =
            "com.android.fake.settingproviderservice2"
        const val SETTING_PROVIDER_SERVICE_CLASS_NAME_2 =
            "com.android.fake.settingproviderservice2.Service"
        const val SETTING_PROVIDER_SERVICE_INTENT_ACTION_2 =
            "com.android.fake.settingproviderservice2.BIND"
        const val BLUETOOTH_DEVICE_METADATA =
            "<DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>" +
                CONFIG_SERVICE_PACKAGE_NAME +
                "</DEVICE_SETTINGS_CONFIG_PACKAGE_NAME>" +
                "<DEVICE_SETTINGS_CONFIG_CLASS>" +
                CONFIG_SERVICE_CLASS_NAME +
                "</DEVICE_SETTINGS_CONFIG_CLASS>" +
                "<DEVICE_SETTINGS_CONFIG_ACTION>" +
                CONFIG_SERVICE_INTENT_ACTION +
                "</DEVICE_SETTINGS_CONFIG_ACTION>"
        val DEVICE_INFO = DeviceInfo.Builder().setBluetoothAddress(BLUETOOTH_ADDRESS).build()

        val DEVICE_SETTING_ITEM_1 =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_HEADER,
                SETTING_PROVIDER_SERVICE_PACKAGE_NAME_1,
                SETTING_PROVIDER_SERVICE_CLASS_NAME_1,
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_1
            )
        val DEVICE_SETTING_ITEM_2 =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_ANC,
                SETTING_PROVIDER_SERVICE_PACKAGE_NAME_2,
                SETTING_PROVIDER_SERVICE_CLASS_NAME_2,
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_2
            )
        val DEVICE_SETTING_1 =
            DeviceSetting.Builder()
                .setSettingId(DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                .setPreference(
                    ActionSwitchPreference.Builder()
                        .setTitle("title1")
                        .setHasSwitch(true)
                        .setAllowedChangingState(true)
                        .build()
                )
                .build()
        val DEVICE_SETTING_2 =
            DeviceSetting.Builder()
                .setSettingId(DeviceSettingId.DEVICE_SETTING_ID_ANC)
                .setPreference(
                    ActionSwitchPreference.Builder()
                        .setTitle("title2")
                        .setHasSwitch(true)
                        .setAllowedChangingState(true)
                        .build()
                )
                .build()
        val DEVICE_SETTING_CONFIG =
            DeviceSettingsConfig(
                listOf(DEVICE_SETTING_ITEM_1),
                listOf(DEVICE_SETTING_ITEM_2),
                "footer"
            )
    }
}
