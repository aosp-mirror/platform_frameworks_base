/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.base.actions

import android.app.PendingIntent
import android.content.Intent
import android.view.View

/**
 * Fake implementation of [QSTileIntentUserInputHandler] interface. Consider using this alongside
 * [QSTileIntentUserInputHandlerSubject].
 */
class FakeQSTileIntentUserInputHandler : QSTileIntentUserInputHandler {

    val handledInputs: List<Input>
        get() = mutableInputs

    private val mutableInputs = mutableListOf<Input>()

    override fun handle(view: View?, intent: Intent) {
        mutableInputs.add(Input.Intent(view, intent))
    }

    override fun handle(
        view: View?,
        pendingIntent: PendingIntent,
        requestLaunchingDefaultActivity: Boolean
    ) {
        mutableInputs.add(Input.PendingIntent(view, pendingIntent, requestLaunchingDefaultActivity))
    }

    sealed interface Input {
        data class Intent(val view: View?, val intent: android.content.Intent) : Input
        data class PendingIntent(
            val view: View?,
            val pendingIntent: android.app.PendingIntent,
            val requestLaunchingDefaultActivity: Boolean
        ) : Input
    }
}

val FakeQSTileIntentUserInputHandler.intentInputs
    get() = handledInputs.mapNotNull { it as? FakeQSTileIntentUserInputHandler.Input.Intent }
val FakeQSTileIntentUserInputHandler.pendingIntentInputs
    get() = handledInputs.mapNotNull { it as? FakeQSTileIntentUserInputHandler.Input.PendingIntent }
