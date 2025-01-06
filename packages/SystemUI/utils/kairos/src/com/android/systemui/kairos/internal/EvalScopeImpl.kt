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

import com.android.systemui.kairos.DeferredValue
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.EventsInit
import com.android.systemui.kairos.EventsLoop
import com.android.systemui.kairos.State
import com.android.systemui.kairos.StateInit
import com.android.systemui.kairos.TransactionScope
import com.android.systemui.kairos.Transactional
import com.android.systemui.kairos.emptyEvents
import com.android.systemui.kairos.init
import com.android.systemui.kairos.mapCheap
import com.android.systemui.kairos.switchEvents

internal class EvalScopeImpl(networkScope: NetworkScope, deferScope: DeferScope) :
    EvalScope, NetworkScope by networkScope, DeferScope by deferScope, TransactionScope {

    override fun <A> Transactional<A>.sampleDeferred(): DeferredValue<A> =
        DeferredValue(deferAsync { impl.sample().sample(this@EvalScopeImpl).value })

    override fun <A> State<A>.sampleDeferred(): DeferredValue<A> =
        DeferredValue(
            deferAsync {
                init
                    .connect(evalScope = this@EvalScopeImpl)
                    .getCurrentWithEpoch(this@EvalScopeImpl)
                    .first
            }
        )

    override fun <R> deferredTransactionScope(block: TransactionScope.() -> R): DeferredValue<R> =
        DeferredValue(deferAsync { block() })

    override val now: Events<Unit> by lazy {
        var result by EventsLoop<Unit>()
        result =
            StateInit(
                    constInit(
                        "now",
                        activatedStateSource(
                            "now",
                            "now",
                            this,
                            { result.mapCheap { emptyEvents }.init.connect(evalScope = this) },
                            CompletableLazy(
                                EventsInit(
                                    constInit(
                                        "now",
                                        EventsImplCheap {
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
                .switchEvents()
        result
    }
}
