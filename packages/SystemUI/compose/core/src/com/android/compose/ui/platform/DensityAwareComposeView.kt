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

package com.android.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AbstractComposeView

/**
 * A ComposeView that recreates its composition if the display size or font scale was changed.
 *
 * TODO(b/317317814): Remove this workaround.
 */
class DensityAwareComposeView(context: Context) : OpenComposeView(context) {
    private var lastDensityDpi: Int = -1
    private var lastFontScale: Float = -1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        val configuration = context.resources.configuration
        lastDensityDpi = configuration.densityDpi
        lastFontScale = configuration.fontScale
    }

    override fun dispatchConfigurationChanged(newConfig: Configuration) {
        super.dispatchConfigurationChanged(newConfig)

        // If the density or font scale changed, we dispose then recreate the composition. Note that
        // we do this here after dispatching the new configuration to children (instead of doing
        // this in onConfigurationChanged()) because the new configuration should first be
        // dispatched to the AndroidComposeView that holds the current density before we recreate
        // the composition.
        val densityDpi = newConfig.densityDpi
        val fontScale = newConfig.fontScale
        if (densityDpi != lastDensityDpi || fontScale != lastFontScale) {
            lastDensityDpi = densityDpi
            lastFontScale = fontScale

            disposeComposition()
            if (isAttachedToWindow) {
                createComposition()
            }
        }
    }
}

/** A fork of [androidx.compose.ui.platform.ComposeView] that is open and can be subclassed. */
open class OpenComposeView
internal constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    AbstractComposeView(context, attrs, defStyleAttr) {

    private val content = mutableStateOf<(@Composable () -> Unit)?>(null)

    @Suppress("RedundantVisibilityModifier")
    protected override var shouldCreateCompositionOnAttachedToWindow: Boolean = false

    @Composable
    override fun Content() {
        content.value?.invoke()
    }

    override fun getAccessibilityClassName(): CharSequence {
        return javaClass.name
    }

    /**
     * Set the Jetpack Compose UI content for this view. Initial composition will occur when the
     * view becomes attached to a window or when [createComposition] is called, whichever comes
     * first.
     */
    fun setContent(content: @Composable () -> Unit) {
        shouldCreateCompositionOnAttachedToWindow = true
        this.content.value = content
        if (isAttachedToWindow) {
            createComposition()
        }
    }
}
