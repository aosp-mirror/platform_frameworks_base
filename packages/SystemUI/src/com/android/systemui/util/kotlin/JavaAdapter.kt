/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.android.systemui.util.kotlin

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Collect information for the given [flow], calling [consumer] for each emitted event. Defaults to
 * [LifeCycle.State.CREATED] to better align with legacy ViewController usage of attaching listeners
 * during onViewAttached() and removing during onViewRemoved()
 */
@JvmOverloads
fun <T> collectFlow(
    view: View,
    flow: Flow<T>,
    consumer: Consumer<T>,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    state: Lifecycle.State = Lifecycle.State.CREATED,
) {
    view.repeatWhenAttached(coroutineContext) {
        repeatOnLifecycle(state) { flow.collect { consumer.accept(it) } }
    }
}
