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

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job

/**
 * Scope for external side-effects triggered by the Kairos network.
 *
 * This still occurs within the context of a transaction, so general suspending calls are disallowed
 * to prevent blocking the transaction. You can [launch] new coroutines to perform long-running
 * asynchronous work. These coroutines are kept alive for the duration of the containing
 * [BuildScope] that this side-effect scope is running in.
 */
@ExperimentalKairosApi
interface EffectScope : HasNetwork, TransactionScope {
    /**
     * Creates a coroutine that is a child of this [EffectScope], and returns its future result as a
     * [Deferred].
     *
     * @see kotlinx.coroutines.async
     */
    fun <R> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend KairosCoroutineScope.() -> R,
    ): Deferred<R>

    /**
     * Launches a new coroutine that is a child of this [EffectScope] without blocking the current
     * thread and returns a reference to the coroutine as a [Job].
     *
     * @see kotlinx.coroutines.launch
     */
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend KairosCoroutineScope.() -> Unit,
    ): Job = async(context, start, block)
}

@ExperimentalKairosApi interface KairosCoroutineScope : HasNetwork, CoroutineScope
