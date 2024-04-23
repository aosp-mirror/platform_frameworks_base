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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor

import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.screenrecord.domain.model.ScreenRecordTileModel
import com.android.systemui.screenrecord.RecordingController
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/** Observes screen record state changes providing the [ScreenRecordTileModel]. */
class ScreenRecordTileDataInteractor
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    private val recordingController: RecordingController,
) : QSTileDataInteractor<ScreenRecordTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<ScreenRecordTileModel> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val callback =
                    object : RecordingController.RecordingStateChangeCallback {
                        override fun onRecordingStart() {
                            trySend(ScreenRecordTileModel.Recording)
                        }
                        override fun onRecordingEnd() {
                            trySend(ScreenRecordTileModel.DoingNothing)
                        }
                        override fun onCountdown(millisUntilFinished: Long) {
                            trySend(ScreenRecordTileModel.Starting(millisUntilFinished))
                        }
                        override fun onCountdownEnd() {
                            if (
                                !recordingController.isRecording && !recordingController.isStarting
                            ) {
                                // The tile was in Starting state and got canceled before recording
                                trySend(ScreenRecordTileModel.DoingNothing)
                            }
                        }
                    }
                recordingController.addCallback(callback)
                awaitClose { recordingController.removeCallback(callback) }
            }
            .onStart { emit(generateModel()) }
            .distinctUntilChanged()
            .flowOn(bgCoroutineContext)

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)

    private fun generateModel(): ScreenRecordTileModel {
        if (recordingController.isRecording) {
            return ScreenRecordTileModel.Recording
        } else if (recordingController.isStarting) {
            return ScreenRecordTileModel.Starting(0)
        } else {
            return ScreenRecordTileModel.DoingNothing
        }
    }
}
