/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.compose

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.android.compose.animation.ViewTreeSavedStateRegistryOwner
import com.android.systemui.lifecycle.ViewLifecycleOwner

/**
 * An initializer to use Compose outside of an Activity, e.g. inside a window added directly using
 * [android.view.WindowManager.addView] (like the shade or status bar) or inside a dialog.
 *
 * Example:
 * ```
 *    windowManager.addView(MyWindowRootView(context), /* layoutParams */)
 *
 *    class MyWindowRootView(context: Context) : FrameLayout(context) {
 *        override fun onAttachedToWindow() {
 *            super.onAttachedToWindow()
 *            ComposeInitializer.onAttachedToWindow(this)
 *        }
 *
 *        override fun onDetachedFromWindow() {
 *            super.onDetachedFromWindow()
 *            ComposeInitializer.onDetachedFromWindow(this)
 *        }
 *    }
 * ```
 */
object ComposeInitializer {
    /** Function to be called on your window root view's [View.onAttachedToWindow] function. */
    fun onAttachedToWindow(root: View) {
        if (root.findViewTreeLifecycleOwner() != null) {
            error("root $root already has a LifecycleOwner")
        }

        val parent = root.parent
        if (parent is View && parent.id != android.R.id.content) {
            error(
                "ComposeInitializer.onAttachedToWindow(View) must be called on the content child." +
                    "Outside of activities and dialogs, this is usually the top-most View of a " +
                    "window."
            )
        }

        // The lifecycle owner, which is STARTED when [root] is visible and RESUMED when [root] is
        // both visible and focused.
        val lifecycleOwner = ViewLifecycleOwner(root)

        // We create a trivial implementation of [SavedStateRegistryOwner] that does not do any save
        // or restore because SystemUI process is always running and top-level windows using this
        // initializer are created once, when the process is started.
        val savedStateRegistryOwner =
            object : SavedStateRegistryOwner {
                private val savedStateRegistryController =
                    SavedStateRegistryController.create(this).apply { performRestore(null) }

                override val savedStateRegistry = savedStateRegistryController.savedStateRegistry

                override val lifecycle: Lifecycle
                    get() = lifecycleOwner.lifecycle
            }

        // We must call [ViewLifecycleOwner.onCreate] after creating the [SavedStateRegistryOwner]
        // because `onCreate` might move the lifecycle state to STARTED which will make
        // [SavedStateRegistryController.performRestore] throw.
        lifecycleOwner.onCreate()

        // Set the owners on the root. They will be reused by any ComposeView inside the root
        // hierarchy.
        root.setViewTreeLifecycleOwner(lifecycleOwner)
        ViewTreeSavedStateRegistryOwner.set(root, savedStateRegistryOwner)
    }

    /** Function to be called on your window root view's [View.onDetachedFromWindow] function. */
    fun onDetachedFromWindow(root: View) {
        (root.findViewTreeLifecycleOwner() as ViewLifecycleOwner).onDestroy()
        root.setViewTreeLifecycleOwner(null)
        ViewTreeSavedStateRegistryOwner.set(root, null)
    }
}
