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
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionInfo
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.view.ContentRecordingSession
import android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.mediaprojection.MediaProjectionLog
import com.android.systemui.mediaprojection.MediaProjectionServiceHelper
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.taskswitcher.data.repository.TasksRepository
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class MediaProjectionManagerRepository
@Inject
constructor(
    private val mediaProjectionManager: MediaProjectionManager,
    private val displayManager: DisplayManager,
    @Main private val handler: Handler,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val tasksRepository: TasksRepository,
    private val mediaProjectionServiceHelper: MediaProjectionServiceHelper,
    @MediaProjectionLog private val logger: LogBuffer,
) : MediaProjectionRepository {

    override suspend fun switchProjectedTask(task: RunningTaskInfo) {
        withContext(backgroundDispatcher) {
            if (mediaProjectionServiceHelper.updateTaskRecordingSession(task.token)) {
                logger.log(TAG, LogLevel.DEBUG, {}, { "Successfully switched projected task" })
            } else {
                logger.log(TAG, LogLevel.WARNING, {}, { "Failed to switch projected task" })
            }
        }
    }

    override suspend fun stopProjecting() {
        withContext(backgroundDispatcher) {
            logger.log(
                TAG,
                LogLevel.DEBUG,
                {},
                { "Requesting MediaProjectionManager#stopActiveProjection" },
            )
            mediaProjectionManager.stopActiveProjection()
        }
    }

    override val mediaProjectionState: Flow<MediaProjectionState> =
        conflatedCallbackFlow {
                val callback =
                    object : MediaProjectionManager.Callback() {
                        override fun onStart(info: MediaProjectionInfo?) {
                            logger.log(
                                TAG,
                                LogLevel.DEBUG,
                                {},
                                { "MediaProjectionManager.Callback#onStart" },
                            )
                            trySendWithFailureLogging(CallbackEvent.OnStart, TAG)
                        }

                        override fun onStop(info: MediaProjectionInfo?) {
                            logger.log(
                                TAG,
                                LogLevel.DEBUG,
                                {},
                                { "MediaProjectionManager.Callback#onStop" },
                            )
                            trySendWithFailureLogging(CallbackEvent.OnStop, TAG)
                        }

                        override fun onRecordingSessionSet(
                            info: MediaProjectionInfo,
                            session: ContentRecordingSession?
                        ) {
                            logger.log(
                                TAG,
                                LogLevel.DEBUG,
                                { str1 = session.toString() },
                                { "MediaProjectionManager.Callback#onSessionStarted: $str1" },
                            )
                            trySendWithFailureLogging(
                                CallbackEvent.OnRecordingSessionSet(info, session),
                                TAG,
                            )
                        }
                    }
                mediaProjectionManager.addCallback(callback, handler)
                awaitClose { mediaProjectionManager.removeCallback(callback) }
            }
            // When we get an #onRecordingSessionSet event, we need to do some work in the
            // background before emitting the right state value. But when we get an #onStop
            // event, we immediately know what state value to emit.
            //
            // Without `mapLatest`, this could be a problem if an #onRecordingSessionSet event
            // comes in and then an #onStop event comes in shortly afterwards (b/352483752):
            // 1. #onRecordingSessionSet -> start some work in the background
            // 2. #onStop -> immediately emit "Not Projecting"
            // 3. onRecordingSessionSet work finishes -> emit "Projecting"
            //
            // At step 3, we *shouldn't* emit "Projecting" because #onStop was the last callback
            // event we received, so we should be "Not Projecting". This `mapLatest` ensures
            // that if an #onStop event comes in, we cancel any ongoing work for
            // #onRecordingSessionSet and we don't emit "Projecting".
            .mapLatest {
                when (it) {
                    is CallbackEvent.OnStart,
                    is CallbackEvent.OnStop -> MediaProjectionState.NotProjecting
                    is CallbackEvent.OnRecordingSessionSet -> stateForSession(it.info, it.session)
                }
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
        val hostDeviceName =
            withContext(backgroundDispatcher) {
                // If the projection is to a different device, then the session's display ID should
                // identify the display associated with that different device.
                displayManager.getDisplay(session.virtualDisplayId)?.name
            }

        if (session.contentToRecord == RECORD_CONTENT_DISPLAY || session.tokenToRecord == null) {
            return MediaProjectionState.Projecting.EntireScreen(hostPackage, hostDeviceName)
        }
        val matchingTask =
            tasksRepository.findRunningTaskFromWindowContainerToken(
                checkNotNull(session.tokenToRecord)
            ) ?: return MediaProjectionState.Projecting.EntireScreen(hostPackage, hostDeviceName)
        return MediaProjectionState.Projecting.SingleTask(hostPackage, hostDeviceName, matchingTask)
    }

    /**
     * Translates [MediaProjectionManager.Callback] events into objects so that we always maintain
     * the correct callback ordering.
     */
    sealed interface CallbackEvent {
        data object OnStart : CallbackEvent

        data object OnStop : CallbackEvent

        data class OnRecordingSessionSet(
            val info: MediaProjectionInfo,
            val session: ContentRecordingSession?,
        ) : CallbackEvent
    }

    companion object {
        private const val TAG = "MediaProjectionMngrRepo"
    }
}
