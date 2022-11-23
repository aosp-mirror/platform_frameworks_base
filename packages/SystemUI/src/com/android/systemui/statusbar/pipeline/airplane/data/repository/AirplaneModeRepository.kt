/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.airplane.data.repository

import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Global
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.qs.SettingObserver
import com.android.systemui.statusbar.pipeline.dagger.AirplaneTableLog
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Provides data related to airplane mode.
 *
 * IMPORTANT: This is currently *not* used to render any airplane mode information anywhere. It is
 * only used to help [com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel]
 * determine what parts of the wifi icon view should be shown.
 *
 * TODO(b/238425913): Consider migrating the status bar airplane mode icon to use this repo.
 */
interface AirplaneModeRepository {
    /** Observable for whether the device is currently in airplane mode. */
    val isAirplaneMode: StateFlow<Boolean>
}

@SysUISingleton
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class AirplaneModeRepositoryImpl
@Inject
constructor(
    @Background private val bgHandler: Handler,
    private val globalSettings: GlobalSettings,
    @AirplaneTableLog logger: TableLogBuffer,
    @Application scope: CoroutineScope,
) : AirplaneModeRepository {
    // TODO(b/254848912): Replace this with a generic SettingObserver coroutine once we have it.
    override val isAirplaneMode: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val observer =
                    object :
                        SettingObserver(
                            globalSettings,
                            bgHandler,
                            Global.AIRPLANE_MODE_ON,
                            UserHandle.USER_ALL
                        ) {
                        override fun handleValueChanged(value: Int, observedChange: Boolean) {
                            trySend(value == 1)
                        }
                    }

                observer.isListening = true
                trySend(observer.value == 1)
                awaitClose { observer.isListening = false }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                logger,
                columnPrefix = "",
                columnName = "isAirplaneMode",
                initialValue = false
            )
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                // When the observer starts listening, the flow will emit the current value so the
                // initialValue here is irrelevant.
                initialValue = false,
            )
}
