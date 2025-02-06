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

import com.android.systemui.communal.data.model.CommunalMediaModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Encapsulates the state of smartspace in communal. */
interface CommunalMediaRepository {
    val mediaModel: Flow<CommunalMediaModel>

    /** Start listening for media updates. */
    fun startListening()

    /** Stop listening for media updates. */
    fun stopListening()
}

@SysUISingleton
class CommunalMediaRepositoryImpl
@Inject
constructor(
    private val mediaDataManager: MediaDataManager,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) : CommunalMediaRepository, MediaDataManager.Listener {

    private val _mediaModel: MutableStateFlow<CommunalMediaModel> =
        MutableStateFlow(CommunalMediaModel.INACTIVE)

    override val mediaModel: Flow<CommunalMediaModel> =
        _mediaModel.logDiffsForTable(
            tableLogBuffer = tableLogBuffer,
            initialValue = CommunalMediaModel.INACTIVE,
        )

    override fun startListening() {
        mediaDataManager.addListener(this)
    }

    override fun stopListening() {
        mediaDataManager.removeListener(this)
    }

    override fun onMediaDataLoaded(
        key: String,
        oldKey: String?,
        data: MediaData,
        immediately: Boolean,
        receivedSmartspaceCardLatency: Int,
        isSsReactivated: Boolean
    ) {
        updateMediaModel(data)
    }

    override fun onMediaDataRemoved(key: String, userInitiated: Boolean) {
        updateMediaModel()
    }

    private fun updateMediaModel(data: MediaData? = null) {
        if (mediaDataManager.hasAnyMediaOrRecommendation()) {
            _mediaModel.value =
                CommunalMediaModel(
                    hasAnyMediaOrRecommendation = true,
                    createdTimestampMillis = data?.createdTimestampMillis ?: 0L,
                )
        } else {
            _mediaModel.value = CommunalMediaModel.INACTIVE
        }
    }
}
