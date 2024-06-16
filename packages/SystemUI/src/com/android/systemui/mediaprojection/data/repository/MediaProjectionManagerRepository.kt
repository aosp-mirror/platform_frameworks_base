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

package com.android.systemui.mediaprojection.data.repository

import android.app.ActivityManager.RunningTaskInfo
import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.util.Log
import android.view.ContentRecordingSession
import android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.mediaprojection.MediaProjectionServiceHelper
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.taskswitcher.data.repository.TasksRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class MediaProjectionManagerRepository
@Inject
constructor(
    private val mediaProjectionManager: MediaProjectionManager,
    @Main private val handler: Handler,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val tasksRepository: TasksRepository,
    private val mediaProjectionServiceHelper: MediaProjectionServiceHelper,
) : MediaProjectionRepository {

    override suspend fun switchProjectedTask(task: RunningTaskInfo) {
        withContext(backgroundDispatcher) {
            if (mediaProjectionServiceHelper.updateTaskRecordingSession(task.token)) {
                Log.d(TAG, "Successfully switched projected task")
            } else {
                Log.d(TAG, "Failed to switch projected task")
            }
        }
    }

    override suspend fun stopProjecting() {
        withContext(backgroundDispatcher) { mediaProjectionManager.stopActiveProjection() }
    }

    override val mediaProjectionState: Flow<MediaProjectionState> =
        conflatedCallbackFlow {
                val callback =
                    object : MediaProjectionManager.Callback() {
                        override fun onStart(info: MediaProjectionInfo?) {
                            Log.d(TAG, "MediaProjectionManager.Callback#onStart")
                            trySendWithFailureLogging(MediaProjectionState.NotProjecting, TAG)
                        }

                        override fun onStop(info: MediaProjectionInfo?) {
                            Log.d(TAG, "MediaProjectionManager.Callback#onStop")
                            trySendWithFailureLogging(MediaProjectionState.NotProjecting, TAG)
                        }

                        override fun onRecordingSessionSet(
                            info: MediaProjectionInfo,
                            session: ContentRecordingSession?
                        ) {
                            Log.d(TAG, "MediaProjectionManager.Callback#onSessionStarted: $session")
                            launch {
                                trySendWithFailureLogging(stateForSession(info, session), TAG)
                            }
                        }
                    }
                mediaProjectionManager.addCallback(callback, handler)
                awaitClose { mediaProjectionManager.removeCallback(callback) }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                initialValue = MediaProjectionState.NotProjecting,
            )

    private suspend fun stateForSession(
        info: MediaProjectionInfo,
        session: ContentRecordingSession?
    ): MediaProjectionState {
        if (session == null) {
            return MediaProjectionState.NotProjecting
        }

        val hostPackage = info.packageName
        if (session.contentToRecord == RECORD_CONTENT_DISPLAY || session.tokenToRecord == null) {
            return MediaProjectionState.Projecting.EntireScreen(hostPackage)
        }
        val matchingTask =
            tasksRepository.findRunningTaskFromWindowContainerToken(
                checkNotNull(session.tokenToRecord)
            ) ?: return MediaProjectionState.Projecting.EntireScreen(hostPackage)
        return MediaProjectionState.Projecting.SingleTask(hostPackage, matchingTask)
    }

    companion object {
        private const val TAG = "MediaProjectionMngrRepo"
    }
}
