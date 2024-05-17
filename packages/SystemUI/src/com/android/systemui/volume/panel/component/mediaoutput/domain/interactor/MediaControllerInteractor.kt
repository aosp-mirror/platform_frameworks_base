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

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaControllerChangeModel
import javax.inject.Inject
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

interface MediaControllerInteractor {

    /** [MediaController.Callback] flow representation. */
    fun stateChanges(mediaController: MediaController): Flow<MediaControllerChangeModel>
}

@SysUISingleton
class MediaControllerInteractorImpl
@Inject
constructor(
    @Background private val backgroundHandler: Handler,
) : MediaControllerInteractor {

    override fun stateChanges(mediaController: MediaController): Flow<MediaControllerChangeModel> {
        return conflatedCallbackFlow {
            val callback = MediaControllerCallbackProducer(this)
            mediaController.registerCallback(callback, backgroundHandler)
            awaitClose { mediaController.unregisterCallback(callback) }
        }
    }
}

private class MediaControllerCallbackProducer(
    private val producingScope: ProducerScope<MediaControllerChangeModel>
) : MediaController.Callback() {

    override fun onSessionDestroyed() {
        send(MediaControllerChangeModel.SessionDestroyed)
    }

    override fun onSessionEvent(event: String, extras: Bundle?) {
        send(MediaControllerChangeModel.SessionEvent(event, extras))
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        send(MediaControllerChangeModel.PlaybackStateChanged(state))
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        send(MediaControllerChangeModel.MetadataChanged(metadata))
    }

    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
        send(MediaControllerChangeModel.QueueChanged(queue))
    }

    override fun onQueueTitleChanged(title: CharSequence?) {
        send(MediaControllerChangeModel.QueueTitleChanged(title))
    }

    override fun onExtrasChanged(extras: Bundle?) {
        send(MediaControllerChangeModel.ExtrasChanged(extras))
    }

    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
        send(MediaControllerChangeModel.AudioInfoChanged(info))
    }

    private fun send(change: MediaControllerChangeModel) {
        producingScope.launch { producingScope.send(change) }
    }
}
