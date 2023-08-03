/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.util.test

import android.util.Log

/**
 * Helper class that intercepts test errors which may be occurring on the wrong thread, and saves
 * them so that they can be rethrown back on the correct thread.
 */
class TestExceptionDeferrer(private val tag: String, private val testThread: Thread) {
    private val deferredErrors = mutableListOf<IllegalStateException>()

    /** Ensure the [value] is `true`; otherwise [fail] with the produced [message] */
    fun check(value: Boolean, message: () -> Any?) {
        if (value) return
        fail(message().toString())
    }

    /**
     * If the [Thread.currentThread] is the [testThread], then [error], otherwise [Log] and defer
     * the error until [throwDeferred] is called.
     */
    fun fail(message: String) {
        if (testThread == Thread.currentThread()) {
            error(message)
        } else {
            val exception = IllegalStateException(message)
            Log.e(tag, "Deferring error: ", exception)
            deferredErrors.add(exception)
        }
    }

    /** If any [fail] or failed [check] has happened, throw the first one. */
    fun throwDeferred() {
        deferredErrors.firstOrNull()?.let { firstError ->
            Log.e(tag, "Deferred errors: ${deferredErrors.size}")
            deferredErrors.clear()
            throw firstError
        }
    }
}
