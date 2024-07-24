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

package com.android.settingslib.volume.data.repository

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** [MediaController.Callback] flow representation. */
fun MediaController.stateChanges(handler: Handler): Flow<MediaControllerChange> {
    return callbackFlow {
        val callback = MediaControllerCallbackProducer(this)
        registerCallback(callback, handler)
        awaitClose { unregisterCallback(callback) }
    }
}

/** Models particular change event received by [MediaController.Callback]. */
sealed interface MediaControllerChange {

    data object SessionDestroyed : MediaControllerChange

    data class SessionEvent(val event: String, val extras: Bundle?) : MediaControllerChange

    data class PlaybackStateChanged(val state: PlaybackState?) : MediaControllerChange

    data class MetadataChanged(val metadata: MediaMetadata?) : MediaControllerChange

    data class QueueChanged(val queue: MutableList<MediaSession.QueueItem>?) :
        MediaControllerChange

    data class QueueTitleChanged(val title: CharSequence?) : MediaControllerChange

    data class ExtrasChanged(val extras: Bundle?) : MediaControllerChange

    data class AudioInfoChanged(val info: MediaController.PlaybackInfo?) : MediaControllerChange
}

private class MediaControllerCallbackProducer(
    private val producingScope: ProducerScope<MediaControllerChange>
) : MediaController.Callback() {

    override fun onSessionDestroyed() {
        send(MediaControllerChange.SessionDestroyed)
    }

    override fun onSessionEvent(event: String, extras: Bundle?) {
        send(MediaControllerChange.SessionEvent(event, extras))
    }

    override fun onPlaybackStateChanged(state: PlaybackState?) {
        send(MediaControllerChange.PlaybackStateChanged(state))
    }

    override fun onMetadataChanged(metadata: MediaMetadata?) {
        send(MediaControllerChange.MetadataChanged(metadata))
    }

    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
        send(MediaControllerChange.QueueChanged(queue))
    }

    override fun onQueueTitleChanged(title: CharSequence?) {
        send(MediaControllerChange.QueueTitleChanged(title))
    }

    override fun onExtrasChanged(extras: Bundle?) {
        send(MediaControllerChange.ExtrasChanged(extras))
    }

    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
        send(MediaControllerChange.AudioInfoChanged(info))
    }

    private fun send(change: MediaControllerChange) {
        producingScope.launch { producingScope.send(change) }
    }
}
