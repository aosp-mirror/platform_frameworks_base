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
package com.android.wm.shell.windowdecor.common.viewhost

import android.content.Context
import android.content.res.Configuration
import android.graphics.Region
import android.view.Display
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.tracing.Trace
import com.android.internal.annotations.VisibleForTesting
import com.android.wm.shell.shared.annotations.ShellMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * An implementation of [WindowDecorViewHost] that supports:
 * 1) Replacing the root [View], meaning [WindowDecorViewHost.updateView] maybe be called with
 *    different [View] instances. This is useful when reusing [WindowDecorViewHost]s instances for
 *    vastly different view hierarchies, such as Desktop Windowing's App Handles and App Headers.
 */
class ReusableWindowDecorViewHost(
    private val context: Context,
    @ShellMainThread private val mainScope: CoroutineScope,
    display: Display,
    val id: Int,
    @VisibleForTesting
    val viewHostAdapter: SurfaceControlViewHostAdapter =
        SurfaceControlViewHostAdapter(context, display),
) : WindowDecorViewHost {
    @VisibleForTesting val rootView = FrameLayout(context)

    private var currentUpdateJob: Job? = null

    override val surfaceControl: SurfaceControl
        get() = viewHostAdapter.rootSurface

    override fun updateView(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration,
        touchableRegion: Region?,
        onDrawTransaction: SurfaceControl.Transaction?,
    ) {
        Trace.beginSection("ReusableWindowDecorViewHost#updateView")
        clearCurrentUpdateJob()
        updateViewHost(view, attrs, configuration, touchableRegion, onDrawTransaction)
        Trace.endSection()
    }

    override fun updateViewAsync(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration,
        touchableRegion: Region?,
    ) {
        Trace.beginSection("ReusableWindowDecorViewHost#updateViewAsync")
        clearCurrentUpdateJob()
        currentUpdateJob =
            mainScope.launch {
                updateViewHost(
                    view,
                    attrs,
                    configuration,
                    touchableRegion,
                    onDrawTransaction = null,
                )
            }
        Trace.endSection()
    }

    override fun release(t: SurfaceControl.Transaction) {
        clearCurrentUpdateJob()
        viewHostAdapter.release(t)
    }

    private fun updateViewHost(
        view: View,
        attrs: WindowManager.LayoutParams,
        configuration: Configuration,
        touchableRegion: Region?,
        onDrawTransaction: SurfaceControl.Transaction?,
    ) {
        Trace.beginSection("ReusableWindowDecorViewHost#updateViewHost")
        viewHostAdapter.prepareViewHost(configuration, touchableRegion)
        onDrawTransaction?.let { viewHostAdapter.applyTransactionOnDraw(it) }
        rootView.removeAllViews()
        rootView.addView(view)
        viewHostAdapter.updateView(rootView, attrs)
        Trace.endSection()
    }

    private fun clearCurrentUpdateJob() {
        currentUpdateJob?.cancel()
        currentUpdateJob = null
    }

    companion object {
        private const val TAG = "ReusableWindowDecorViewHost"
    }
}
