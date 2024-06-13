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
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.util.Utils
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
) {
    val projection: StateFlow<ProjectionChipModel> =
        mediaProjectionRepository.mediaProjectionState
            .map { state ->
                when (state) {
                    is MediaProjectionState.NotProjecting -> ProjectionChipModel.NotProjecting
                    is MediaProjectionState.Projecting -> {
                        val type =
                            if (isProjectionToOtherDevice(state.hostPackage)) {
                                ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE
                            } else {
                                ProjectionChipModel.Type.SHARE_TO_APP
                            }
                        ProjectionChipModel.Projecting(type, state)
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ProjectionChipModel.NotProjecting)

    /** Stops the currently active projection. */
    fun stopProjecting() {
        scope.launch { mediaProjectionRepository.stopProjecting() }
    }

    /**
     * Returns true iff projecting to the given [packageName] means that we're projecting to a
     * *different* device (as opposed to projecting to some application on *this* device).
     */
    private fun isProjectionToOtherDevice(packageName: String?): Boolean {
        // The [isHeadlessRemoteDisplayProvider] check approximates whether a projection is to a
        // different device or the same device, because headless remote display packages are the
        // only kinds of packages that do cast-to-other-device. This isn't exactly perfect,
        // because it means that any projection by those headless remote display packages will be
        // marked as going to a different device, even if that isn't always true. See b/321078669.
        return Utils.isHeadlessRemoteDisplayProvider(packageManager, packageName)
    }
}
