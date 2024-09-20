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
import android.graphics.Bitmap
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreference
import com.android.settingslib.bluetooth.devicesettings.ActionSwitchPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingHelpPreference
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsProviderServiceStatus
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreference
import com.android.settingslib.bluetooth.devicesettings.MultiTogglePreferenceState
import com.android.settingslib.bluetooth.devicesettings.ToggleInfo
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigItemModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingConfigModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingIcon
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingStateModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.ToggleModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
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

        `when`(context.bindService(any(), anyInt(), any(), any())).then { input ->
            val intent = input.getArgument<Intent?>(0)
            val connection = input.getArgument<ServiceConnection>(3)

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
                            SETTING_PROVIDER_SERVICE_CLASS_NAME_1,
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
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertConfig(config!!, DEVICE_SETTING_CONFIG)
            assertThat(config.mainItems[0])
                .isInstanceOf(DeviceSettingConfigItemModel.AppProvidedItem::class.java)
            assertThat(config.mainItems[1])
                .isInstanceOf(
                    DeviceSettingConfigItemModel.BuiltinItem.CommonBuiltinItem::class.java
                )
            assertThat(config.mainItems[2])
                .isInstanceOf(
                    DeviceSettingConfigItemModel.BuiltinItem.BluetoothProfilesItem::class.java
                )
        }
    }

    @Test
    fun getDeviceSettingsConfig_noMetadata_returnNull() {
        testScope.runTest {
            `when`(
                    bluetoothDevice.getMetadata(
                        DeviceSettingServiceConnection.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS
                    )
                )
                .thenReturn("".toByteArray())
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertThat(config).isNull()
        }
    }

    @Test
    fun getDeviceSettingsConfig_providerServiceNotEnabled_returnNull() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(false))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertThat(config).isNull()
        }
    }

    @Test
    fun getDeviceSettingsConfig_bindingServiceFail_returnNull() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            doReturn(false).`when`(context).bindService(any(), anyInt(), any(), any())

            val config = underTest.getDeviceSettingsConfig(cachedDevice)

            assertThat(config).isNull()
        }
    }

    @Test
    fun getDeviceSetting_actionSwitchPreference_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertDeviceSetting(setting!!, DEVICE_SETTING_1)
        }
    }

    @Test
    fun getDeviceSetting_multiTogglePreference_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService2.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_2))
            }
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_ANC)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertDeviceSetting(setting!!, DEVICE_SETTING_2)
        }
    }

    @Test
    fun getDeviceSetting_helpPreference_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService2.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_HELP))
            }
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DEVICE_SETTING_ID_HELP)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertDeviceSetting(setting!!, DEVICE_SETTING_HELP)
        }
    }

    @Test
    fun getDeviceSetting_noConfig_returnNull() {
        testScope.runTest {
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertThat(setting).isNull()
        }
    }

    @Test
    fun updateDeviceSettingState_switchState_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService1.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_1))
            }
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()
            val updateFunc = (setting as DeviceSettingModel.ActionSwitchPreference).updateState!!
            updateFunc(DeviceSettingStateModel.ActionSwitchPreferenceState(false))
            runCurrent()

            verify(settingProviderService1)
                .updateDeviceSettings(
                    DEVICE_INFO,
                    DeviceSettingState.Builder()
                        .setSettingId(DeviceSettingId.DEVICE_SETTING_ID_HEADER)
                        .setPreferenceState(
                            ActionSwitchPreferenceState.Builder().setChecked(false).build()
                        )
                        .build(),
                )
        }
    }

    @Test
    fun updateDeviceSettingState_multiToggleState_success() {
        testScope.runTest {
            `when`(configService.getDeviceSettingsConfig(any())).thenReturn(DEVICE_SETTING_CONFIG)
            `when`(settingProviderService2.registerDeviceSettingsListener(any(), any())).then {
                input ->
                input
                    .getArgument<IDeviceSettingsListener>(1)
                    .onDeviceSettingsChanged(listOf(DEVICE_SETTING_2))
            }
            `when`(settingProviderService1.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            `when`(settingProviderService2.serviceStatus)
                .thenReturn(DeviceSettingsProviderServiceStatus(true))
            var setting: DeviceSettingModel? = null

            underTest
                .getDeviceSetting(cachedDevice, DeviceSettingId.DEVICE_SETTING_ID_ANC)
                .onEach { setting = it }
                .launchIn(backgroundScope)
            runCurrent()
            val updateFunc = (setting as DeviceSettingModel.MultiTogglePreference).updateState
            updateFunc(DeviceSettingStateModel.MultiTogglePreferenceState(2))
            runCurrent()

            verify(settingProviderService2)
                .updateDeviceSettings(
                    DEVICE_INFO,
                    DeviceSettingState.Builder()
                        .setSettingId(DeviceSettingId.DEVICE_SETTING_ID_ANC)
                        .setPreferenceState(
                            MultiTogglePreferenceState.Builder().setState(2).build()
                        )
                        .build(),
                )
        }
    }

    private fun assertDeviceSetting(actual: DeviceSettingModel, serviceResponse: DeviceSetting) {
        assertThat(actual.id).isEqualTo(serviceResponse.settingId)
        when (actual) {
            is DeviceSettingModel.ActionSwitchPreference -> {
                assertThat(serviceResponse.preference)
                    .isInstanceOf(ActionSwitchPreference::class.java)
                val pref = serviceResponse.preference as ActionSwitchPreference
                assertThat(actual.title).isEqualTo(pref.title)
                assertThat(actual.summary).isEqualTo(pref.summary)
                assertThat(actual.icon)
                    .isEqualTo(pref.icon?.let { DeviceSettingIcon.BitmapIcon(it) })
                assertThat(actual.isAllowedChangingState).isEqualTo(pref.isAllowedChangingState)
                if (pref.hasSwitch()) {
                    assertThat(actual.switchState!!.checked).isEqualTo(pref.checked)
                } else {
                    assertThat(actual.switchState).isNull()
                }
            }
            is DeviceSettingModel.MultiTogglePreference -> {
                assertThat(serviceResponse.preference)
                    .isInstanceOf(MultiTogglePreference::class.java)
                val pref = serviceResponse.preference as MultiTogglePreference
                assertThat(actual.title).isEqualTo(pref.title)
                assertThat(actual.isAllowedChangingState).isEqualTo(pref.isAllowedChangingState)
                assertThat(actual.toggles.size).isEqualTo(pref.toggleInfos.size)
                for (i in 0..<actual.toggles.size) {
                    assertToggle(actual.toggles[i], pref.toggleInfos[i])
                }
            }
            is DeviceSettingModel.HelpPreference -> {
                assertThat(serviceResponse.preference)
                    .isInstanceOf(DeviceSettingHelpPreference::class.java)
                val pref = serviceResponse.preference as DeviceSettingHelpPreference
                assertThat(actual.intent).isSameInstanceAs(pref.intent)
            }
            else -> {}
        }
    }

    private fun assertToggle(actual: ToggleModel, serviceResponse: ToggleInfo) {
        assertThat(actual.label).isEqualTo(serviceResponse.label)
        assertThat((actual.icon as DeviceSettingIcon.BitmapIcon).bitmap)
            .isEqualTo(serviceResponse.icon)
    }

    private fun assertConfig(
        actual: DeviceSettingConfigModel,
        serviceResponse: DeviceSettingsConfig,
    ) {
        assertThat(actual.mainItems.size).isEqualTo(serviceResponse.mainContentItems.size)
        for (i in 0..<actual.mainItems.size) {
            assertConfigItem(actual.mainItems[i], serviceResponse.mainContentItems[i])
        }
        assertThat(actual.moreSettingsItems.size).isEqualTo(serviceResponse.moreSettingsItems.size)
        for (i in 0..<actual.moreSettingsItems.size) {
            assertConfigItem(actual.moreSettingsItems[i], serviceResponse.moreSettingsItems[i])
        }
    }

    private fun assertConfigItem(
        actual: DeviceSettingConfigItemModel,
        serviceResponse: DeviceSettingItem,
    ) {
        assertThat(actual.settingId).isEqualTo(serviceResponse.settingId)
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
        const val DEVICE_SETTING_ID_HELP = 12345
        val DEVICE_SETTING_APP_PROVIDED_ITEM_1 =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_HEADER,
                SETTING_PROVIDER_SERVICE_PACKAGE_NAME_1,
                SETTING_PROVIDER_SERVICE_CLASS_NAME_1,
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_1,
            )
        val DEVICE_SETTING_APP_PROVIDED_ITEM_2 =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_ANC,
                SETTING_PROVIDER_SERVICE_PACKAGE_NAME_2,
                SETTING_PROVIDER_SERVICE_CLASS_NAME_2,
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_2,
            )
        val DEVICE_SETTING_BUILT_IN_ITEM =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_AUDIO_DEVICE_TYPE_GROUP,
                "",
                "",
                "",
                "device_type",
            )
        val DEVICE_SETTING_BUILT_IN_BT_PROFILES_ITEM =
            DeviceSettingItem(
                DeviceSettingId.DEVICE_SETTING_ID_BLUETOOTH_PROFILES,
                "",
                "",
                "",
                "bluetooth_profiles",
            )
        val DEVICE_SETTING_HELP_ITEM =
            DeviceSettingItem(
                DEVICE_SETTING_ID_HELP,
                SETTING_PROVIDER_SERVICE_PACKAGE_NAME_2,
                SETTING_PROVIDER_SERVICE_CLASS_NAME_2,
                SETTING_PROVIDER_SERVICE_INTENT_ACTION_2,
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
                    MultiTogglePreference.Builder()
                        .setTitle("title1")
                        .setAllowChangingState(true)
                        .addToggleInfo(
                            ToggleInfo.Builder()
                                .setLabel("label1")
                                .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                                .build()
                        )
                        .addToggleInfo(
                            ToggleInfo.Builder()
                                .setLabel("label2")
                                .setIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                                .build()
                        )
                        .build()
                )
                .build()
        val DEVICE_SETTING_HELP =
            DeviceSetting.Builder()
                .setSettingId(DEVICE_SETTING_ID_HELP)
                .setPreference(DeviceSettingHelpPreference.Builder().setIntent(Intent()).build())
                .build()
        val DEVICE_SETTING_CONFIG =
            DeviceSettingsConfig(
                listOf(
                    DEVICE_SETTING_APP_PROVIDED_ITEM_1,
                    DEVICE_SETTING_BUILT_IN_ITEM,
                    DEVICE_SETTING_BUILT_IN_BT_PROFILES_ITEM,
                ),
                listOf(DEVICE_SETTING_APP_PROVIDED_ITEM_2),
                DEVICE_SETTING_HELP_ITEM,
            )
    }
}
