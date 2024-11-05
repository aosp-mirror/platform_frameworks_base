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

package com.android.systemui.qs.tiles.impl.irecording

import android.os.UserHandle
import com.android.systemui.Flags
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.recordissue.IssueRecordingState
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

class IssueRecordingDataInteractor
@Inject
constructor(
    private val state: IssueRecordingState,
    @Background private val bgCoroutineContext: CoroutineContext,
) : QSTileDataInteractor<IssueRecordingModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<IssueRecordingModel> =
        conflatedCallbackFlow {
                val listener = Runnable { trySend(IssueRecordingModel(state.isRecording)) }
                state.addListener(listener)
                awaitClose { state.removeListener(listener) }
            }
            .onStart { emit(IssueRecordingModel(state.isRecording)) }
            .distinctUntilChanged()
            .flowOn(bgCoroutineContext)

    override fun availability(user: UserHandle): Flow<Boolean> =
        flowOf(android.os.Build.IS_DEBUGGABLE && Flags.recordIssueQsTile())
}
