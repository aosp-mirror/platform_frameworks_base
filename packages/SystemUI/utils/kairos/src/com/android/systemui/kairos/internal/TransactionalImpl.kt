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

import com.android.systemui.kairos.internal.util.hashString

internal sealed class TransactionalImpl<out A> {
    data class Const<out A>(val value: Lazy<A>) : TransactionalImpl<A>()

    class Impl<A>(val block: EvalScope.() -> A) : TransactionalImpl<A>() {
        val cache = TransactionCache<Lazy<A>>()

        override fun toString(): String = "${this::class.simpleName}@$hashString"
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <A> transactionalImpl(noinline block: EvalScope.() -> A): TransactionalImpl<A> =
    TransactionalImpl.Impl(block)

internal fun <A> TransactionalImpl<A>.sample(evalScope: EvalScope): Lazy<A> =
    when (this) {
        is TransactionalImpl.Const -> value
        is TransactionalImpl.Impl ->
            cache.getOrPut(evalScope) { evalScope.deferAsync { evalScope.block() } }
    }
