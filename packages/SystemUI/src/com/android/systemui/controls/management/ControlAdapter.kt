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

import android.content.ComponentName
import android.graphics.Rect
import android.service.controls.DeviceTypes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.controls.ui.RenderInfo

private typealias ModelFavoriteChanger = (String, Boolean) -> Unit

/**
 * Adapter for binding [Control] information to views.
 *
 * The model for this adapter is provided by a [FavoriteModel] that is set using
 * [changeFavoritesModel]. This allows for updating the model if there's a reload.
 *
 * @param layoutInflater an inflater for the views in the containing [RecyclerView]
 * @param onlyFavorites set to true to only display favorites instead of all controls
 */
class ControlAdapter(
    private val elevation: Float
) : RecyclerView.Adapter<Holder>() {

    companion object {
        private const val TYPE_ZONE = 0
        private const val TYPE_CONTROL = 1
    }

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            return if (getItemViewType(position) == TYPE_ZONE) 2 else 1
        }
    }

    private var model: ControlsModel? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CONTROL -> {
                ControlHolder(
                    layoutInflater.inflate(R.layout.controls_base_item, parent, false).apply {
                        layoutParams.apply {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        elevation = this@ControlAdapter.elevation
                        background = parent.context.getDrawable(
                                R.drawable.control_background_ripple)
                    }
                ) { id, favorite ->
                    model?.changeFavoriteStatus(id, favorite)
                }
            }
            TYPE_ZONE -> {
                ZoneHolder(layoutInflater.inflate(R.layout.controls_zone_header, parent, false))
            }
            else -> throw IllegalStateException("Wrong viewType: $viewType")
        }
    }

    fun changeModel(model: ControlsModel) {
        this.model = model
        notifyDataSetChanged()
    }

    override fun getItemCount() = model?.elements?.size ?: 0

    override fun onBindViewHolder(holder: Holder, index: Int) {
        model?.let {
            holder.bindData(it.elements[index])
        }
    }

    override fun getItemViewType(position: Int): Int {
        model?.let {
            return when (it.elements.get(position)) {
                is ZoneNameWrapper -> TYPE_ZONE
                is ControlWrapper -> TYPE_CONTROL
            }
        } ?: throw IllegalStateException("Getting item type for null model")
    }
}

/**
 * Holder for binding views in the [RecyclerView]-
 * @param view the [View] for this [Holder]
 */
sealed class Holder(view: View) : RecyclerView.ViewHolder(view) {

    /**
     * Bind the data from the model into the view
     */
    abstract fun bindData(wrapper: ElementWrapper)
}

/**
 * Holder for using with [ZoneNameWrapper] to display names of zones.
 */
private class ZoneHolder(view: View) : Holder(view) {
    private val zone: TextView = itemView as TextView

    override fun bindData(wrapper: ElementWrapper) {
        wrapper as ZoneNameWrapper
        zone.text = wrapper.zoneName
    }
}

/**
 * Holder for using with [ControlWrapper] to display names of zones.
 * @param favoriteCallback this callback will be called whenever the favorite state of the
 *                         [Control] this view represents changes.
 */
private class ControlHolder(view: View, val favoriteCallback: ModelFavoriteChanger) : Holder(view) {
    private val icon: ImageView = itemView.requireViewById(R.id.icon)
    private val title: TextView = itemView.requireViewById(R.id.title)
    private val subtitle: TextView = itemView.requireViewById(R.id.subtitle)
    private val removed: TextView = itemView.requireViewById(R.id.status)
    private val favorite: CheckBox = itemView.requireViewById<CheckBox>(R.id.favorite).apply {
        visibility = View.VISIBLE
    }

    override fun bindData(wrapper: ElementWrapper) {
        wrapper as ControlWrapper
        val data = wrapper.controlStatus
        val renderInfo = getRenderInfo(data.component, data.control.deviceType)
        title.text = data.control.title
        subtitle.text = data.control.subtitle
        favorite.isChecked = data.favorite
        removed.text = if (data.removed) "Removed" else ""
        itemView.setOnClickListener {
            favorite.isChecked = !favorite.isChecked
            favoriteCallback(data.control.controlId, favorite.isChecked)
        }
        applyRenderInfo(renderInfo)
    }

    private fun getRenderInfo(
        component: ComponentName,
        @DeviceTypes.DeviceType deviceType: Int
    ): RenderInfo {
        return RenderInfo.lookup(itemView.context, component, deviceType, true)
    }

    private fun applyRenderInfo(ri: RenderInfo) {
        val context = itemView.context
        val fg = context.getResources().getColorStateList(ri.foreground, context.getTheme())

        icon.setImageDrawable(ri.icon)
        icon.setImageTintList(fg)
    }
}

class MarginItemDecorator(
    private val topMargin: Int,
    private val sideMargins: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.apply {
            top = topMargin
            left = sideMargins
            right = sideMargins
        }
    }
}
