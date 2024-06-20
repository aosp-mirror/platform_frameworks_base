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

package com.android.settingslib.media.session

import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.UserHandle
import androidx.concurrent.futures.DirectExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** [Flow] for [MediaSessionManager.OnActiveSessionsChangedListener]. */
val MediaSessionManager.activeMediaChanges: Flow<List<MediaController>?>
    get() =
        callbackFlow {
                val listener =
                    MediaSessionManager.OnActiveSessionsChangedListener { launch { send(it) } }
                addOnActiveSessionsChangedListener(
                    null,
                    UserHandle.of(UserHandle.myUserId()),
                    DirectExecutor.INSTANCE,
                    listener,
                )
                awaitClose { removeOnActiveSessionsChangedListener(listener) }
            }
            .buffer(capacity = Channel.CONFLATED)

/** [Flow] for [MediaSessionManager.RemoteSessionCallback]. */
val MediaSessionManager.defaultRemoteSessionChanged: Flow<MediaSession.Token?>
    get() =
        callbackFlow {
                val callback =
                    object : MediaSessionManager.RemoteSessionCallback {
                        override fun onVolumeChanged(sessionToken: MediaSession.Token, flags: Int) =
                            Unit

                        override fun onDefaultRemoteSessionChanged(
                            sessionToken: MediaSession.Token?
                        ) {
                            launch { send(sessionToken) }
                        }
                    }
                registerRemoteSessionCallback(DirectExecutor.INSTANCE, callback)
                awaitClose { unregisterRemoteSessionCallback(callback) }
            }
            .buffer(capacity = Channel.CONFLATED)
