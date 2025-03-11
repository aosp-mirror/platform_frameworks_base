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

package com.android.systemui.kairos.internal

import com.android.systemui.kairos.util.Just
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class Output<A>(
    val context: CoroutineContext = EmptyCoroutineContext,
    val onDeath: suspend () -> Unit = {},
    val onEmit: suspend EvalScope.(A) -> Unit,
) {

    val schedulable = Schedulable.O(this)

    @Volatile var upstream: NodeConnection<A>? = null
    @Volatile var result: Any? = NoResult

    private object NoResult

    // invoked by network
    suspend fun visit(evalScope: EvalScope) {
        val upstreamResult = result
        check(upstreamResult !== NoResult) { "output visited with null upstream result" }
        result = null
        @Suppress("UNCHECKED_CAST") evalScope.onEmit(upstreamResult as A)
    }

    suspend fun kill() {
        onDeath()
    }

    suspend fun schedule(evalScope: EvalScope) {
        val upstreamResult =
            checkNotNull(upstream) { "output scheduled with null upstream" }.getPushEvent(evalScope)
        if (upstreamResult is Just) {
            result = upstreamResult.value
            evalScope.scheduleOutput(this)
        }
    }
}

internal inline fun OneShot(crossinline onEmit: suspend EvalScope.() -> Unit): Output<Unit> =
    Output<Unit>(onEmit = { onEmit() }).apply { result = Unit }
