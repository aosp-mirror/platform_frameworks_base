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
import android.service.controls.Control
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
import com.android.systemui.controls.ControlInterface
import com.android.systemui.controls.ui.RenderInfo

private typealias ModelFavoriteChanger = (String, Boolean) -> Unit

/**
 * Adapter for binding [Control] information to views.
 *
 * The model for this adapter is provided by a [ControlModel] that is set using
 * [changeFavoritesModel]. This allows for updating the model if there's a reload.
 *
 * @property elevation elevation of each control view
 */
class ControlAdapter(
    private val elevation: Float
) : RecyclerView.Adapter<Holder>() {

    companion object {
        const val TYPE_ZONE = 0
        const val TYPE_CONTROL = 1
        const val TYPE_DIVIDER = 2
    }

    val spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            return if (getItemViewType(position) != TYPE_CONTROL) 2 else 1
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
            TYPE_DIVIDER -> {
                DividerHolder(layoutInflater.inflate(
                        R.layout.controls_horizontal_divider_with_empty, parent, false))
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

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            model?.let {
                val el = it.elements[position]
                if (el is ControlInterface) {
                    holder.updateFavorite(el.favorite)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        model?.let {
            return when (it.elements.get(position)) {
                is ZoneNameWrapper -> TYPE_ZONE
                is ControlStatusWrapper -> TYPE_CONTROL
                is ControlInfoWrapper -> TYPE_CONTROL
                is DividerWrapper -> TYPE_DIVIDER
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

    open fun updateFavorite(favorite: Boolean) {}
}

/**
 * Holder for using with [DividerWrapper] to display a divider between zones.
 *
 * The divider can be shown or hidden. It also has a view the height of a control, that can
 * be toggled visible or gone.
 */
private class DividerHolder(view: View) : Holder(view) {
    private val frame: View = itemView.requireViewById(R.id.frame)
    private val divider: View = itemView.requireViewById(R.id.divider)
    override fun bindData(wrapper: ElementWrapper) {
        wrapper as DividerWrapper
        frame.visibility = if (wrapper.showNone) View.VISIBLE else View.GONE
        divider.visibility = if (wrapper.showDivider) View.VISIBLE else View.GONE
    }
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
 * Holder for using with [ControlStatusWrapper] to display names of zones.
 * @param favoriteCallback this callback will be called whenever the favorite state of the
 *                         [Control] this view represents changes.
 */
internal class ControlHolder(
    view: View,
    val favoriteCallback: ModelFavoriteChanger
) : Holder(view) {
    private val icon: ImageView = itemView.requireViewById(R.id.icon)
    private val title: TextView = itemView.requireViewById(R.id.title)
    private val subtitle: TextView = itemView.requireViewById(R.id.subtitle)
    private val removed: TextView = itemView.requireViewById(R.id.status)
    private val favorite: CheckBox = itemView.requireViewById<CheckBox>(R.id.favorite).apply {
        visibility = View.VISIBLE
    }

    override fun bindData(wrapper: ElementWrapper) {
        wrapper as ControlInterface
        val renderInfo = getRenderInfo(wrapper.component, wrapper.deviceType)
        title.text = wrapper.title
        subtitle.text = wrapper.subtitle
        favorite.isChecked = wrapper.favorite
        removed.text = if (wrapper.removed) "Removed" else ""
        itemView.setOnClickListener {
            favorite.isChecked = !favorite.isChecked
            favoriteCallback(wrapper.controlId, favorite.isChecked)
        }
        applyRenderInfo(renderInfo)
    }

    override fun updateFavorite(favorite: Boolean) {
        this.favorite.isChecked = favorite
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
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val type = parent.adapter?.getItemViewType(position)
        if (type == ControlAdapter.TYPE_CONTROL) {
            outRect.apply {
                top = topMargin
                left = sideMargins
                right = sideMargins
                bottom = 0
            }
        } else if (type == ControlAdapter.TYPE_ZONE && position == 0) {
            // add negative padding to the first zone to counteract the margin
            val margin = (view.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            outRect.apply {
                top = -margin
                left = 0
                right = 0
                bottom = 0
            }
        }
    }
}
