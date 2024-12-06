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

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal class Output<A>(
    val context: CoroutineContext = EmptyCoroutineContext,
    val onDeath: () -> Unit = {},
    val onEmit: EvalScope.(A) -> Unit,
) {

    val schedulable = Schedulable.O(this)

    @Volatile var upstream: NodeConnection<A>? = null
    @Volatile var result: Any? = NoResult

    private object NoResult

    // invoked by network
    fun visit(evalScope: EvalScope) {
        val upstreamResult = result
        check(upstreamResult !== NoResult) { "output visited with null upstream result" }
        result = NoResult
        @Suppress("UNCHECKED_CAST") evalScope.onEmit(upstreamResult as A)
    }

    fun kill() {
        onDeath()
    }

    fun schedule(logIndent: Int, evalScope: EvalScope) {
        result =
            checkNotNull(upstream) { "output scheduled with null upstream" }
                .getPushEvent(logIndent, evalScope)
        evalScope.scheduleOutput(this)
    }
}

internal inline fun OneShot(crossinline onEmit: EvalScope.() -> Unit): Output<Unit> =
    Output<Unit>(onEmit = { onEmit() }).apply { result = Unit }
