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

package com.android.systemui.statusbar.featurepods.media.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for managing the state of the media control chip in the status bar.
 *
 * Provides a [StateFlow] of [MediaControlChipModel] representing the current state of the media
 * control chip. Emits a new [MediaControlChipModel] when there is an active media session and the
 * corresponding user preference is found, otherwise emits null.
 */
@SysUISingleton
class MediaControlChipInteractor
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    mediaFilterRepository: MediaFilterRepository,
) {
    private val currentMediaControls: StateFlow<List<MediaCommonModel.MediaControl>> =
        mediaFilterRepository.currentMedia
            .map { mediaList -> mediaList.filterIsInstance<MediaCommonModel.MediaControl>() }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    /** The currently active [MediaControlChipModel] */
    val mediaControlModel: StateFlow<MediaControlChipModel?> =
        combine(currentMediaControls, mediaFilterRepository.selectedUserEntries) {
                mediaControls,
                userEntries ->
                mediaControls
                    .mapNotNull { userEntries[it.mediaLoadedModel.instanceId] }
                    .firstOrNull { it.active }
                    ?.toMediaControlChipModel()
            }
            .stateIn(
                scope = backgroundScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null,
            )
}

private fun MediaData.toMediaControlChipModel(): MediaControlChipModel {
    return MediaControlChipModel(
        appIcon = this.appIcon,
        appName = this.app,
        songName = this.song,
        playOrPause = this.semanticActions?.getActionById(R.id.actionPlayPause),
    )
}
