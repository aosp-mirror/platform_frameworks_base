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

package com.android.systemui.kairos

import com.android.systemui.kairos.internal.BuildScopeImpl
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.StateScopeImpl
import com.android.systemui.kairos.internal.util.awaitCancellationAndThen
import com.android.systemui.kairos.internal.util.childScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Marks declarations that are still **experimental** and shouldn't be used in general production
 * code.
 */
@RequiresOptIn(
    message = "This API is experimental and should not be used in general production code."
)
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalFrpApi

/**
 * External interface to an FRP network. Can be used to make transactional queries and modifications
 * to the network.
 */
@ExperimentalFrpApi
interface FrpNetwork {
    /**
     * Runs [block] inside of a transaction, suspending until the transaction is complete.
     *
     * The [FrpBuildScope] receiver exposes methods that can be used to query or modify the network.
     * If the network is cancelled while the caller of [transact] is suspended, then the call will
     * be cancelled.
     */
    @ExperimentalFrpApi suspend fun <R> transact(block: suspend FrpTransactionScope.() -> R): R

    /**
     * Activates [spec] in a transaction, suspending indefinitely. While suspended, all observers
     * and long-running effects are kept alive. When cancelled, observers are unregistered and
     * effects are cancelled.
     */
    @ExperimentalFrpApi suspend fun activateSpec(spec: FrpSpec<*>)

    /** Returns a [CoalescingMutableTFlow] that can emit values into this [FrpNetwork]. */
    @ExperimentalFrpApi
    fun <In, Out> coalescingMutableTFlow(
        coalesce: (old: Out, new: In) -> Out,
        getInitialValue: () -> Out,
    ): CoalescingMutableTFlow<In, Out>

    /** Returns a [MutableTFlow] that can emit values into this [FrpNetwork]. */
    @ExperimentalFrpApi fun <T> mutableTFlow(): MutableTFlow<T>

    /** Returns a [MutableTState]. with initial state [initialValue]. */
    @ExperimentalFrpApi
    fun <T> mutableTStateDeferred(initialValue: FrpDeferredValue<T>): MutableTState<T>
}

/** Returns a [CoalescingMutableTFlow] that can emit values into this [FrpNetwork]. */
@ExperimentalFrpApi
fun <In, Out> FrpNetwork.coalescingMutableTFlow(
    coalesce: (old: Out, new: In) -> Out,
    initialValue: Out,
): CoalescingMutableTFlow<In, Out> =
    coalescingMutableTFlow(coalesce, getInitialValue = { initialValue })

/** Returns a [MutableTState]. with initial state [initialValue]. */
@ExperimentalFrpApi
fun <T> FrpNetwork.mutableTState(initialValue: T): MutableTState<T> =
    mutableTStateDeferred(deferredOf(initialValue))

/** Returns a [MutableTState]. with initial state [initialValue]. */
@ExperimentalFrpApi
fun <T> MutableTState(network: FrpNetwork, initialValue: T): MutableTState<T> =
    network.mutableTState(initialValue)

/** Returns a [MutableTFlow] that can emit values into this [FrpNetwork]. */
@ExperimentalFrpApi
fun <T> MutableTFlow(network: FrpNetwork): MutableTFlow<T> = network.mutableTFlow()

/** Returns a [CoalescingMutableTFlow] that can emit values into this [FrpNetwork]. */
@ExperimentalFrpApi
fun <In, Out> CoalescingMutableTFlow(
    network: FrpNetwork,
    coalesce: (old: Out, new: In) -> Out,
    initialValue: Out,
): CoalescingMutableTFlow<In, Out> = network.coalescingMutableTFlow(coalesce) { initialValue }

/** Returns a [CoalescingMutableTFlow] that can emit values into this [FrpNetwork]. */
@ExperimentalFrpApi
fun <In, Out> CoalescingMutableTFlow(
    network: FrpNetwork,
    coalesce: (old: Out, new: In) -> Out,
    getInitialValue: () -> Out,
): CoalescingMutableTFlow<In, Out> = network.coalescingMutableTFlow(coalesce, getInitialValue)

/**
 * Activates [spec] in a transaction and invokes [block] with the result, suspending indefinitely.
 * While suspended, all observers and long-running effects are kept alive. When cancelled, observers
 * are unregistered and effects are cancelled.
 */
@ExperimentalFrpApi
suspend fun <R> FrpNetwork.activateSpec(spec: FrpSpec<R>, block: suspend (R) -> Unit) {
    activateSpec {
        val result = spec.applySpec()
        launchEffect { block(result) }
    }
}

internal class LocalFrpNetwork(
    private val network: Network,
    private val scope: CoroutineScope,
    private val endSignal: TFlow<Any>,
) : FrpNetwork {
    override suspend fun <R> transact(block: suspend FrpTransactionScope.() -> R): R {
        val result = CompletableDeferred<R>(coroutineContext[Job])
        @Suppress("DeferredResultUnused")
        network.transaction {
            val buildScope =
                BuildScopeImpl(
                    stateScope = StateScopeImpl(evalScope = this, endSignal = endSignal),
                    coroutineScope = scope,
                )
            buildScope.runInBuildScope { effect { result.complete(block()) } }
        }
        return result.await()
    }

    override suspend fun activateSpec(spec: FrpSpec<*>) {
        val job =
            network
                .transaction {
                    val buildScope =
                        BuildScopeImpl(
                            stateScope = StateScopeImpl(evalScope = this, endSignal = endSignal),
                            coroutineScope = scope,
                        )
                    buildScope.runInBuildScope { launchScope(spec) }
                }
                .await()
        awaitCancellationAndThen { job.cancel() }
    }

    override fun <In, Out> coalescingMutableTFlow(
        coalesce: (old: Out, new: In) -> Out,
        getInitialValue: () -> Out,
    ): CoalescingMutableTFlow<In, Out> = CoalescingMutableTFlow(coalesce, network, getInitialValue)

    override fun <T> mutableTFlow(): MutableTFlow<T> = MutableTFlow(network)

    override fun <T> mutableTStateDeferred(initialValue: FrpDeferredValue<T>): MutableTState<T> =
        MutableTState(network, initialValue.unwrapped)
}

/**
 * Combination of an [FrpNetwork] and a [Job] that, when cancelled, will cancel the entire FRP
 * network.
 */
@ExperimentalFrpApi
class RootFrpNetwork
internal constructor(private val network: Network, private val scope: CoroutineScope, job: Job) :
    Job by job, FrpNetwork by LocalFrpNetwork(network, scope, emptyTFlow)

/** Constructs a new [RootFrpNetwork] in the given [CoroutineScope]. */
@ExperimentalFrpApi
fun CoroutineScope.newFrpNetwork(
    context: CoroutineContext = EmptyCoroutineContext
): RootFrpNetwork {
    val scope = childScope(context)
    val network = Network(scope)
    scope.launch(CoroutineName("newFrpNetwork scheduler")) { network.runInputScheduler() }
    return RootFrpNetwork(network, scope, scope.coroutineContext.job)
}
