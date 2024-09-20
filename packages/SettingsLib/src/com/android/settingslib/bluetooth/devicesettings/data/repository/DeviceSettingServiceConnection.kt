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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.text.TextUtils
import android.util.Log
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.DeviceInfo
import com.android.settingslib.bluetooth.devicesettings.DeviceSetting
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingPreferenceState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingState
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsConfigProviderService
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsListener
import com.android.settingslib.bluetooth.devicesettings.IDeviceSettingsProviderService
import com.android.settingslib.bluetooth.devicesettings.data.model.ServiceConnectionStatus
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSettingServiceConnection(
    private val cachedDevice: CachedBluetoothDevice,
    private val context: Context,
    private val bluetoothAdaptor: BluetoothAdapter,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
) {
    data class EndPoint(
        private val packageName: String,
        private val className: String?,
        private val intentAction: String,
    ) {
        fun toIntent(): Intent =
            Intent().apply {
                if (className.isNullOrBlank()) {
                    setPackage(packageName)
                } else {
                    setClassName(packageName, className)
                }
                setAction(intentAction)
            }

        fun isValid(): Boolean {
            return !TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(intentAction)
        }
    }

    private var isServiceEnabled =
        coroutineScope.async(backgroundCoroutineContext, start = CoroutineStart.LAZY) {
            val states = getSettingsProviderServices()?.values ?: return@async false
            combine(states) { it.toList() }
                .mapNotNull { allStatus ->
                    if (allStatus.any { it is ServiceConnectionStatus.Failed }) {
                        false
                    } else if (allStatus.all { it is ServiceConnectionStatus.Connected }) {
                        allStatus
                            .filterIsInstance<
                                ServiceConnectionStatus.Connected<IDeviceSettingsProviderService>
                            >()
                            .all { it.service.serviceStatus?.enabled == true }
                    } else {
                        null
                    }
                }
                .first()
        }

    private var config =
        coroutineScope.async(backgroundCoroutineContext, start = CoroutineStart.LAZY) {
            val intent =
                tryGetEndpointFromMetadata(cachedDevice)?.toIntent()
                    ?: run {
                        Log.i(TAG, "Unable to read device setting metadata from $cachedDevice")
                        return@async null
                    }
            getService(intent, IDeviceSettingsConfigProviderService.Stub::asInterface)
                .flatMapConcat {
                    when (it) {
                        is ServiceConnectionStatus.Connected ->
                            flowOf(
                                it.service.getDeviceSettingsConfig(
                                    deviceInfo { setBluetoothAddress(cachedDevice.address) }
                                )
                            )
                        ServiceConnectionStatus.Connecting -> flowOf()
                        ServiceConnectionStatus.Failed -> flowOf(null)
                    }
                }
                .first()
        }

    private val settingIdToItemMapping =
        flow {
                if (!isServiceEnabled.await()) {
                    Log.w(TAG, "Service is disabled")
                    return@flow
                }
                getSettingsProviderServices()
                    ?.values
                    ?.map {
                        it.flatMapLatest { status ->
                            when (status) {
                                is ServiceConnectionStatus.Connected ->
                                    getDeviceSettingsFromService(cachedDevice, status.service)
                                else -> flowOf(emptyList())
                            }
                        }
                    }
                    ?.let { items -> combine(items) { it.toList().flatten() } }
                    ?.map { items -> items.associateBy { it.settingId } }
                    ?.let { emitAll(it) }
            }
            .shareIn(scope = coroutineScope, started = SharingStarted.WhileSubscribed(), replay = 1)

    /** Gets [DeviceSettingsConfig] for the device, return null when failed. */
    suspend fun getDeviceSettingsConfig(): DeviceSettingsConfig? {
        if (!isServiceEnabled.await()) {
            Log.w(TAG, "Service is disabled")
            return null
        }
        return readConfig()
    }

    /** Gets all device settings for the device. */
    fun getDeviceSettingList(): Flow<List<DeviceSetting>> =
        settingIdToItemMapping.map { it.values.toList() }

    /** Gets the device settings with the ID for the device. */
    fun getDeviceSetting(@DeviceSettingId deviceSettingId: Int): Flow<DeviceSetting?> =
        settingIdToItemMapping.map { it[deviceSettingId] }

    /** Updates the device setting state for the device. */
    suspend fun updateDeviceSettings(
        @DeviceSettingId deviceSettingId: Int,
        deviceSettingPreferenceState: DeviceSettingPreferenceState,
    ) {
        if (!isServiceEnabled.await()) {
            Log.w(TAG, "Service is disabled")
            return
        }
        readConfig()?.let { config ->
            (config.mainContentItems + config.moreSettingsItems)
                .find { it.settingId == deviceSettingId }
                ?.let {
                    getSettingsProviderServices()
                        ?.get(EndPoint(it.packageName, it.className, it.intentAction))
                        ?.filterIsInstance<
                            ServiceConnectionStatus.Connected<IDeviceSettingsProviderService>
                        >()
                        ?.first()
                }
                ?.service
                ?.updateDeviceSettings(
                    deviceInfo { setBluetoothAddress(cachedDevice.address) },
                    DeviceSettingState.Builder()
                        .setSettingId(deviceSettingId)
                        .setPreferenceState(deviceSettingPreferenceState)
                        .build(),
                )
        }
    }

    private suspend fun readConfig(): DeviceSettingsConfig? = config.await()

    private suspend fun getSettingsProviderServices():
        Map<EndPoint, StateFlow<ServiceConnectionStatus<IDeviceSettingsProviderService>>>? =
        readConfig()
            ?.let { config ->
                (config.mainContentItems + config.moreSettingsItems).map {
                    EndPoint(
                        packageName = it.packageName,
                        className = it.className,
                        intentAction = it.intentAction,
                    )
                }
            }
            ?.filter { it.isValid() }
            ?.distinct()
            ?.associateBy(
                { it },
                { endpoint ->
                    services.computeIfAbsent(endpoint) {
                        getService(
                                endpoint.toIntent(),
                                IDeviceSettingsProviderService.Stub::asInterface,
                            )
                            .stateIn(
                                coroutineScope.plus(backgroundCoroutineContext),
                                SharingStarted.WhileSubscribed(),
                                ServiceConnectionStatus.Connecting,
                            )
                    }
                },
            )

    private fun getDeviceSettingsFromService(
        cachedDevice: CachedBluetoothDevice,
        service: IDeviceSettingsProviderService,
    ): Flow<List<DeviceSetting>> {
        return callbackFlow {
                val listener =
                    object : IDeviceSettingsListener.Stub() {
                        override fun onDeviceSettingsChanged(settings: List<DeviceSetting>) {
                            launch { send(settings) }
                        }
                    }
                val deviceInfo = deviceInfo { setBluetoothAddress(cachedDevice.address) }
                service.registerDeviceSettingsListener(deviceInfo, listener)
                awaitClose { service.unregisterDeviceSettingsListener(deviceInfo, listener) }
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyList())
    }

    private fun <T : IInterface> getService(
        intent: Intent,
        transform: ((IBinder) -> T),
    ): Flow<ServiceConnectionStatus<T>> {
        return callbackFlow {
                val serviceConnection =
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            launch { send(ServiceConnectionStatus.Connected(transform(service))) }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            launch { send(ServiceConnectionStatus.Connecting) }
                        }
                    }
                if (
                    !context.bindService(
                        intent,
                        Context.BIND_AUTO_CREATE,
                        { launch { it.run() } },
                        serviceConnection,
                    )
                ) {
                    Log.w(TAG, "Fail to bind service $intent")
                    launch { send(ServiceConnectionStatus.Failed) }
                }
                awaitClose { context.unbindService(serviceConnection) }
            }
            .flowOn(backgroundCoroutineContext)
    }

    private suspend fun tryGetEndpointFromMetadata(cachedDevice: CachedBluetoothDevice): EndPoint? =
        withContext(backgroundCoroutineContext) {
            val packageName =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_PACKAGE_NAME,
                ) ?: return@withContext null
            val className =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_CLASS_NAME,
                ) ?: return@withContext null
            val intentAction =
                BluetoothUtils.getFastPairCustomizedField(
                    cachedDevice.device,
                    CONFIG_SERVICE_INTENT_ACTION,
                ) ?: return@withContext null
            EndPoint(packageName, className, intentAction)
        }

    private inline fun deviceInfo(block: DeviceInfo.Builder.() -> Unit): DeviceInfo {
        return DeviceInfo.Builder().apply { block() }.build()
    }

    companion object {
        const val TAG = "DeviceSettingSrvConn"
        const val METADATA_FAST_PAIR_CUSTOMIZED_FIELDS: Int = 25
        const val CONFIG_SERVICE_PACKAGE_NAME = "DEVICE_SETTINGS_CONFIG_PACKAGE_NAME"
        const val CONFIG_SERVICE_CLASS_NAME = "DEVICE_SETTINGS_CONFIG_CLASS"
        const val CONFIG_SERVICE_INTENT_ACTION = "DEVICE_SETTINGS_CONFIG_ACTION"

        val services =
            ConcurrentHashMap<
                EndPoint,
                StateFlow<ServiceConnectionStatus<IDeviceSettingsProviderService>>,
            >()
    }
}
