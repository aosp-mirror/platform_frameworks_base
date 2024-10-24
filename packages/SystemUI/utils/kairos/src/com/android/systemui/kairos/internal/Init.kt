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
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Performs actions once, when the reactive component is first connected to the network. */
internal class Init<out A>(val name: String?, private val block: suspend InitScope.() -> A) {

    /** Has the initialization logic been evaluated yet? */
    private val initialized = AtomicBoolean()

    /**
     * Stores the result after initialization, as well as the id of the [Network] it's been
     * initialized with.
     */
    private val cache = CompletableDeferred<Pair<Any, A>>()

    suspend fun connect(evalScope: InitScope): A =
        if (initialized.getAndSet(true)) {
            // Read from cache
            val (networkId, result) = cache.await()
            check(networkId == evalScope.networkId) { "Network mismatch" }
            result
        } else {
            // Write to cache
            block(evalScope).also { cache.complete(evalScope.networkId to it) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getUnsafe(): Maybe<A> =
        if (cache.isCompleted) {
            just(cache.getCompleted().second)
        } else {
            none
        }
}

internal fun <A> init(name: String?, block: suspend InitScope.() -> A) = Init(name, block)

internal fun <A> constInit(name: String?, value: A) = init(name) { value }
