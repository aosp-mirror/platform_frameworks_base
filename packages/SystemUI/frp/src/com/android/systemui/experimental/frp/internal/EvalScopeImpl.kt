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

package com.android.systemui.experimental.frp.internal

import com.android.systemui.experimental.frp.FrpDeferredValue
import com.android.systemui.experimental.frp.FrpTransactionScope
import com.android.systemui.experimental.frp.TFlow
import com.android.systemui.experimental.frp.TFlowInit
import com.android.systemui.experimental.frp.TFlowLoop
import com.android.systemui.experimental.frp.TState
import com.android.systemui.experimental.frp.TStateInit
import com.android.systemui.experimental.frp.Transactional
import com.android.systemui.experimental.frp.emptyTFlow
import com.android.systemui.experimental.frp.init
import com.android.systemui.experimental.frp.mapCheap
import com.android.systemui.experimental.frp.switch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.job

internal class EvalScopeImpl(networkScope: NetworkScope, deferScope: DeferScope) :
    EvalScope, NetworkScope by networkScope, DeferScope by deferScope {

    private suspend fun <A> Transactional<A>.sample(): A =
        impl.sample().sample(this@EvalScopeImpl).await()

    private suspend fun <A> TState<A>.sample(): A =
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
                        mkState(
                            "now",
                            "now",
                            this,
                            { result.mapCheap { emptyTFlow }.init.connect(evalScope = this) },
                            CompletableDeferred(
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

    private fun <R> deferredInternal(
        block: suspend FrpTransactionScope.() -> R
    ): FrpDeferredValue<R> = FrpDeferredValue(deferAsync { runInTransactionScope(block) })

    override suspend fun <R> runInTransactionScope(block: suspend FrpTransactionScope.() -> R): R {
        val complete = CompletableDeferred<R>(parent = coroutineContext.job)
        block.startCoroutine(
            frpScope,
            object : Continuation<R> {
                override val context: CoroutineContext
                    get() = EmptyCoroutineContext

                override fun resumeWith(result: Result<R>) {
                    complete.completeWith(result)
                }
            },
        )
        return complete.await()
    }

    override val frpScope: FrpTransactionScope = FrpTransactionScopeImpl()

    inner class FrpTransactionScopeImpl : FrpTransactionScope {
        override fun <A> Transactional<A>.sampleDeferred(): FrpDeferredValue<A> = deferredValue

        override fun <A> TState<A>.sampleDeferred(): FrpDeferredValue<A> = deferredValue

        override fun <R> deferredTransactionScope(
            block: suspend FrpTransactionScope.() -> R
        ): FrpDeferredValue<R> = deferredInternal(block)

        override val now: TFlow<Unit>
            get() = nowInternal
    }
}
