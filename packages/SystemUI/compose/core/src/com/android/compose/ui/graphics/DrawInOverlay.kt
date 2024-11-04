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

package com.android.compose.ui.graphics

import android.view.View
import android.view.ViewGroupOverlay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draw this composable in the [overlay][ViewGroupOverlay] of the [current ComposeView][LocalView].
 */
@Composable
fun Modifier.drawInOverlay(): Modifier {
    val containerState = remember { ContainerState() }
    val context = LocalContext.current
    val localView = LocalView.current
    val compositionContext = rememberCompositionContext()
    val displayMetrics = context.resources.displayMetrics
    val displaySize = IntSize(displayMetrics.widthPixels, displayMetrics.heightPixels)

    DisposableEffect(containerState, context, localView, compositionContext, displaySize) {
        val overlay = localView.rootView.overlay as ViewGroupOverlay
        val view =
            ComposeView(context).apply {
                setParentCompositionContext(compositionContext)

                // Set the owners.
                setViewTreeLifecycleOwner(localView.findViewTreeLifecycleOwner())
                setViewTreeViewModelStoreOwner(localView.findViewTreeViewModelStoreOwner())
                setViewTreeSavedStateRegistryOwner(localView.findViewTreeSavedStateRegistryOwner())

                setContent { Box(Modifier.fillMaxSize().container(containerState)) }
            }

        overlay.add(view)

        // Make the ComposeView as big as the display. We have to manually measure and layout the
        // View given that there is no layout pass in Android overlays.
        view.measure(
            View.MeasureSpec.makeSafeMeasureSpec(displaySize.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeSafeMeasureSpec(displaySize.height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, displaySize.width, displaySize.height)

        onDispose { overlay.remove(view) }
    }

    return this.drawInContainer(containerState, enabled = { true })
}
