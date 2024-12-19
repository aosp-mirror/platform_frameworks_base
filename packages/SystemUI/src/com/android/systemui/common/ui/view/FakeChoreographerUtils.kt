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

package com.android.systemui.common.ui.view

import android.view.View
import kotlinx.coroutines.CompletableDeferred

class FakeChoreographerUtils : ChoreographerUtils {

    private var pendingDeferred: CompletableDeferred<Unit>? = null

    override suspend fun waitUntilNextDoFrameDone(view: View) {
        getDeferred().await()
        clearDeferred()
    }

    /**
     * Called from tests when it's time to complete the doFrame. It works also if it's called before
     * [waitUntilNextDoFrameDone].
     */
    fun completeDoFrame() {
        getDeferred().complete(Unit)
    }

    @Synchronized
    private fun getDeferred(): CompletableDeferred<Unit> {
        if (pendingDeferred == null) {
            pendingDeferred = CompletableDeferred()
        }
        return pendingDeferred!!
    }

    @Synchronized
    private fun clearDeferred() {
        pendingDeferred = null
    }
}
