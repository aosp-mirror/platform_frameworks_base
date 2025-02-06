/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.systemui.kairos.internal.CompletableLazy

/**
 * A value that may not be immediately (synchronously) available, but is guaranteed to be available
 * before this transaction is completed.
 */
@ExperimentalKairosApi
class DeferredValue<out A> internal constructor(internal val unwrapped: Lazy<A>) {
    /**
     * Returns the value held by this [DeferredValue], or throws [IllegalStateException] if it is
     * not yet available.
     */
    val value: A
        get() = unwrapped.value
}

/** Returns an already-available [DeferredValue] containing [value]. */
@ExperimentalKairosApi
fun <A> deferredOf(value: A): DeferredValue<A> = DeferredValue(CompletableLazy(value))
