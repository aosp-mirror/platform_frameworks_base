/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.models.player.MediaData
import com.android.systemui.media.controls.pipeline.MediaDataManager
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** Encapsulates the state of smartspace in communal. */
interface CommunalMediaRepository {
    val mediaPlaying: Flow<Boolean>
}

@SysUISingleton
class CommunalMediaRepositoryImpl
@Inject
constructor(
    private val mediaDataManager: MediaDataManager,
) : CommunalMediaRepository {

    private val mediaDataListener =
        object : MediaDataManager.Listener {
            override fun onMediaDataLoaded(
                key: String,
                oldKey: String?,
                data: MediaData,
                immediately: Boolean,
                receivedSmartspaceCardLatency: Int,
                isSsReactivated: Boolean
            ) {
                if (!mediaDataManager.hasAnyMediaOrRecommendation()) {
                    return
                }
                _mediaPlaying.value = true
            }

            override fun onMediaDataRemoved(key: String) {
                if (mediaDataManager.hasAnyMediaOrRecommendation()) {
                    return
                }
                _mediaPlaying.value = false
            }
        }

    private val _mediaPlaying: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override val mediaPlaying: Flow<Boolean> =
        _mediaPlaying
            .onStart {
                mediaDataManager.addListener(mediaDataListener)
                _mediaPlaying.value = mediaDataManager.hasAnyMediaOrRecommendation()
            }
            .onCompletion { mediaDataManager.removeListener(mediaDataListener) }
}
