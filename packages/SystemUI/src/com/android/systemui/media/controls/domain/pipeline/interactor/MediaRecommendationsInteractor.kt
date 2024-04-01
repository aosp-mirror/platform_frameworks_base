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

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.shared.model.MediaRecModel
import com.android.systemui.media.controls.shared.model.MediaRecommendationsModel
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic for media recommendation */
@SysUISingleton
class MediaRecommendationsInteractor
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    repository: MediaFilterRepository,
    private val mediaDataProcessor: MediaDataProcessor,
) {

    val recommendations: Flow<MediaRecommendationsModel> =
        repository.smartspaceMediaData.map { toRecommendationsModel(it) }.distinctUntilChanged()

    /** Indicates whether the recommendations card is active. */
    val isActive: StateFlow<Boolean> =
        repository.smartspaceMediaData
            .map { it.isActive }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), false)

    fun removeMediaRecommendations(key: String, delayMs: Long) {
        mediaDataProcessor.dismissSmartspaceRecommendation(key, delayMs)
    }

    private fun toRecommendationsModel(data: SmartspaceMediaData): MediaRecommendationsModel {
        val mediaRecs = ArrayList<MediaRecModel>()
        data.recommendations.forEach {
            with(it) { mediaRecs.add(MediaRecModel(intent, title, subtitle, icon, extras)) }
        }
        return with(data) {
            MediaRecommendationsModel(
                key = targetId,
                uid = getUid(applicationContext),
                packageName = packageName,
                instanceId = instanceId,
                appName = getAppName(applicationContext),
                dismissIntent = dismissIntent,
                areRecommendationsValid = isValid(),
                mediaRecs = mediaRecs,
            )
        }
    }
}
