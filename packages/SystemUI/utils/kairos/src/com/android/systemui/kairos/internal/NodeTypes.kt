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

/*
Dmux
Muxes + Branch
*/
internal sealed interface SchedulableNode {
    /** schedule this node w/ given NodeEvalScope */
    fun schedule(logIndent: Int, evalScope: EvalScope)

    fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int)

    fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *, *>>,
        newDirectDepth: Int,
    )

    fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *, *>>,
        additions: Set<MuxDeferredNode<*, *, *>>,
    )

    fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDirectDepth: Int,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *, *>>,
    )

    fun removeIndirectUpstream(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *, *>>,
    )

    fun removeDirectUpstream(scheduler: Scheduler, depth: Int)
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
    fun getPushEvent(logIndent: Int, evalScope: EvalScope): A
}

/*
Muxes + DmuxBranch
 */
internal sealed interface PushNode<A> : PullNode<A> {

    fun hasCurrentValue(logIndent: Int, evalScope: EvalScope): Boolean

    val depthTracker: DepthTracker

    fun removeDownstream(downstream: Schedulable)

    /** called during cleanup phase */
    fun deactivateIfNeeded()

    /** called from mux nodes after severs */
    fun scheduleDeactivationIfNeeded(evalScope: EvalScope)

    fun addDownstream(downstream: Schedulable)

    fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable)
}
