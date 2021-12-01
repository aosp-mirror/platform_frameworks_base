/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.fgsmanager

import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.GuardedBy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.fgsmanager.FgsManagerDialogController.RunningApp
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor

/**
 * Dialog which shows a list of running foreground services and offers controls to them
 */
class FgsManagerDialog(
    context: Context,
    private val executor: Executor,
    @Background private val backgroundExecutor: Executor,
    private val systemClock: SystemClock,
    private val fgsManagerDialogController: FgsManagerDialogController
) : SystemUIDialog(context, R.style.Theme_SystemUI_Dialog) {

    private val appListRecyclerView: RecyclerView = RecyclerView(this.context)
    private val adapter: AppListAdapter = AppListAdapter()

    init {
        setTitle(R.string.fgs_manager_dialog_title)
        setView(appListRecyclerView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appListRecyclerView.layoutManager = LinearLayoutManager(context)
        fgsManagerDialogController.registerDialogForChanges(
                object : FgsManagerDialogController.FgsManagerDialogCallback {
                    override fun onRunningAppsChanged(apps: List<RunningApp>) {
                        executor.execute {
                            adapter.setData(apps)
                        }
                    }
                }
        )
        appListRecyclerView.adapter = adapter
        backgroundExecutor.execute { adapter.setData(fgsManagerDialogController.runningAppList) }
    }

    private inner class AppListAdapter : RecyclerView.Adapter<AppItemViewHolder>() {
        private val lock = Any()

        @GuardedBy("lock")
        private val data: MutableList<RunningApp> = ArrayList()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder {
            return AppItemViewHolder(LayoutInflater.from(context)
                            .inflate(R.layout.fgs_manager_app_item, parent, false))
        }

        override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
            var runningApp: RunningApp
            synchronized(lock) {
                runningApp = data[position]
            }
            with(holder) {
                iconView.setImageDrawable(runningApp.mIcon)
                appLabelView.text = runningApp.mAppLabel
                durationView.text = DateUtils.formatDuration(
                        Math.max(systemClock.elapsedRealtime() - runningApp.mTimeStarted, 60000),
                        DateUtils.LENGTH_MEDIUM)
                stopButton.setOnClickListener {
                    fgsManagerDialogController
                            .stopAllFgs(runningApp.mUserId, runningApp.mPackageName)
                }
            }
        }

        override fun getItemCount(): Int {
            synchronized(lock) { return data.size }
        }

        fun setData(newData: List<RunningApp>) {
            var oldData: List<RunningApp>
            synchronized(lock) {
                oldData = ArrayList(data)
                data.clear()
                data.addAll(newData)
            }

            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return oldData.size
                }

                override fun getNewListSize(): Int {
                    return newData.size
                }

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int):
                        Boolean {
                    return oldData[oldItemPosition] == newData[newItemPosition]
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int):
                        Boolean {
                    return true // TODO, look into updating the time subtext
                }
            }).dispatchUpdatesTo(this)
        }
    }

    private class AppItemViewHolder(parent: View) : RecyclerView.ViewHolder(parent) {
        val appLabelView: TextView = parent.requireViewById(R.id.fgs_manager_app_item_label)
        val durationView: TextView = parent.requireViewById(R.id.fgs_manager_app_item_duration)
        val iconView: ImageView = parent.requireViewById(R.id.fgs_manager_app_item_icon)
        val stopButton: Button = parent.requireViewById(R.id.fgs_manager_app_item_stop_button)
    }
}