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

import kotlin.coroutines.RestrictsSuspension
import kotlinx.coroutines.CoroutineScope

/**
 * Scope for external side-effects triggered by the Frp network. This still occurs within the
 * context of a transaction, so general suspending calls are disallowed to prevent blocking the
 * transaction. You can use [frpCoroutineScope] to [launch] new coroutines to perform long-running
 * asynchronous work. This scope is alive for the duration of the containing [FrpBuildScope] that
 * this side-effect scope is running in.
 */
@RestrictsSuspension
@ExperimentalFrpApi
interface FrpEffectScope : FrpTransactionScope {
    /**
     * A [CoroutineScope] whose lifecycle lives for as long as this [FrpEffectScope] is alive. This
     * is generally until the [Job] returned by [FrpBuildScope.effect] is cancelled.
     */
    @ExperimentalFrpApi val frpCoroutineScope: CoroutineScope

    /**
     * A [FrpNetwork] instance that can be used to transactionally query / modify the FRP network.
     *
     * The lambda passed to [FrpNetwork.transact] on this instance will receive an [FrpBuildScope]
     * that is lifetime-bound to this [FrpEffectScope]. Once this [FrpEffectScope] is no longer
     * alive, any modifications to the FRP network performed via this [FrpNetwork] instance will be
     * undone (any registered [observers][FrpBuildScope.observe] are unregistered, and any pending
     * [side-effects][FrpBuildScope.effect] are cancelled).
     */
    @ExperimentalFrpApi val frpNetwork: FrpNetwork
}
