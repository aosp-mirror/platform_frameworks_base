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

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.media.dagger.MediaProjectionAppSelector
import com.android.systemui.mediaprojection.appselector.data.AppIconLoader
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RecentTaskViewHolder @AssistedInject constructor(
    @Assisted root: ViewGroup,
    private val iconLoader: AppIconLoader,
    private val thumbnailLoader: RecentTaskThumbnailLoader,
    @MediaProjectionAppSelector private val scope: CoroutineScope
) : RecyclerView.ViewHolder(root) {

    private val iconView: ImageView = root.requireViewById(R.id.task_icon)
    private val thumbnailView: ImageView = root.requireViewById(R.id.task_thumbnail)

    private var job: Job? = null

    fun bind(task: RecentTask, onClick: (View) -> Unit) {
        job?.cancel()

        job =
            scope.launch {
                task.baseIntentComponent?.let { component ->
                    launch {
                        val icon = iconLoader.loadIcon(task.userId, component)
                        iconView.setImageDrawable(icon)
                    }
                }
                launch {
                    val thumbnail = thumbnailLoader.loadThumbnail(task.taskId)
                    thumbnailView.setImageBitmap(thumbnail?.thumbnail)
                }
            }

        thumbnailView.setOnClickListener(onClick)
    }

    fun onRecycled() {
        iconView.setImageDrawable(null)
        thumbnailView.setImageBitmap(null)
        job?.cancel()
        job = null
    }

    @AssistedFactory
    fun interface Factory {
        fun create(root: ViewGroup): RecentTaskViewHolder
    }
}
