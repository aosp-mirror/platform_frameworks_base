/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.controller.ControlInfo

/**
 * Adapter for binding [Control] information to views.
 *
 * @param layoutInflater an inflater for the views in the containing [RecyclerView]
 * @param favoriteCallback a callback to be called when the favorite status of a [Control] is
 *                         changed. The callback will take a [ControlInfo.Builder] that's
 *                         pre-populated with the [Control] information and the new favorite
 *                         status.
 */
class ControlAdapter(
    private val layoutInflater: LayoutInflater,
    private val favoriteCallback: (ControlInfo.Builder, Boolean) -> Unit
) : RecyclerView.Adapter<ControlAdapter.Holder>() {

    var listOfControls = emptyList<ControlStatus>()

    override fun onCreateViewHolder(parent: ViewGroup, i: Int): Holder {
        return Holder(layoutInflater.inflate(R.layout.control_item, parent, false))
    }

    override fun getItemCount() = listOfControls.size

    override fun onBindViewHolder(holder: Holder, index: Int) {
        holder.bindData(listOfControls[index], favoriteCallback)
    }

    /**
     * Holder for binding views in the [RecyclerView]-
     */
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = itemView.requireViewById(R.id.title)
        private val subtitle: TextView = itemView.requireViewById(R.id.subtitle)
        private val favorite: CheckBox = itemView.requireViewById(R.id.favorite)

        /**
         * Bind data to the view
         * @param data information about the [Control]
         * @param callback a callback to be called when the favorite status of the [Control] is
         *                 changed. The callback will take a [ControlInfo.Builder] that's
         *                 pre-populated with the [Control] information and the new favorite status.
         */
        fun bindData(data: ControlStatus, callback: (ControlInfo.Builder, Boolean) -> Unit) {
            title.text = data.control.title
            subtitle.text = data.control.subtitle
            favorite.isChecked = data.favorite
            favorite.setOnClickListener {
                val infoBuilder = ControlInfo.Builder().apply {
                    controlId = data.control.controlId
                    controlTitle = data.control.title
                    deviceType = data.control.deviceType
                }
                callback(infoBuilder, favorite.isChecked)
            }
        }
    }

    fun setItems(list: List<ControlStatus>) {
        listOfControls = list
        notifyDataSetChanged()
    }
}