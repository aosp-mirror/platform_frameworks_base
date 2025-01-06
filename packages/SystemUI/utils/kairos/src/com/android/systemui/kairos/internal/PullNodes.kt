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

import com.android.systemui.kairos.internal.util.logDuration

internal val neverImpl: EventsImpl<Nothing> = EventsImplCheap { null }

internal class MapNode<A, B>(val upstream: PullNode<A>, val transform: EvalScope.(A, Int) -> B) :
    PullNode<B> {
    override fun getPushEvent(logIndent: Int, evalScope: EvalScope): B =
        logDuration(logIndent, "MapNode.getPushEvent") {
            val upstream =
                logDuration("upstream event") { upstream.getPushEvent(currentLogIndent, evalScope) }
            logDuration("transform") { evalScope.transform(upstream, currentLogIndent) }
        }
}

internal inline fun <A, B> mapImpl(
    crossinline upstream: EvalScope.() -> EventsImpl<A>,
    noinline transform: EvalScope.(A, Int) -> B,
): EventsImpl<B> = EventsImplCheap { downstream ->
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

internal class CachedNode<A>(
    private val transactionCache: TransactionCache<Lazy<A>>,
    val upstream: PullNode<A>,
) : PullNode<A> {
    override fun getPushEvent(logIndent: Int, evalScope: EvalScope): A =
        logDuration(logIndent, "CachedNode.getPushEvent") {
            val deferred =
                logDuration("CachedNode.getOrPut", false) {
                    transactionCache.getOrPut(evalScope) {
                        evalScope.deferAsync {
                            logDuration("CachedNode.getUpstreamEvent") {
                                upstream.getPushEvent(currentLogIndent, evalScope)
                            }
                        }
                    }
                }
            logDuration("await") { deferred.value }
        }
}

internal fun <A> EventsImpl<A>.cached(): EventsImpl<A> {
    val key = TransactionCache<Lazy<A>>()
    return EventsImplCheap { it ->
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
