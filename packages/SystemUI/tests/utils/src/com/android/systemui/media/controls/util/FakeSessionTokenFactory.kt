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
import android.media.session.MediaSession.Token
import androidx.media3.session.SessionToken

class FakeSessionTokenFactory(context: Context) : SessionTokenFactory(context) {
    private var sessionToken: SessionToken? = null

    override suspend fun createTokenFromLegacy(token: Token): SessionToken {
        return sessionToken ?: super.createTokenFromLegacy(token)
    }

    fun setMedia3SessionToken(token: SessionToken) {
        sessionToken = token
    }
}
