/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.retail.data.repository

import android.database.ContentObserver
import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Repository to track if the device is in Retail mode */
interface RetailModeRepository {
    /** Flow of whether the device is currently in retail mode. */
    val retailMode: StateFlow<Boolean>

    /** Last value of whether the device is in retail mode. */
    val inRetailMode: Boolean
        get() = retailMode.value
}

/**
 * Tracks [Settings.Global.DEVICE_DEMO_MODE].
 *
 * @see UserManager.isDeviceInDemoMode
 */
@SysUISingleton
class RetailModeSettingsRepository
@Inject
constructor(
    globalSettings: GlobalSettings,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Application scope: CoroutineScope,
) : RetailModeRepository {
    override val retailMode =
        conflatedCallbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                globalSettings.registerContentObserverSync(RETAIL_MODE_SETTING, observer)

                awaitClose { globalSettings.unregisterContentObserverSync(observer) }
            }
            .onStart { emit(Unit) }
            .map { globalSettings.getInt(RETAIL_MODE_SETTING, 0) != 0 }
            .flowOn(backgroundDispatcher)
            .stateIn(scope, SharingStarted.Eagerly, false)

    companion object {
        private const val RETAIL_MODE_SETTING = Settings.Global.DEVICE_DEMO_MODE
    }
}
