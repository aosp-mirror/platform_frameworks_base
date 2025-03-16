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

import com.android.systemui.kairos.internal.util.Key
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.map
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred

internal val neverImpl: TFlowImpl<Nothing> = TFlowCheap { null }

internal class MapNode<A, B>(val upstream: PullNode<A>, val transform: suspend EvalScope.(A) -> B) :
    PullNode<B> {
    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<B> =
        upstream.getPushEvent(evalScope).map { evalScope.transform(it) }
}

internal inline fun <A, B> mapImpl(
    crossinline upstream: suspend EvalScope.() -> TFlowImpl<A>,
    noinline transform: suspend EvalScope.(A) -> B,
): TFlowImpl<B> = TFlowCheap { downstream ->
    upstream().activate(evalScope = this, downstream)?.let { (connection, needsEval) ->
        ActivationResult(
            connection =
                NodeConnection(
                    directUpstream = MapNode(connection.directUpstream, transform),
                    schedulerUpstream = connection.schedulerUpstream,
                ),
            needsEval = needsEval,
        )
    }
}

internal class CachedNode<A>(val key: Key<Deferred<Maybe<A>>>, val upstream: PullNode<A>) :
    PullNode<A> {
    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<A> {
        val deferred =
            evalScope.transactionStore.getOrPut(key) {
                evalScope.deferAsync(CoroutineStart.LAZY) { upstream.getPushEvent(evalScope) }
            }
        return deferred.await()
    }
}

internal fun <A> TFlowImpl<A>.cached(): TFlowImpl<A> {
    val key = object : Key<Deferred<Maybe<A>>> {}
    return TFlowCheap {
        activate(this, it)?.let { (connection, needsEval) ->
            ActivationResult(
                connection =
                    NodeConnection(
                        directUpstream = CachedNode(key, connection.directUpstream),
                        schedulerUpstream = connection.schedulerUpstream,
                    ),
                needsEval = needsEval,
            )
        }
    }
}
