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

package com.android.systemui.mediarouter.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.mediarouter.MediaRouterLog
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.CastDevice
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** A repository for data coming from MediaRouter APIs. */
interface MediaRouterRepository {
    /** A list of the cast devices that MediaRouter is currently aware of. */
    val castDevices: StateFlow<List<CastDevice>>

    /** Stops the cast to the given device. */
    fun stopCasting(device: CastDevice)
}

@SysUISingleton
class MediaRouterRepositoryImpl
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val castController: CastController,
    @MediaRouterLog private val logger: LogBuffer,
) : MediaRouterRepository {
    override val castDevices: StateFlow<List<CastDevice>> =
        conflatedCallbackFlow {
                val callback = CastController.Callback { trySend(castController.castDevices) }
                castController.addCallback(callback)
                awaitClose { castController.removeCallback(callback) }
            }
            // The CastController.Callback is pretty noisy and sends the same values multiple times
            // in a row, so use a distinctUntilChanged before logging.
            .distinctUntilChanged()
            .onEach { allDevices ->
                val logString = allDevices.map { it.shortLogString }.toString()
                logger.log(TAG, LogLevel.INFO, { str1 = logString }, { "All cast devices: $str1" })
            }
            .map { it.filter { device -> device.origin == CastDevice.CastOrigin.MediaRouter } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())

    override fun stopCasting(device: CastDevice) {
        castController.stopCasting(device)
    }

    companion object {
        private const val TAG = "MediaRouterRepo"
    }
}
