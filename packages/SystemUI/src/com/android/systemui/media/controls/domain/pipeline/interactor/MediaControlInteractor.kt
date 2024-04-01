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

package com.android.systemui.media.controls.domain.pipeline.interactor

import com.android.internal.logging.InstanceId
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.shared.model.MediaControlModel
import com.android.systemui.media.controls.shared.model.MediaData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Encapsulates business logic for single media control. */
class MediaControlInteractor(
    instanceId: InstanceId,
    repository: MediaFilterRepository,
    private val mediaDataProcessor: MediaDataProcessor,
) {

    val mediaControl: Flow<MediaControlModel?> =
        repository.selectedUserEntries
            .map { entries -> entries[instanceId]?.let { toMediaControlModel(it) } }
            .distinctUntilChanged()

    fun removeMediaControl(key: String, delayMs: Long): Boolean {
        return mediaDataProcessor.dismissMediaData(key, delayMs)
    }

    private fun toMediaControlModel(data: MediaData): MediaControlModel {
        return with(data) {
            MediaControlModel(
                uid = appUid,
                packageName = packageName,
                instanceId = instanceId,
                token = token,
                appIcon = appIcon,
                clickIntent = clickIntent,
                appName = app,
                songName = song,
                artistName = artist,
                artwork = artwork,
                deviceData = device,
                semanticActionButtons = semanticActions,
                notificationActionButtons = actions,
                actionsToShowInCollapsed = actionsToShowInCompact,
                isResume = resumption,
                resumeProgress = resumeProgress,
            )
        }
    }
}
