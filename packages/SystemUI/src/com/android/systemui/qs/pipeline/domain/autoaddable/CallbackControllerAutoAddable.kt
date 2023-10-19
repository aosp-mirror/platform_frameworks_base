/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.CallbackController
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Generic [AutoAddable] for tiles that are added based on a signal from a [CallbackController]. */
abstract class CallbackControllerAutoAddable<
    Callback : Any, Controller : CallbackController<Callback>>(
    private val controller: Controller,
) : AutoAddable {

    /** [TileSpec] for the tile to add. */
    protected abstract val spec: TileSpec

    /**
     * Callback to be used to determine when to add the tile. When the callback determines that the
     * feature has been enabled, it should call [sendAdd].
     */
    protected abstract fun ProducerScope<AutoAddSignal>.getCallback(): Callback

    /** Sends an [AutoAddSignal.Add] for [spec]. */
    protected fun ProducerScope<AutoAddSignal>.sendAdd() {
        trySend(AutoAddSignal.Add(spec))
    }

    final override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return conflatedCallbackFlow {
            val callback = getCallback()
            controller.addCallback(callback)

            awaitClose { controller.removeCallback(callback) }
        }
    }

    override val autoAddTracking: AutoAddTracking
        get() = AutoAddTracking.IfNotAdded(spec)
}
