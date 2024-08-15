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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.media.session.MediaController
import android.media.session.PlaybackState
import com.android.settingslib.volume.data.repository.MediaControllerRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaControllerChangeModel
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Allows to observe and change [MediaDeviceSession] state. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class MediaDeviceSessionInteractor
@Inject
constructor(
    @Background private val backgroundCoroutineContext: CoroutineContext,
    private val mediaControllerInteractor: MediaControllerInteractor,
    private val mediaControllerRepository: MediaControllerRepository,
) {

    /** [PlaybackState] changes for the [MediaDeviceSession]. */
    fun playbackState(session: MediaDeviceSession): Flow<PlaybackState?> {
        return stateChanges(session) {
                emit(MediaControllerChangeModel.PlaybackStateChanged(it.playbackState))
            }
            .filterIsInstance(MediaControllerChangeModel.PlaybackStateChanged::class)
            .map { it.state }
    }

    /** [MediaController.PlaybackInfo] changes for the [MediaDeviceSession]. */
    fun playbackInfo(session: MediaDeviceSession): Flow<MediaController.PlaybackInfo> {
        return stateChanges(session) {
                emit(MediaControllerChangeModel.AudioInfoChanged(it.playbackInfo))
            }
            .filterIsInstance(MediaControllerChangeModel.AudioInfoChanged::class)
            .map { it.info }
    }

    private fun stateChanges(
        session: MediaDeviceSession,
        onStart:
            suspend FlowCollector<MediaControllerChangeModel>.(controller: MediaController) -> Unit,
    ): Flow<MediaControllerChangeModel?> =
        mediaControllerRepository.activeSessions
            .flatMapLatest { controllers ->
                val controller: MediaController =
                    findControllerForSession(controllers, session)
                        ?: return@flatMapLatest flowOf(null)
                mediaControllerInteractor.stateChanges(controller).onStart { onStart(controller) }
            }
            .flowOn(backgroundCoroutineContext)

    /** Set [MediaDeviceSession] volume to [volume]. */
    suspend fun setSessionVolume(mediaDeviceSession: MediaDeviceSession, volume: Int): Boolean {
        if (!mediaDeviceSession.canAdjustVolume) {
            return false
        }
        return withContext(backgroundCoroutineContext) {
            val controller =
                findControllerForSession(
                    mediaControllerRepository.activeSessions.value,
                    mediaDeviceSession,
                )
            if (controller == null) {
                false
            } else {
                controller.setVolumeTo(volume, 0)
                true
            }
        }
    }

    private fun findControllerForSession(
        controllers: Collection<MediaController>,
        mediaDeviceSession: MediaDeviceSession,
    ): MediaController? =
        controllers.firstOrNull { it.sessionToken == mediaDeviceSession.sessionToken }
}
