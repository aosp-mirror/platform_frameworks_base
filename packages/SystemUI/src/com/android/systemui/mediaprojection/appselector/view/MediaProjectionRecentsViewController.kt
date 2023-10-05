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

import android.app.ActivityOptions
import android.app.IActivityTaskManager
import android.graphics.Rect
import android.os.Binder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorResultHandler
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorScope
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.view.RecentTasksAdapter.RecentTaskClickListener
import com.android.systemui.mediaprojection.appselector.view.TaskPreviewSizeProvider.TaskPreviewSizeListener
import com.android.systemui.util.recycler.HorizontalSpacerItemDecoration
import javax.inject.Inject

/**
 * Controller that handles view of the recent apps selector in the media projection activity.
 * It is responsible for creating and updating recent apps view.
 */
@MediaProjectionAppSelectorScope
class MediaProjectionRecentsViewController
@Inject
constructor(
    private val recentTasksAdapterFactory: RecentTasksAdapter.Factory,
    private val taskViewSizeProvider: TaskPreviewSizeProvider,
    private val activityTaskManager: IActivityTaskManager,
    private val resultHandler: MediaProjectionAppSelectorResultHandler,
) : RecentTaskClickListener, TaskPreviewSizeListener {

    private var views: Views? = null
    private var lastBoundData: List<RecentTask>? = null

    init {
        taskViewSizeProvider.addCallback(this)
    }

    fun createView(parent: ViewGroup): ViewGroup =
        views?.root ?: createRecentViews(parent).also {
            views = it
            lastBoundData?.let { recents -> bind(recents) }
        }.root

    fun bind(recentTasks: List<RecentTask>) {
        views?.apply {
            if (recentTasks.isEmpty()) {
                root.visibility = View.GONE
                return
            }

            progress.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            root.visibility = View.VISIBLE

            recycler.adapter =
                recentTasksAdapterFactory.create(
                    recentTasks,
                    this@MediaProjectionRecentsViewController
                )
        }

        lastBoundData = recentTasks
    }

    private fun createRecentViews(parent: ViewGroup): Views {
        val recentsRoot =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.media_projection_recent_tasks, parent, /* attachToRoot= */ false)
                as ViewGroup

        val container = recentsRoot.requireViewById<View>(R.id.media_projection_recent_tasks_container)
        container.setTaskHeightSize()

        val progress = recentsRoot.requireViewById<View>(R.id.media_projection_recent_tasks_loader)
        val recycler =
            recentsRoot.requireViewById<RecyclerView>(R.id.media_projection_recent_tasks_recycler)
        recycler.layoutManager =
            LinearLayoutManager(
                parent.context,
                LinearLayoutManager.HORIZONTAL,
                /* reverseLayout= */ false
            )

        val itemDecoration =
            HorizontalSpacerItemDecoration(
                parent.resources.getDimensionPixelOffset(
                    R.dimen.media_projection_app_selector_recents_padding
                )
            )
        recycler.addItemDecoration(itemDecoration)

        return Views(recentsRoot, container, progress, recycler)
    }

    override fun onRecentAppClicked(task: RecentTask, view: View) {
        val launchCookie = Binder()
        val activityOptions =
            ActivityOptions.makeScaleUpAnimation(
                view,
                /* startX= */ 0,
                /* startY= */ 0,
                view.width,
                view.height
            )
        activityOptions.launchCookie = launchCookie

        activityTaskManager.startActivityFromRecents(task.taskId, activityOptions.toBundle())
        resultHandler.returnSelectedApp(launchCookie)
    }

    override fun onTaskSizeChanged(size: Rect) {
        views?.recentsContainer?.setTaskHeightSize()
    }

    private fun View.setTaskHeightSize() {
        val thumbnailHeight = taskViewSizeProvider.size.height()
        val itemHeight =
            thumbnailHeight +
                context.resources.getDimensionPixelSize(
                    R.dimen.media_projection_app_selector_task_icon_size
                ) +
                context.resources.getDimensionPixelSize(
                    R.dimen.media_projection_app_selector_task_icon_margin
                ) * 2

        layoutParams = layoutParams.apply { height = itemHeight }
    }

    private class Views(
        val root: ViewGroup,
        val recentsContainer: View,
        val progress: View,
        val recycler: RecyclerView
    )
}
