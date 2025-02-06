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

import com.android.systemui.kairos.internal.store.Single
import com.android.systemui.kairos.internal.store.SingletonMapK
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Maybe.Present

internal inline fun <A> filterPresentImpl(
    crossinline getPulse: EvalScope.() -> EventsImpl<Maybe<A>>
): EventsImpl<A> =
    DemuxImpl(
            mapImpl(getPulse) { maybeResult, _ ->
                if (maybeResult is Present) {
                    Single(maybeResult.value)
                } else {
                    Single<A>()
                }
            },
            numKeys = 1,
            storeFactory = SingletonMapK.Factory(),
        )
        .eventsForKey(Unit)

internal inline fun <A> filterImpl(
    crossinline getPulse: EvalScope.() -> EventsImpl<A>,
    crossinline f: EvalScope.(A) -> Boolean,
): EventsImpl<A> {
    val mapped =
        mapImpl(getPulse) { it, _ -> if (f(it)) Maybe.present(it) else Maybe.absent }.cached()
    return filterPresentImpl { mapped }
}
