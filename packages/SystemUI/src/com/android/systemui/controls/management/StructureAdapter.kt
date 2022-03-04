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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R

class StructureAdapter(
    private val models: List<StructureContainer>
) : RecyclerView.Adapter<StructureAdapter.StructureHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, p1: Int): StructureHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return StructureHolder(
            layoutInflater.inflate(R.layout.controls_structure_page, parent, false)
        )
    }

    override fun getItemCount() = models.size

    override fun onBindViewHolder(holder: StructureHolder, index: Int) {
        holder.bind(models[index].model)
    }

    class StructureHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val recyclerView: RecyclerView
        private val controlAdapter: ControlAdapter

        init {
            recyclerView = itemView.requireViewById<RecyclerView>(R.id.listAll)
            val elevation = itemView.context.resources.getFloat(R.dimen.control_card_elevation)
            controlAdapter = ControlAdapter(elevation)
            setUpRecyclerView()
        }

        fun bind(model: ControlsModel) {
            controlAdapter.changeModel(model)
        }

        private fun setUpRecyclerView() {
            val margin = itemView.context.resources
                .getDimensionPixelSize(R.dimen.controls_card_margin)
            val itemDecorator = MarginItemDecorator(margin, margin)
            val spanCount = ControlAdapter.findMaxColumns(itemView.resources)

            recyclerView.apply {
                this.adapter = controlAdapter
                layoutManager = GridLayoutManager(recyclerView.context, spanCount).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (adapter?.getItemViewType(position)
                                    != ControlAdapter.TYPE_CONTROL) spanCount else 1
                        }
                    }
                }
                addItemDecoration(itemDecorator)
            }
        }
    }
}