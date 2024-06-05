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

package com.android.systemui.screenrecord.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/**
 * Repository storing information about the state of screen recording.
 *
 * Mostly a wrapper around [RecordingController] so that new screen-recording-related code can use
 * recommended architecture.
 */
interface ScreenRecordRepository {
    /** The current screen recording state. Note that this is a cold flow. */
    val screenRecordState: Flow<ScreenRecordModel>
}

@SysUISingleton
class ScreenRecordRepositoryImpl
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    private val recordingController: RecordingController,
) : ScreenRecordRepository {

    override val screenRecordState: Flow<ScreenRecordModel> =
        conflatedCallbackFlow {
                val callback =
                    object : RecordingController.RecordingStateChangeCallback {
                        override fun onRecordingStart() {
                            trySend(ScreenRecordModel.Recording)
                        }

                        override fun onRecordingEnd() {
                            trySend(ScreenRecordModel.DoingNothing)
                        }

                        override fun onCountdown(millisUntilFinished: Long) {
                            trySend(ScreenRecordModel.Starting(millisUntilFinished))
                        }

                        override fun onCountdownEnd() {
                            if (
                                !recordingController.isRecording && !recordingController.isStarting
                            ) {
                                // The recording was in Starting state but got canceled before
                                // actually starting
                                trySend(ScreenRecordModel.DoingNothing)
                            }
                        }
                    }
                recordingController.addCallback(callback)
                awaitClose { recordingController.removeCallback(callback) }
            }
            .onStart { emit(generateModel()) }
            .distinctUntilChanged()
            .flowOn(bgCoroutineContext)

    private fun generateModel(): ScreenRecordModel {
        return if (recordingController.isRecording) {
            ScreenRecordModel.Recording
        } else if (recordingController.isStarting) {
            ScreenRecordModel.Starting(0)
        } else {
            ScreenRecordModel.DoingNothing
        }
    }
}
