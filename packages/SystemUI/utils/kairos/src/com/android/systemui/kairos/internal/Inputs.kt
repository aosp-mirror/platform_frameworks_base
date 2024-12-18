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
import com.android.systemui.kairos.util.just
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InputNode<A>(
    private val activate: suspend EvalScope.() -> Unit = {},
    private val deactivate: () -> Unit = {},
) : PushNode<A>, Key<A> {

    internal val downstreamSet = DownstreamSet()
    private val mutex = Mutex()
    private val activated = AtomicBoolean(false)

    override val depthTracker: DepthTracker = DepthTracker()

    override suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean =
        transactionStore.contains(this)

    suspend fun visit(evalScope: EvalScope, value: A) {
        evalScope.setResult(this, value)
        coroutineScope {
            if (!mutex.withLock { scheduleAll(downstreamSet, evalScope) }) {
                evalScope.scheduleDeactivation(this@InputNode)
            }
        }
    }

    override suspend fun removeDownstream(downstream: Schedulable) {
        mutex.withLock { downstreamSet.remove(downstream) }
    }

    override suspend fun deactivateIfNeeded() {
        if (mutex.withLock { downstreamSet.isEmpty() && activated.getAndSet(false) }) {
            deactivate()
        }
    }

    override suspend fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {
        if (mutex.withLock { downstreamSet.isEmpty() }) {
            evalScope.scheduleDeactivation(this)
        }
    }

    override suspend fun addDownstream(downstream: Schedulable) {
        mutex.withLock { downstreamSet.add(downstream) }
    }

    suspend fun addDownstreamAndActivateIfNeeded(downstream: Schedulable, evalScope: EvalScope) {
        val needsActivation =
            mutex.withLock {
                val wasEmpty = downstreamSet.isEmpty()
                downstreamSet.add(downstream)
                wasEmpty && !activated.getAndSet(true)
            }
        if (needsActivation) {
            activate(evalScope)
        }
    }

    override suspend fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {
        val needsDeactivation =
            mutex.withLock {
                downstreamSet.remove(downstream)
                downstreamSet.isEmpty() && activated.getAndSet(false)
            }
        if (needsDeactivation) {
            deactivate()
        }
    }

    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<A> =
        evalScope.getCurrentValue(this)
}

internal fun <A> InputNode<A>.activated() = TFlowCheap { downstream ->
    val input = this@activated
    addDownstreamAndActivateIfNeeded(downstream, evalScope = this)
    ActivationResult(connection = NodeConnection(input, input), needsEval = hasCurrentValue(input))
}

internal data object AlwaysNode : PushNode<Unit> {

    override val depthTracker = DepthTracker()

    override suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean = true

    override suspend fun removeDownstream(downstream: Schedulable) {}

    override suspend fun deactivateIfNeeded() {}

    override suspend fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {}

    override suspend fun addDownstream(downstream: Schedulable) {}

    override suspend fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {}

    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<Unit> = just(Unit)
}
