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
import com.android.systemui.inputdevice.tutorial.data.model.DeviceSchedulerInfo
import com.android.systemui.inputdevice.tutorial.data.model.TutorialSchedulerInfo
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@SysUISingleton
class TutorialSchedulerRepository
@Inject
constructor(@Application private val applicationContext: Context) {

    private val Context.dataStore: DataStore<Preferences> by
        preferencesDataStore(name = DATASTORE_NAME)

    suspend fun loadData(): TutorialSchedulerInfo {
        return applicationContext.dataStore.data.map { pref -> getSchedulerInfo(pref) }.first()
    }

    suspend fun updateConnectTime(device: DeviceType, time: Long) {
        applicationContext.dataStore.edit { pref -> pref[getConnectKey(device)] = time }
    }

    suspend fun updateLaunch(device: DeviceType) {
        applicationContext.dataStore.edit { pref -> pref[getLaunchedKey(device)] = true }
    }

    private fun getSchedulerInfo(pref: Preferences): TutorialSchedulerInfo {
        return TutorialSchedulerInfo(
            keyboard = getDeviceSchedulerInfo(pref, DeviceType.KEYBOARD),
            touchpad = getDeviceSchedulerInfo(pref, DeviceType.TOUCHPAD)
        )
    }

    private fun getDeviceSchedulerInfo(pref: Preferences, device: DeviceType): DeviceSchedulerInfo {
        val isLaunched = pref[getLaunchedKey(device)] ?: false
        val connectionTime = pref[getConnectKey(device)] ?: null
        return DeviceSchedulerInfo(isLaunched, connectionTime)
    }

    private fun getLaunchedKey(device: DeviceType) =
        booleanPreferencesKey(device.name + IS_LAUNCHED_SUFFIX)

    private fun getConnectKey(device: DeviceType) =
        longPreferencesKey(device.name + CONNECT_TIME_SUFFIX)

    companion object {
        const val DATASTORE_NAME = "TutorialScheduler"
        const val IS_LAUNCHED_SUFFIX = "_IS_LAUNCHED"
        const val CONNECT_TIME_SUFFIX = "_CONNECTED_TIME"
    }
}

enum class DeviceType {
    KEYBOARD,
    TOUCHPAD
}
