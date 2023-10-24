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

package com.android.systemui.util.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.util.kotlin.awaitCancellationThenDispose
import com.android.systemui.util.kotlin.stateFlow
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Perform an inflation right away, then re-inflate whenever the [flow] emits, and call [onInflate]
 * on the resulting view each time. Dispose of the [DisposableHandle] returned by [onInflate] when
 * done.
 *
 * This never completes unless cancelled, it just suspends and waits for updates.
 *
 * For parameters [resource], [root] and [attachToRoot], see [LayoutInflater.inflate].
 *
 * An example use-case of this is when a view needs to be re-inflated whenever a configuration
 * change occurs, which would require the ViewBinder to then re-bind the new view. For example, the
 * code in the parent view's binder would look like:
 * ```
 * parentView.repeatWhenAttached {
 *     LayoutInflater.from(parentView.context)
 *         .reinflateOnChange(
 *             R.layout.my_layout,
 *             parentView,
 *             attachToRoot = false,
 *             coroutineScope = lifecycleScope,
 *             configurationController.onThemeChanged,
 *             ),
 *     ) { view ->
 *         ChildViewBinder.bind(view as ChildView, childViewModel)
 *     }
 * }
 * ```
 *
 * In turn, the bind method (passed through [onInflate]) uses [repeatWhenAttached], which returns a
 * [DisposableHandle].
 */
suspend fun LayoutInflater.reinflateAndBindLatest(
    resource: Int,
    root: ViewGroup?,
    attachToRoot: Boolean,
    flow: Flow<Unit>,
    onInflate: (View) -> DisposableHandle?,
) = coroutineScope {
    val viewFlow: Flow<View> = stateFlow(flow) { inflate(resource, root, attachToRoot) }
    viewFlow.bindLatest(onInflate)
}

/**
 * Use the [bind] method to bind the view every time this flow emits, and suspend to await for more
 * updates. New emissions lead to the previous binding call being cancelled if not completed.
 * Dispose of the [DisposableHandle] returned by [bind] when done.
 */
suspend fun Flow<View>.bindLatest(bind: (View) -> DisposableHandle?) {
    this.collectLatest { view ->
        val disposableHandle = bind(view)
        disposableHandle?.awaitCancellationThenDispose()
    }
}
