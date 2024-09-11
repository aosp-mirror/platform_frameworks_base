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

package com.android.systemui.volume.panel.component.mediaoutput.domain.model

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle

/** Models particular change event received by [MediaController.Callback]. */
sealed interface MediaControllerChangeModel {

    data object SessionDestroyed : MediaControllerChangeModel

    data class SessionEvent(val event: String, val extras: Bundle?) : MediaControllerChangeModel

    data class PlaybackStateChanged(val state: PlaybackState?) : MediaControllerChangeModel

    data class MetadataChanged(val metadata: MediaMetadata?) : MediaControllerChangeModel

    data class QueueChanged(val queue: MutableList<MediaSession.QueueItem>?) :
        MediaControllerChangeModel

    data class QueueTitleChanged(val title: CharSequence?) : MediaControllerChangeModel

    data class ExtrasChanged(val extras: Bundle?) : MediaControllerChangeModel

    data class AudioInfoChanged(val info: MediaController.PlaybackInfo) :
        MediaControllerChangeModel
}
