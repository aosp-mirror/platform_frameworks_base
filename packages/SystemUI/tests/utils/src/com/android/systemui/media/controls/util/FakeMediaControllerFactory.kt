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

package com.android.systemui.media.controls.util

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession.Token
import android.os.Looper
import androidx.media3.session.MediaController as Media3Controller
import androidx.media3.session.SessionToken

class FakeMediaControllerFactory(context: Context) : MediaControllerFactory(context) {

    private val mediaControllersForToken = mutableMapOf<Token, MediaController>()
    private var media3Controller: Media3Controller? = null

    override fun create(token: Token): MediaController {
        if (token !in mediaControllersForToken) {
            super.create(token)
        }
        return mediaControllersForToken[token]!!
    }

    override suspend fun create(token: SessionToken, looper: Looper): Media3Controller {
        return media3Controller ?: super.create(token, looper)
    }

    fun setControllerForToken(token: Token, mediaController: MediaController) {
        mediaControllersForToken[token] = mediaController
    }

    fun setMedia3Controller(mediaController: Media3Controller) {
        media3Controller = mediaController
    }
}
