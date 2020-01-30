/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.content.ComponentName
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.widget.CandidateInfo
import com.android.systemui.R
import java.util.concurrent.Executor

/**
 * Adapter for binding [CandidateInfo] related to [ControlsProviderService].
 *
 * This class handles subscribing and keeping track of the list of valid applications for
 * displaying.
 *
 * @param uiExecutor an executor on the view thread of the containing [RecyclerView]
 * @param lifecycle the lifecycle of the containing [LifecycleOwner] to control listening status
 * @param controlsListingController the controller to keep track of valid applications
 * @param layoutInflater an inflater for the views in the containing [RecyclerView]
 * @param onAppSelected a callback to indicate that an app has been selected in the list.
 */
class AppAdapter(
    uiExecutor: Executor,
    lifecycle: Lifecycle,
    controlsListingController: ControlsListingController,
    private val layoutInflater: LayoutInflater,
    private val onAppSelected: (ComponentName?) -> Unit = {}
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var listOfServices = emptyList<CandidateInfo>()

    private val callback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(list: List<CandidateInfo>) {
            uiExecutor.execute {
                listOfServices = list
                notifyDataSetChanged()
            }
        }
    }

    init {
        controlsListingController.observe(lifecycle, callback)
    }

    override fun onCreateViewHolder(parent: ViewGroup, i: Int): Holder {
        return Holder(layoutInflater.inflate(R.layout.app_item, parent, false))
    }

    override fun getItemCount() = listOfServices.size

    override fun onBindViewHolder(holder: Holder, index: Int) {
        holder.bindData(listOfServices[index])
        holder.itemView.setOnClickListener {
            onAppSelected(ComponentName.unflattenFromString(listOfServices[index].key))
        }
    }

    /**
     * Holder for binding views in the [RecyclerView]-
     */
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = itemView.requireViewById(com.android.internal.R.id.icon)
        private val title: TextView = itemView.requireViewById(com.android.internal.R.id.title)

        /**
         * Bind data to the view
         * @param data Information about the [ControlsProviderService] to bind to the data
         */
        fun bindData(data: CandidateInfo) {
            icon.setImageDrawable(data.loadIcon())
            title.text = data.loadLabel()
        }
    }
}