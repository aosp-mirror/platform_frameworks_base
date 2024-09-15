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
package com.android.wm.shell.windowdecor.viewhost

import android.content.Context
import android.content.res.Configuration
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.view.WindowlessWindowManager
import androidx.tracing.Trace
import com.android.internal.annotations.VisibleForTesting
import com.android.wm.shell.shared.annotations.ShellMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
typealias SurfaceControlViewHostFactory =
            (Context, Display, WindowlessWindowManager, String) -> SurfaceControlViewHost

/**
 * A default implementation of [WindowDecorViewHost] backed by a [SurfaceControlViewHost].
 *
 * It does not support swapping the root view added to the VRI of the [SurfaceControlViewHost], and
 * any attempts to do will throw, which means that once a [View] is added using [updateView] or
 * [updateViewAsync], only its properties and binding may be changed, its children views may be
 * added, removed or changed and its [WindowManager.LayoutParams] may be changed.
 * It also supports asynchronously updating the view hierarchy using [updateViewAsync], in which
 * case the update work will be posted on the [ShellMainThread] with no delay.
 */
class DefaultWindowDecorViewHost(
    private val context: Context,
    @ShellMainThread private val mainScope: CoroutineScope,
    private val display: Display,
    private val surfaceControlViewHostFactory: SurfaceControlViewHostFactory = { c, d, wwm, s ->
        SurfaceControlViewHost(c, d, wwm, s)
    }
) : WindowDecorViewHost {

    private val rootSurface: SurfaceControl = SurfaceControl.Builder()
            .setName("DefaultWindowDecorViewHost surface")
            .setContainerLayer()
            .setCallsite("DefaultWindowDecorViewHost#init")
            .build()

    private var wwm: WindowlessWindowManager? = null
    @VisibleForTesting
    var viewHost: SurfaceControlViewHost? = null
    private var currentUpdateJob: Job? = null

    override val surfaceControl: SurfaceControl
        get() = rootSurface

    override fun updateView(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration,
        onDrawTransaction: SurfaceControl.Transaction?
    ) {
        Trace.beginSection("DefaultWindowDecorViewHost#updateView")
        clearCurrentUpdateJob()
        updateViewHost(view, attrs, configuration, onDrawTransaction)
        Trace.endSection()
    }

    override fun updateViewAsync(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration
    ) {
        Trace.beginSection("DefaultWindowDecorViewHost#updateViewAsync")
        clearCurrentUpdateJob()
        currentUpdateJob = mainScope.launch {
            updateViewHost(view, attrs, configuration, onDrawTransaction = null)
        }
        Trace.endSection()
    }

    override fun release(t: SurfaceControl.Transaction) {
        clearCurrentUpdateJob()
        viewHost?.release()
        t.remove(rootSurface)
    }

    private fun updateViewHost(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration,
        onDrawTransaction: SurfaceControl.Transaction?
    ) {
        Trace.beginSection("DefaultWindowDecorViewHost#updateViewHost")
        if (wwm == null) {
            wwm = WindowlessWindowManager(configuration, rootSurface, null)
        }
        requireWindowlessWindowManager().setConfiguration(configuration)
        if (viewHost == null) {
            viewHost = surfaceControlViewHostFactory.invoke(
                context,
                display,
                requireWindowlessWindowManager(),
                "DefaultWindowDecorViewHost#updateViewHost"
            )
        }
        onDrawTransaction?.let {
            requireViewHost().rootSurfaceControl.applyTransactionOnDraw(it)
        }
        if (requireViewHost().view == null) {
            Trace.beginSection("DefaultWindowDecorViewHost#updateViewHost-setView")
            requireViewHost().setView(view, attrs)
            Trace.endSection()
        } else {
            check(requireViewHost().view == view) { "Changing view is not allowed" }
            Trace.beginSection("DefaultWindowDecorViewHost#updateViewHost-relayout")
            requireViewHost().relayout(attrs)
            Trace.endSection()
        }
        Trace.endSection()
    }

    private fun clearCurrentUpdateJob() {
        currentUpdateJob?.cancel()
        currentUpdateJob = null
    }

    private fun requireWindowlessWindowManager(): WindowlessWindowManager {
        return wwm ?: error("Expected non-null windowless window manager")
    }

    private fun requireViewHost(): SurfaceControlViewHost {
        return viewHost ?: error("Expected non-null view host")
    }
}
