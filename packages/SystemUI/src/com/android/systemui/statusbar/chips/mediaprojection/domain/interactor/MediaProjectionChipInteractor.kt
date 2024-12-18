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
import android.media.projection.StopReason
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.mediaprojection.MediaProjectionUtils.packageHasCastingCapabilities
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.statusbar.chips.StatusBarChipLogTags.pad
import com.android.systemui.statusbar.chips.StatusBarChipsLog
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
                        val receiver =
                            if (packageHasCastingCapabilities(packageManager, state.hostPackage)) {
                                ProjectionChipModel.Receiver.CastToOtherDevice
                            } else {
                                ProjectionChipModel.Receiver.ShareToApp
                            }
                        val contentType =
                            if (Flags.statusBarShowAudioOnlyProjectionChip()) {
                                when (state) {
                                    is MediaProjectionState.Projecting.EntireScreen,
                                    is MediaProjectionState.Projecting.SingleTask ->
                                        ProjectionChipModel.ContentType.Screen
                                    is MediaProjectionState.Projecting.NoScreen ->
                                        ProjectionChipModel.ContentType.Audio
                                }
                            } else {
                                ProjectionChipModel.ContentType.Screen
                            }

                        logger.log(
                            TAG,
                            LogLevel.INFO,
                            {
                                bool1 = receiver == ProjectionChipModel.Receiver.CastToOtherDevice
                                bool2 = contentType == ProjectionChipModel.ContentType.Screen
                                str1 = state.hostPackage
                                str2 = state.hostDeviceName
                            },
                            {
                                "State: Projecting(" +
                                    "receiver=${if (bool1) "CastToOtherDevice" else "ShareToApp"} " +
                                    "contentType=${if (bool2) "Screen" else "Audio"} " +
                                    "hostPackage=$str1 " +
                                    "hostDevice=$str2)"
                            },
                        )
                        ProjectionChipModel.Projecting(receiver, contentType, state)
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ProjectionChipModel.NotProjecting)

    /** Stops the currently active projection. */
    fun stopProjecting() {
        scope.launch { mediaProjectionRepository.stopProjecting(StopReason.STOP_PRIVACY_CHIP) }
    }

    companion object {
        private val TAG = "MediaProjection".pad()
    }
}
