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

package com.android.systemui.statusbar.chips.mediaprojection.domain.interactor

import android.content.pm.PackageManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.mediaprojection.MediaProjectionUtils.packageHasCastingCapabilities
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Interactor for media projection events, used to show chips in the status bar for share-to-app and
 * cast-to-other-device events. See
 * [com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel] and
 * [com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel.CastToOtherDeviceChipViewModel]
 * for more details on what those events are.
 */
@SysUISingleton
class MediaProjectionChipInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val mediaProjectionRepository: MediaProjectionRepository,
    private val packageManager: PackageManager,
    @StatusBarChipsLog private val logger: LogBuffer,
) {
    val projection: StateFlow<ProjectionChipModel> =
        mediaProjectionRepository.mediaProjectionState
            .map { state ->
                when (state) {
                    is MediaProjectionState.NotProjecting -> {
                        logger.log(TAG, LogLevel.INFO, {}, { "State: NotProjecting" })
                        ProjectionChipModel.NotProjecting
                    }
                    is MediaProjectionState.Projecting -> {
                        val type =
                            if (packageHasCastingCapabilities(packageManager, state.hostPackage)) {
                                ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE
                            } else {
                                ProjectionChipModel.Type.SHARE_TO_APP
                            }
                        logger.log(
                            TAG,
                            LogLevel.INFO,
                            {
                                str1 = type.name
                                str2 = state.hostPackage
                                str3 = state.hostDeviceName
                            },
                            { "State: Projecting(type=$str1 hostPackage=$str2 hostDevice=$str3)" }
                        )
                        ProjectionChipModel.Projecting(type, state)
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ProjectionChipModel.NotProjecting)

    /** Stops the currently active projection. */
    fun stopProjecting() {
        scope.launch { mediaProjectionRepository.stopProjecting() }
    }

    companion object {
        private const val TAG = "MediaProjection"
    }
}
