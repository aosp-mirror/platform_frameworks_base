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

import kotlinx.coroutines.CoroutineScope

/**
 * Scope for external side-effects triggered by the Kairos network. This still occurs within the
 * context of a transaction, so general suspending calls are disallowed to prevent blocking the
 * transaction. You can use [effectCoroutineScope] to [launch][kotlinx.coroutines.launch] new
 * coroutines to perform long-running asynchronous work. This scope is alive for the duration of the
 * containing [BuildScope] that this side-effect scope is running in.
 */
@ExperimentalKairosApi
interface EffectScope : TransactionScope {
    /**
     * A [CoroutineScope] whose lifecycle lives for as long as this [EffectScope] is alive. This is
     * generally until the [Job][kotlinx.coroutines.Job] returned by [BuildScope.effect] is
     * cancelled.
     */
    @ExperimentalKairosApi val effectCoroutineScope: CoroutineScope

    /**
     * A [KairosNetwork] instance that can be used to transactionally query / modify the Kairos
     * network.
     *
     * The lambda passed to [KairosNetwork.transact] on this instance will receive an [BuildScope]
     * that is lifetime-bound to this [EffectScope]. Once this [EffectScope] is no longer alive, any
     * modifications to the Kairos network performed via this [KairosNetwork] instance will be
     * undone (any registered [observers][BuildScope.observe] are unregistered, and any pending
     * [side-effects][BuildScope.effect] are cancelled).
     */
    @ExperimentalKairosApi val kairosNetwork: KairosNetwork
}
