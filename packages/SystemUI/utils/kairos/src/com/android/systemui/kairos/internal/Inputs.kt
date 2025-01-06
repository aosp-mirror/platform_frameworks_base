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
import java.util.concurrent.atomic.AtomicBoolean

internal class InputNode<A>(
    private val activate: EvalScope.() -> Unit = {},
    private val deactivate: () -> Unit = {},
) : PushNode<A> {

    private val downstreamSet = DownstreamSet()
    val activated = AtomicBoolean(false)

    private val transactionCache = TransactionCache<A>()
    private val epoch
        get() = transactionCache.epoch

    override val depthTracker: DepthTracker = DepthTracker()

    override fun hasCurrentValue(logIndent: Int, evalScope: EvalScope): Boolean =
        epoch == evalScope.epoch

    fun visit(evalScope: EvalScope, value: A) {
        transactionCache.put(evalScope, value)
        if (!scheduleAll(0, downstreamSet, evalScope)) {
            evalScope.scheduleDeactivation(this@InputNode)
        }
    }

    override fun removeDownstream(downstream: Schedulable) {
        downstreamSet.remove(downstream)
    }

    override fun deactivateIfNeeded() {
        if (downstreamSet.isEmpty() && activated.getAndSet(false)) {
            deactivate()
        }
    }

    override fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {
        if (downstreamSet.isEmpty()) {
            evalScope.scheduleDeactivation(this)
        }
    }

    override fun addDownstream(downstream: Schedulable) {
        downstreamSet.add(downstream)
    }

    fun addDownstreamAndActivateIfNeeded(downstream: Schedulable, evalScope: EvalScope) {
        val needsActivation = run {
            val wasEmpty = downstreamSet.isEmpty()
            downstreamSet.add(downstream)
            wasEmpty && !activated.getAndSet(true)
        }
        if (needsActivation) {
            activate(evalScope)
        }
    }

    override fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {
        downstreamSet.remove(downstream)
        val needsDeactivation = downstreamSet.isEmpty() && activated.getAndSet(false)
        if (needsDeactivation) {
            deactivate()
        }
    }

    override fun getPushEvent(logIndent: Int, evalScope: EvalScope): A =
        logDuration(logIndent, "Input.getPushEvent", false) {
            transactionCache.getCurrentValue(evalScope)
        }
}

internal fun <A> InputNode<A>.activated() = EventsImplCheap { downstream ->
    val input = this@activated
    addDownstreamAndActivateIfNeeded(downstream, evalScope = this)
    ActivationResult(
        connection = NodeConnection(input, input),
        needsEval = input.hasCurrentValue(0, evalScope = this),
    )
}

internal data object AlwaysNode : PushNode<Unit> {

    override val depthTracker = DepthTracker()

    override fun hasCurrentValue(logIndent: Int, evalScope: EvalScope): Boolean = true

    override fun removeDownstream(downstream: Schedulable) {}

    override fun deactivateIfNeeded() {}

    override fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {}

    override fun addDownstream(downstream: Schedulable) {}

    override fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {}

    override fun getPushEvent(logIndent: Int, evalScope: EvalScope) =
        logDuration(logIndent, "Always.getPushEvent", false) { Unit }
}
