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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.res.R
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class RecentTasksAdapter
@AssistedInject
constructor(
    @Assisted private val items: List<RecentTask>,
    @Assisted private val listener: RecentTaskClickListener,
    private val viewHolderFactory: RecentTaskViewHolder.Factory
) : RecyclerView.Adapter<RecentTaskViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentTaskViewHolder {
        val taskItem =
                LayoutInflater.from(parent.context)
                        .inflate(R.layout.media_projection_task_item, parent, false) as ViewGroup

        return viewHolderFactory.create(taskItem)
    }

    override fun onBindViewHolder(holder: RecentTaskViewHolder, position: Int) {
        val task = items[position]
        holder.bind(task, onClick = {
            listener.onRecentAppClicked(task, holder.itemView)
        })
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecentTaskViewHolder) {
        holder.onRecycled()
    }

    interface RecentTaskClickListener {
        fun onRecentAppClicked(task: RecentTask, view: View)
    }

    @AssistedFactory
    fun interface Factory {
        fun create(items: List<RecentTask>, listener: RecentTaskClickListener): RecentTasksAdapter
    }
}
