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

package com.android.systemui.lifecycle

import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [Activatable] that can be concurrently activated by no more than one owner.
 *
 * A previous call to [activate] must be canceled before a new call to [activate] can be made.
 * Trying to call [activate] while already active will fail with an error.
 */
abstract class SafeActivatable : Activatable {

    private val _isActive = AtomicBoolean(false)

    var isActive: Boolean
        get() = _isActive.get()
        private set(value) {
            _isActive.set(value)
        }

    final override suspend fun activate() {
        val allowed = _isActive.compareAndSet(false, true)
        check(allowed) { "Cannot activate an already active activatable!" }

        try {
            onActivated()
        } finally {
            isActive = false
        }
    }

    /**
     * Notifies that the [Activatable] has been activated.
     *
     * Serves as an entrypoint to kick off coroutine work that the object requires in order to keep
     * its state fresh and/or perform side-effects.
     *
     * The method suspends and doesn't return until all work required by the object is finished. In
     * most cases, it's expected for the work to remain ongoing forever so this method will forever
     * suspend its caller until the coroutine that called it is canceled.
     *
     * Implementations could follow this pattern:
     * ```kotlin
     * override suspend fun onActivated() {
     *     coroutineScope {
     *         launch { ... }
     *         launch { ... }
     *         launch { ... }
     *     }
     * }
     * ```
     *
     * @see activate
     */
    protected abstract suspend fun onActivated()
}
