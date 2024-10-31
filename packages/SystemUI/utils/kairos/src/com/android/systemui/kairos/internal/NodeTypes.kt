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

/*
Dmux
Muxes + Branch
*/
internal sealed interface SchedulableNode {
    /** schedule this node w/ given NodeEvalScope */
    suspend fun schedule(evalScope: EvalScope)

    suspend fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int)

    suspend fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *>>,
        newDirectDepth: Int,
    )

    suspend fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    )

    suspend fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDirectDepth: Int,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    )

    suspend fun removeIndirectUpstream(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    )

    suspend fun removeDirectUpstream(scheduler: Scheduler, depth: Int)
}

/*
All but Dmux
 */
internal sealed interface PullNode<out A> {
    /**
     * query the result of this node within the current transaction. if the node is cached, this
     * will read from the cache, otherwise it will perform a full evaluation, even if invoked
     * multiple times within a transaction.
     */
    suspend fun getPushEvent(evalScope: EvalScope): Maybe<A>
}

/*
Muxes + DmuxBranch
 */
internal sealed interface PushNode<A> : PullNode<A> {

    suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean

    val depthTracker: DepthTracker

    suspend fun removeDownstream(downstream: Schedulable)

    /** called during cleanup phase */
    suspend fun deactivateIfNeeded()

    /** called from mux nodes after severs */
    suspend fun scheduleDeactivationIfNeeded(evalScope: EvalScope)

    suspend fun addDownstream(downstream: Schedulable)

    suspend fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable)
}
