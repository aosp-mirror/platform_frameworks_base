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

internal interface DeferScope {
    fun deferAction(block: () -> Unit)

    fun <R> deferAsync(block: () -> R): Lazy<R>
}

internal inline fun <A> deferScope(block: DeferScope.() -> A): A {
    val scope =
        object : DeferScope {
            val deferrals = ArrayDeque<() -> Unit>() // TODO: store lazies instead?

            fun drainDeferrals() {
                while (deferrals.isNotEmpty()) {
                    deferrals.removeFirst().invoke()
                }
            }

            override fun deferAction(block: () -> Unit) {
                deferrals.add(block)
            }

            override fun <R> deferAsync(block: () -> R): Lazy<R> =
                lazy(block).also { deferrals.add { it.value } }
        }
    return scope.block().also { scope.drainDeferrals() }
}

internal object NoValue

internal class CompletableLazy<T>(
    private var _value: Any? = NoValue,
    private val name: String? = null,
) : Lazy<T> {

    fun setValue(value: T) {
        check(_value === NoValue) { "CompletableLazy value already set" }
        _value = value
    }

    override val value: T
        get() {
            check(_value !== NoValue) { "CompletableLazy($name) accessed before initialized" }
            @Suppress("UNCHECKED_CAST")
            return _value as T
        }

    override fun isInitialized(): Boolean = _value !== NoValue
}
