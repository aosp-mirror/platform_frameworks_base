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

package com.android.systemui.inputdevice.tutorial.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.tutorial.data.model.DeviceSchedulerInfo
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@SysUISingleton
class TutorialSchedulerRepository(
    private val applicationContext: Context,
    backgroundScope: CoroutineScope,
    dataStoreName: String,
) {
    @Inject
    constructor(
        @Application applicationContext: Context,
        @Background backgroundScope: CoroutineScope,
    ) : this(applicationContext, backgroundScope, dataStoreName = DATASTORE_NAME)

    private val Context.dataStore: DataStore<Preferences> by
        preferencesDataStore(name = dataStoreName, scope = backgroundScope)

    suspend fun setScheduledTutorialLaunchTime(device: DeviceType, time: Instant) {
        applicationContext.dataStore.edit { pref -> pref[getLaunchKey(device)] = time.epochSecond }
    }

    suspend fun isScheduledTutorialLaunched(deviceType: DeviceType): Boolean =
        loadData()[deviceType]!!.isLaunched

    suspend fun getScheduledTutorialLaunchTime(deviceType: DeviceType): Instant? =
        loadData()[deviceType]!!.launchTime

    suspend fun setFirstConnectionTime(device: DeviceType, time: Instant) {
        applicationContext.dataStore.edit { pref -> pref[getConnectKey(device)] = time.epochSecond }
    }

    suspend fun setNotified(device: DeviceType) {
        applicationContext.dataStore.edit { pref -> pref[getNotificationKey(device)] = true }
    }

    suspend fun isNotified(deviceType: DeviceType): Boolean = loadData()[deviceType]!!.isNotified

    suspend fun wasEverConnected(deviceType: DeviceType): Boolean =
        loadData()[deviceType]!!.wasEverConnected

    suspend fun getFirstConnectionTime(deviceType: DeviceType): Instant? =
        loadData()[deviceType]!!.firstConnectionTime

    private suspend fun loadData(): Map<DeviceType, DeviceSchedulerInfo> {
        return applicationContext.dataStore.data.map { pref -> getSchedulerInfo(pref) }.first()
    }

    private fun getSchedulerInfo(pref: Preferences): Map<DeviceType, DeviceSchedulerInfo> {
        return mapOf(
            DeviceType.KEYBOARD to getDeviceSchedulerInfo(pref, DeviceType.KEYBOARD),
            DeviceType.TOUCHPAD to getDeviceSchedulerInfo(pref, DeviceType.TOUCHPAD),
        )
    }

    private fun getDeviceSchedulerInfo(pref: Preferences, device: DeviceType): DeviceSchedulerInfo {
        val launchTime = pref[getLaunchKey(device)]
        val connectionTime = pref[getConnectKey(device)]
        val isNotified = pref[getNotificationKey(device)] == true
        return DeviceSchedulerInfo(launchTime, connectionTime, isNotified)
    }

    private fun getLaunchKey(device: DeviceType) =
        longPreferencesKey(device.name + LAUNCH_TIME_SUFFIX)

    private fun getConnectKey(device: DeviceType) =
        longPreferencesKey(device.name + CONNECT_TIME_SUFFIX)

    private fun getNotificationKey(device: DeviceType) =
        booleanPreferencesKey(device.name + NOTIFIED_SUFFIX)

    suspend fun clear() {
        applicationContext.dataStore.edit { it.clear() }
    }

    companion object {
        const val DATASTORE_NAME = "TutorialScheduler"
        const val LAUNCH_TIME_SUFFIX = "_LAUNCH_TIME"
        const val CONNECT_TIME_SUFFIX = "_CONNECT_TIME"
        const val NOTIFIED_SUFFIX = "_NOTIFIED"
    }
}

enum class DeviceType {
    KEYBOARD,
    TOUCHPAD,
}
