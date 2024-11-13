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

package com.android.systemui.statusbar.chips.casttootherdevice.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.mediarouter.data.repository.MediaRouterRepository
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.casttootherdevice.domain.model.MediaRouterCastModel
import com.android.systemui.statusbar.policy.CastDevice
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for MediaRouter events, used to show the cast-audio-to-other-device chip in the status
 * bar.
 */
@SysUISingleton
class MediaRouterChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val mediaRouterRepository: MediaRouterRepository,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    private val activeCastDevice: StateFlow<CastDevice?> =
        mediaRouterRepository.castDevices
            .map { allDevices -> allDevices.firstOrNull { it.isCasting } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    /** The current casting state, according to MediaRouter APIs. */
    val mediaRouterCastingState: StateFlow<MediaRouterCastModel> =
        activeCastDevice
            .map {
                if (it != null) {
                    logger.log(TAG, LogLevel.INFO, { str1 = it.name }, { "State: Casting($str1)" })
                    MediaRouterCastModel.Casting(deviceName = it.name)
                } else {
                    logger.log(TAG, LogLevel.INFO, {}, { "State: DoingNothing" })
                    MediaRouterCastModel.DoingNothing
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), MediaRouterCastModel.DoingNothing)

    /** Stops the currently active MediaRouter cast. */
    fun stopCasting() {
        activeCastDevice.value?.let { mediaRouterRepository.stopCasting(it) }
    }

    companion object {
        private const val TAG = "MediaRouter"
    }
}
