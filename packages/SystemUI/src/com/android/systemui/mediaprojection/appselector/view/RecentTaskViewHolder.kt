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

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.android.systemui.res.R
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelector
import com.android.systemui.mediaprojection.appselector.data.AppIconLoader
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskLabelLoader
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RecentTaskViewHolder
@AssistedInject
constructor(
    @Assisted private val root: ViewGroup,
    private val iconLoader: AppIconLoader,
    private val thumbnailLoader: RecentTaskThumbnailLoader,
    private val labelLoader: RecentTaskLabelLoader,
    private val taskViewSizeProvider: TaskPreviewSizeProvider,
    @MediaProjectionAppSelector private val scope: CoroutineScope
) : ViewHolder(root), ConfigurationListener, TaskPreviewSizeProvider.TaskPreviewSizeListener {

    val thumbnailView: MediaProjectionTaskView = root.requireViewById(R.id.task_thumbnail)
    private val iconView: ImageView = root.requireViewById(R.id.task_icon)

    private var job: Job? = null

    init {
        updateThumbnailSize()
    }

    fun bind(task: RecentTask, onClick: (View) -> Unit) {
        taskViewSizeProvider.addCallback(this)
        job?.cancel()

        job =
            scope.launch {
                task.baseIntentComponent?.let { component ->
                    launch {
                        val icon = iconLoader.loadIcon(task.userId, component)
                        iconView.setImageDrawable(icon)
                    }
                    launch {
                        val label = labelLoader.loadLabel(task.userId, component)
                        root.contentDescription =
                            label
                                ?: root.context.getString(com.android.settingslib.R.string.unknown)
                    }
                }
                launch {
                    val thumbnail = thumbnailLoader.loadThumbnail(task.taskId)
                    thumbnailView.bindTask(task, thumbnail)
                }
            }

        root.setOnClickListener(onClick)
    }

    fun onRecycled() {
        taskViewSizeProvider.removeCallback(this)
        iconView.setImageDrawable(null)
        thumbnailView.bindTask(null, null)
        job?.cancel()
        job = null
    }

    override fun onTaskSizeChanged(size: Rect) {
        updateThumbnailSize()
    }

    private fun updateThumbnailSize() {
        thumbnailView.layoutParams =
            thumbnailView.layoutParams.apply {
                width = taskViewSizeProvider.size.width()
                height = taskViewSizeProvider.size.height()
            }
    }

    @AssistedFactory
    fun interface Factory {
        fun create(root: ViewGroup): RecentTaskViewHolder
    }
}
