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

import com.android.systemui.kairos.util.Maybe

/* Initialized TFlow */
internal fun interface TFlowImpl<out A> {
    suspend fun activate(evalScope: EvalScope, downstream: Schedulable): ActivationResult<A>?
}

internal data class ActivationResult<out A>(
    val connection: NodeConnection<A>,
    val needsEval: Boolean,
)

internal inline fun <A> TFlowCheap(crossinline cheap: CheapNodeSubscribe<A>) =
    TFlowImpl { scope, ds ->
        scope.cheap(ds)
    }

internal typealias CheapNodeSubscribe<A> =
    suspend EvalScope.(downstream: Schedulable) -> ActivationResult<A>?

internal data class NodeConnection<out A>(
    val directUpstream: PullNode<A>,
    val schedulerUpstream: PushNode<*>,
)

internal suspend fun <A> NodeConnection<A>.hasCurrentValue(
    transactionStore: TransactionStore
): Boolean = schedulerUpstream.hasCurrentValue(transactionStore)

internal suspend fun <A> NodeConnection<A>.removeDownstreamAndDeactivateIfNeeded(
    downstream: Schedulable
) = schedulerUpstream.removeDownstreamAndDeactivateIfNeeded(downstream)

internal suspend fun <A> NodeConnection<A>.scheduleDeactivationIfNeeded(evalScope: EvalScope) =
    schedulerUpstream.scheduleDeactivationIfNeeded(evalScope)

internal suspend fun <A> NodeConnection<A>.removeDownstream(downstream: Schedulable) =
    schedulerUpstream.removeDownstream(downstream)

internal suspend fun <A> NodeConnection<A>.getPushEvent(evalScope: EvalScope): Maybe<A> =
    directUpstream.getPushEvent(evalScope)

internal val <A> NodeConnection<A>.depthTracker: DepthTracker
    get() = schedulerUpstream.depthTracker
