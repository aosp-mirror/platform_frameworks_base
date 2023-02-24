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
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.util.icuMessageFormat
import java.text.Collator
import java.util.concurrent.Executor

/**
 * Adapter for binding [ControlsServiceInfo] related to [ControlsProviderService].
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
    backgroundExecutor: Executor,
    uiExecutor: Executor,
    lifecycle: Lifecycle,
    controlsListingController: ControlsListingController,
    private val layoutInflater: LayoutInflater,
    private val onAppSelected: (ComponentName?) -> Unit = {},
    private val favoritesRenderer: FavoritesRenderer,
    private val resources: Resources
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var listOfServices = emptyList<ControlsServiceInfo>()

    private val callback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
            backgroundExecutor.execute {
                val collator = Collator.getInstance(resources.configuration.locales[0])
                val localeComparator = compareBy<ControlsServiceInfo, CharSequence>(collator) {
                    it.loadLabel() ?: ""
                }
                listOfServices = serviceInfos.sortedWith(localeComparator)
                uiExecutor.execute(::notifyDataSetChanged)
            }
        }
    }

    init {
        controlsListingController.observe(lifecycle, callback)
    }

    override fun onCreateViewHolder(parent: ViewGroup, i: Int): Holder {
        return Holder(
            layoutInflater.inflate(R.layout.controls_app_item, parent, false),
            favoritesRenderer
        )
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
    class Holder(view: View, val favRenderer: FavoritesRenderer) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = itemView.requireViewById(com.android.internal.R.id.icon)
        private val title: TextView = itemView.requireViewById(com.android.internal.R.id.title)
        private val favorites: TextView = itemView.requireViewById(R.id.favorites)

        /**
         * Bind data to the view
         * @param data Information about the [ControlsProviderService] to bind to the data
         */
        fun bindData(data: ControlsServiceInfo) {
            icon.setImageDrawable(data.loadIcon())
            title.text = data.loadLabel()
            val text = favRenderer.renderFavoritesForComponent(data.componentName)
            favorites.text = text
            favorites.visibility = if (text == null) View.GONE else View.VISIBLE
        }
    }
}

class FavoritesRenderer(
    private val resources: Resources,
    private val favoriteFunction: (ComponentName) -> Int
) {

    fun renderFavoritesForComponent(component: ComponentName): String? {
        val qty = favoriteFunction(component)
        return if (qty != 0) {
            icuMessageFormat(resources, R.string.controls_number_of_favorites, qty)
        } else {
            null
        }
    }
}
