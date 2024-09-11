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

package com.android.systemui.common.ui.view

import android.view.View
import kotlinx.coroutines.DisposableHandle

/**
 * Set this view's [View#importantForAccessibility] to [View#IMPORTANT_FOR_ACCESSIBILITY_YES] or
 * [View#IMPORTANT_FOR_ACCESSIBILITY_NO] based on [value].
 */
fun View.setImportantForAccessibilityYesNo(value: Boolean) {
    importantForAccessibility =
        if (value) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/**
 * Can be used to find the nearest parent of a view of a particular type.
 *
 * Usage:
 * ```
 * val textView = view.getNearestParent<TextView>()
 * ```
 */
inline fun <reified T : View> View.getNearestParent(): T? {
    var view: Any? = this
    while (view is View) {
        if (view is T) return view
        view = view.parent
    }
    return null
}

/** Adds a [View.OnLayoutChangeListener] and provides a [DisposableHandle] for teardown. */
fun View.onLayoutChanged(onLayoutChanged: (v: View) -> Unit): DisposableHandle =
    onLayoutChanged { v, _, _, _, _, _, _, _, _ ->
        onLayoutChanged(v)
    }

/** Adds the [View.OnLayoutChangeListener] and provides a [DisposableHandle] for teardown. */
fun View.onLayoutChanged(listener: View.OnLayoutChangeListener): DisposableHandle {
    addOnLayoutChangeListener(listener)
    return DisposableHandle { removeOnLayoutChangeListener(listener) }
}

/** Adds a [View.OnApplyWindowInsetsListener] and provides a [DisposableHandle] for teardown. */
fun View.onApplyWindowInsets(listener: View.OnApplyWindowInsetsListener): DisposableHandle {
    setOnApplyWindowInsetsListener(listener)
    return DisposableHandle { setOnApplyWindowInsetsListener(null) }
}

/** Adds a [View.OnTouchListener] and provides a [DisposableHandle] for teardown. */
fun View.onTouchListener(listener: View.OnTouchListener): DisposableHandle {
    setOnTouchListener(listener)
    return DisposableHandle { setOnTouchListener(null) }
}
