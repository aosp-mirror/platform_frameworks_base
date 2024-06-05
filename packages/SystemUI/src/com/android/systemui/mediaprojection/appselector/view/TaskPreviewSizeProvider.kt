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

package com.android.systemui.mediaprojection.appselector.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorScope
import com.android.systemui.mediaprojection.appselector.view.TaskPreviewSizeProvider.TaskPreviewSizeListener
import com.android.systemui.shared.recents.utilities.Utilities.isLargeScreen
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import javax.inject.Inject

@MediaProjectionAppSelectorScope
class TaskPreviewSizeProvider
@Inject
constructor(
    private val context: Context,
    private val windowMetricsProvider: WindowMetricsProvider,
    private val configurationController: ConfigurationController,
) : CallbackController<TaskPreviewSizeListener>, ConfigurationListener, DefaultLifecycleObserver {

    /** Returns the size of the task preview on the screen in pixels */
    val size: Rect = calculateSize()

    private val listeners = arrayListOf<TaskPreviewSizeListener>()

    override fun onCreate(owner: LifecycleOwner) {
        configurationController.addCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        configurationController.removeCallback(this)
    }

    override fun onConfigChanged(newConfig: Configuration) {
        val newSize = calculateSize()
        if (newSize != size) {
            size.set(newSize)
            listeners.forEach { it.onTaskSizeChanged(size) }
        }
    }

    private fun calculateSize(): Rect {
        val maxWindowBounds = windowMetricsProvider.maximumWindowBounds
        val maximumWindowHeight = maxWindowBounds.height()
        val width = maxWindowBounds.width()
        var height = maximumWindowHeight

        val isLargeScreen = isLargeScreen(context)
        if (isLargeScreen) {
            val taskbarSize = windowMetricsProvider.currentWindowInsets.bottom
            height -= taskbarSize
        }

        val previewSize = Rect(0, 0, width, height)
        val scale = (height / maximumWindowHeight.toFloat()) / SCREEN_HEIGHT_TO_TASK_HEIGHT_RATIO
        previewSize.scale(scale)

        return previewSize
    }

    override fun addCallback(listener: TaskPreviewSizeListener) {
        listeners += listener
    }

    override fun removeCallback(listener: TaskPreviewSizeListener) {
        listeners -= listener
    }

    interface TaskPreviewSizeListener {
        fun onTaskSizeChanged(size: Rect)
    }
}

/**
 * How many times smaller the task preview should be on the screen comparing to the height of the
 * screen
 */
private const val SCREEN_HEIGHT_TO_TASK_HEIGHT_RATIO = 4f
