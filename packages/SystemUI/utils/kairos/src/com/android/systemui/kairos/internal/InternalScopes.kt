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

import com.android.systemui.kairos.FrpBuildScope
import com.android.systemui.kairos.FrpStateScope
import com.android.systemui.kairos.FrpTransactionScope
import com.android.systemui.kairos.TFlow

internal interface InitScope {
    val networkId: Any
}

internal interface EvalScope : NetworkScope, DeferScope {
    val frpScope: FrpTransactionScope

    fun <R> runInTransactionScope(block: FrpTransactionScope.() -> R): R
}

internal interface StateScope : EvalScope {
    override val frpScope: FrpStateScope

    fun <R> runInStateScope(block: FrpStateScope.() -> R): R

    val endSignal: TFlow<Any>

    fun childStateScope(newEnd: TFlow<Any>): StateScope

    val endSignalOnce: TFlow<Any>
}

internal interface BuildScope : StateScope {
    override val frpScope: FrpBuildScope

    fun <R> runInBuildScope(block: FrpBuildScope.() -> R): R
}

internal interface NetworkScope : InitScope {

    val epoch: Long
    val network: Network

    val compactor: Scheduler
    val scheduler: Scheduler

    val transactionStore: TransactionStore

    fun scheduleOutput(output: Output<*>)

    fun scheduleMuxMover(muxMover: MuxDeferredNode<*, *, *>)

    fun schedule(state: TStateSource<*>)

    fun scheduleDeactivation(node: PushNode<*>)

    fun scheduleDeactivation(output: Output<*>)
}
