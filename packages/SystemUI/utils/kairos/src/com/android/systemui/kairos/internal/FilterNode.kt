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

import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none

internal inline fun <A> filterJustImpl(
    crossinline getPulse: suspend EvalScope.() -> TFlowImpl<Maybe<A>>
): TFlowImpl<A> =
    DemuxImpl(
            {
                mapImpl(getPulse) { maybeResult ->
                    if (maybeResult is Just) {
                        mapOf(Unit to maybeResult.value)
                    } else {
                        emptyMap()
                    }
                }
            },
            numKeys = 1,
        )
        .eventsForKey(Unit)

internal inline fun <A> filterImpl(
    crossinline getPulse: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline f: suspend EvalScope.(A) -> Boolean,
): TFlowImpl<A> = filterJustImpl { mapImpl(getPulse) { if (f(it)) just(it) else none }.cached() }
