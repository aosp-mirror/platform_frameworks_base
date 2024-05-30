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

import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.MediaProjectionRepository
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.domain.interactor.OngoingActivityChipInteractor
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for media-projection-related chips in the status bar.
 *
 * There are two kinds of media projection events that will show chips in the status bar:
 * 1) Share-to-app: Sharing your phone screen content to another app on the same device. (Triggered
 *    from within each individual app.)
 * 2) Cast-to-other-device: Sharing your phone screen content to a different device. (Triggered from
 *    the Quick Settings Cast tile or from the Settings app.) This interactor handles both of those
 *    event types (though maybe not audio-only casting -- see b/342169876).
 */
@SysUISingleton
class MediaProjectionChipInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    mediaProjectionRepository: MediaProjectionRepository,
    val systemClock: SystemClock,
) : OngoingActivityChipInteractor {
    override val chip: StateFlow<OngoingActivityChipModel> =
        mediaProjectionRepository.mediaProjectionState
            .map { state ->
                when (state) {
                    is MediaProjectionState.NotProjecting -> OngoingActivityChipModel.Hidden
                    is MediaProjectionState.EntireScreen,
                    is MediaProjectionState.SingleTask -> {
                        // TODO(b/332662551): Distinguish between cast-to-other-device and
                        // share-to-app.
                        OngoingActivityChipModel.Shown(
                            icon =
                                Icon.Resource(
                                    R.drawable.ic_cast_connected,
                                    ContentDescription.Resource(R.string.accessibility_casting)
                                ),
                            // TODO(b/332662551): See if we can use a MediaProjection API to fetch
                            // this time.
                            startTimeMs = systemClock.elapsedRealtime()
                        ) {
                            // TODO(b/332662551): Implement the pause dialog.
                        }
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), OngoingActivityChipModel.Hidden)
}
