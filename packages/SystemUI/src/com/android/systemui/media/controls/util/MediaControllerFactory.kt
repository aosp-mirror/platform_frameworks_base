/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.media.controls.util

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Looper
import androidx.concurrent.futures.await
import androidx.media3.session.MediaController as Media3Controller
import androidx.media3.session.SessionToken
import javax.inject.Inject

/** Testable wrapper for media controller construction */
open class MediaControllerFactory @Inject constructor(private val context: Context) {
    /**
     * Creates a new [MediaController] from the framework session token.
     *
     * @param token The token for the session. This value must never be null.
     */
    open fun create(token: MediaSession.Token): MediaController {
        return MediaController(context, token)
    }

    /**
     * Creates a new [Media3Controller] from the media3 [SessionToken].
     *
     * @param token The token for the session
     * @param looper The looper that will be used for this controller's operations
     */
    open suspend fun create(token: SessionToken, looper: Looper): Media3Controller {
        return Media3Controller.Builder(context, token)
            .setApplicationLooper(looper)
            .buildAsync()
            .await()
    }
}
