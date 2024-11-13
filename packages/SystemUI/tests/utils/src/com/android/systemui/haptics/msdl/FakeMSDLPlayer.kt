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

package com.android.systemui.haptics.msdl

import com.google.android.msdl.data.model.FeedbackLevel
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.InteractionProperties
import com.google.android.msdl.domain.MSDLPlayer
import com.google.android.msdl.logging.MSDLEvent

class FakeMSDLPlayer : MSDLPlayer {
    private val history = arrayListOf<MSDLEvent>()
    var currentFeedbackLevel = FeedbackLevel.DEFAULT
    var latestTokenPlayed: MSDLToken? = null
        private set

    var latestPropertiesPlayed: InteractionProperties? = null
        private set

    override fun getSystemFeedbackLevel(): FeedbackLevel = currentFeedbackLevel

    override fun playToken(token: MSDLToken, properties: InteractionProperties?) {
        latestTokenPlayed = token
        latestPropertiesPlayed = properties
        history.add(MSDLEvent(token, properties))
    }

    override fun getHistory(): List<MSDLEvent> = history
}
