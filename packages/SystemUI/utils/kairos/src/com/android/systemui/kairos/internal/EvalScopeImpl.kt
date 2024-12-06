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

import com.android.systemui.kairos.FrpDeferredValue
import com.android.systemui.kairos.FrpTransactionScope
import com.android.systemui.kairos.TFlow
import com.android.systemui.kairos.TFlowInit
import com.android.systemui.kairos.TFlowLoop
import com.android.systemui.kairos.TState
import com.android.systemui.kairos.TStateInit
import com.android.systemui.kairos.Transactional
import com.android.systemui.kairos.emptyTFlow
import com.android.systemui.kairos.init
import com.android.systemui.kairos.mapCheap
import com.android.systemui.kairos.switch

internal class EvalScopeImpl(networkScope: NetworkScope, deferScope: DeferScope) :
    EvalScope, NetworkScope by networkScope, DeferScope by deferScope {

    private fun <A> Transactional<A>.sample(): A = impl.sample().sample(this@EvalScopeImpl).value

    private fun <A> TState<A>.sample(): A =
        init.connect(evalScope = this@EvalScopeImpl).getCurrentWithEpoch(this@EvalScopeImpl).first

    private val <A> Transactional<A>.deferredValue: FrpDeferredValue<A>
        get() = FrpDeferredValue(deferAsync { sample() })

    private val <A> TState<A>.deferredValue: FrpDeferredValue<A>
        get() = FrpDeferredValue(deferAsync { sample() })

    private val nowInternal: TFlow<Unit> by lazy {
        var result by TFlowLoop<Unit>()
        result =
            TStateInit(
                    constInit(
                        "now",
                        activatedTStateSource(
                            "now",
                            "now",
                            this,
                            { result.mapCheap { emptyTFlow }.init.connect(evalScope = this) },
                            CompletableLazy(
                                TFlowInit(
                                    constInit(
                                        "now",
                                        TFlowCheap {
                                            ActivationResult(
                                                connection = NodeConnection(AlwaysNode, AlwaysNode),
                                                needsEval = true,
                                            )
                                        },
                                    )
                                )
                            ),
                        ),
                    )
                )
                .switch()
        result
    }

    private fun <R> deferredInternal(block: FrpTransactionScope.() -> R): FrpDeferredValue<R> =
        FrpDeferredValue(deferAsync { runInTransactionScope(block) })

    override fun <R> runInTransactionScope(block: FrpTransactionScope.() -> R): R = frpScope.block()

    override val frpScope: FrpTransactionScope = FrpTransactionScopeImpl()

    inner class FrpTransactionScopeImpl : FrpTransactionScope {
        override fun <A> Transactional<A>.sampleDeferred(): FrpDeferredValue<A> = deferredValue

        override fun <A> TState<A>.sampleDeferred(): FrpDeferredValue<A> = deferredValue

        override fun <R> deferredTransactionScope(
            block: FrpTransactionScope.() -> R
        ): FrpDeferredValue<R> = deferredInternal(block)

        override val now: TFlow<Unit>
            get() = nowInternal
    }
}
