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
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.controls.ControlInterface
import com.android.systemui.controls.ui.CanUseIconPredicate
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
    private val elevation: Float,
    private val currentUserId: Int,
) : RecyclerView.Adapter<Holder>() {

    companion object {
        const val TYPE_ZONE = 0
        const val TYPE_CONTROL = 1
        const val TYPE_DIVIDER = 2

        /**
         * For low-dp width screens that also employ an increased font scale, adjust the
         * number of columns. This helps prevent text truncation on these devices.
         *
         */
        @JvmStatic
        fun findMaxColumns(res: Resources): Int {
            var maxColumns = res.getInteger(R.integer.controls_max_columns)
            val maxColumnsAdjustWidth =
                    res.getInteger(R.integer.controls_max_columns_adjust_below_width_dp)

            val outValue = TypedValue()
            res.getValue(R.dimen.controls_max_columns_adjust_above_font_scale, outValue, true)
            val maxColumnsAdjustFontScale = outValue.getFloat()

            val config = res.configuration
            val isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT
            if (isPortrait &&
                    config.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED &&
                    config.screenWidthDp <= maxColumnsAdjustWidth &&
                    config.fontScale >= maxColumnsAdjustFontScale) {
                maxColumns--
            }

            return maxColumns
        }
    }

    private var model: ControlsModel? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CONTROL -> {
                ControlHolder(
                    layoutInflater.inflate(R.layout.controls_base_item, parent, false).apply {
                        (layoutParams as ViewGroup.MarginLayoutParams).apply {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                            // Reset margins as they will be set through the decoration
                            topMargin = 0
                            bottomMargin = 0
                            leftMargin = 0
                            rightMargin = 0
                        }
                        elevation = this@ControlAdapter.elevation
                        background = parent.context.getDrawable(
                                R.drawable.control_background_ripple)
                    },
                    currentUserId,
                    model?.moveHelper, // Indicates that position information is needed
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
 * @param moveHelper a helper interface to facilitate a11y rearranging. Null indicates no
 *                   rearranging
 * @param favoriteCallback this callback will be called whenever the favorite state of the
 *                         [Control] this view represents changes.
 */
internal class ControlHolder(
    view: View,
    currentUserId: Int,
    val moveHelper: ControlsModel.MoveHelper?,
    val favoriteCallback: ModelFavoriteChanger,
) : Holder(view) {
    private val favoriteStateDescription =
        itemView.context.getString(R.string.accessibility_control_favorite)
    private val notFavoriteStateDescription =
        itemView.context.getString(R.string.accessibility_control_not_favorite)

    private val icon: ImageView = itemView.requireViewById(R.id.icon)
    private val title: TextView = itemView.requireViewById(R.id.title)
    private val subtitle: TextView = itemView.requireViewById(R.id.subtitle)
    private val removed: TextView = itemView.requireViewById(R.id.status)
    private val favorite: CheckBox = itemView.requireViewById<CheckBox>(R.id.favorite).apply {
        visibility = View.VISIBLE
    }

    private val canUseIconPredicate = CanUseIconPredicate(currentUserId)
    private val accessibilityDelegate = ControlHolderAccessibilityDelegate(
        this::stateDescription,
        this::getLayoutPosition,
        moveHelper
    )

    init {
        ViewCompat.setAccessibilityDelegate(itemView, accessibilityDelegate)
    }

    // Determine the stateDescription based on favorite state and maybe position
    private fun stateDescription(favorite: Boolean): CharSequence? {
        if (!favorite) {
            return notFavoriteStateDescription
        } else if (moveHelper == null) {
            return favoriteStateDescription
        } else {
            val position = layoutPosition + 1
            return itemView.context.getString(
                R.string.accessibility_control_favorite_position, position)
        }
    }

    override fun bindData(wrapper: ElementWrapper) {
        wrapper as ControlInterface
        val renderInfo = getRenderInfo(wrapper.component, wrapper.deviceType)
        title.text = wrapper.title
        subtitle.text = wrapper.subtitle
        updateFavorite(wrapper.favorite)
        removed.text = if (wrapper.removed) {
            itemView.context.getText(R.string.controls_removed)
        } else {
            ""
        }
        itemView.setOnClickListener {
            updateFavorite(!favorite.isChecked)
            favoriteCallback(wrapper.controlId, favorite.isChecked)
        }
        applyRenderInfo(renderInfo, wrapper)
    }

    override fun updateFavorite(favorite: Boolean) {
        this.favorite.isChecked = favorite
        accessibilityDelegate.isFavorite = favorite
        itemView.stateDescription = stateDescription(favorite)
    }

    private fun getRenderInfo(
        component: ComponentName,
        @DeviceTypes.DeviceType deviceType: Int
    ): RenderInfo {
        return RenderInfo.lookup(itemView.context, component, deviceType)
    }

    private fun applyRenderInfo(ri: RenderInfo, ci: ControlInterface) {
        val context = itemView.context
        val fg = context.getResources().getColorStateList(ri.foreground, context.getTheme())

        icon.imageTintList = null
        ci.customIcon
                ?.takeIf(canUseIconPredicate)
                ?.let {
            icon.setImageIcon(it)
        } ?: run {
            icon.setImageDrawable(ri.icon)

            // Do not color app icons
            if (ci.deviceType != DeviceTypes.TYPE_ROUTINE) {
                icon.setImageTintList(fg)
            }
        }
    }
}

/**
 * Accessibility delegate for [ControlHolder].
 *
 * Provides the following functionality:
 * * Sets the state description indicating whether the controls is Favorited or Unfavorited
 * * Adds the position to the state description if necessary.
 * * Adds context action for moving (rearranging) a control.
 *
 * @param stateRetriever function to determine the state description based on the favorite state
 * @param positionRetriever function to obtain the position of this control. It only has to be
 *                          correct in controls that are currently favorites (and therefore can
 *                          be moved).
 * @param moveHelper helper interface to determine if a control can be moved and actually move it.
 */
private class ControlHolderAccessibilityDelegate(
    val stateRetriever: (Boolean) -> CharSequence?,
    val positionRetriever: () -> Int,
    val moveHelper: ControlsModel.MoveHelper?
) : AccessibilityDelegateCompat() {

    var isFavorite = false

    companion object {
        private val MOVE_BEFORE_ID = R.id.accessibility_action_controls_move_before
        private val MOVE_AFTER_ID = R.id.accessibility_action_controls_move_after
    }

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)

        info.isContextClickable = false
        addClickAction(host, info)
        maybeAddMoveBeforeAction(host, info)
        maybeAddMoveAfterAction(host, info)

        // Determine the stateDescription based on the holder information
        info.stateDescription = stateRetriever(isFavorite)
        // Remove the information at the end indicating row and column.
        info.setCollectionItemInfo(null)

        info.className = Switch::class.java.name
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        if (super.performAccessibilityAction(host, action, args)) {
            return true
        }
        return when (action) {
            MOVE_BEFORE_ID -> {
                moveHelper?.moveBefore(positionRetriever())
                true
            }
            MOVE_AFTER_ID -> {
                moveHelper?.moveAfter(positionRetriever())
                true
            }
            else -> false
        }
    }

    private fun addClickAction(host: View, info: AccessibilityNodeInfoCompat) {
        // Change the text for the double-tap action
        val clickActionString = if (isFavorite) {
            host.context.getString(R.string.accessibility_control_change_unfavorite)
        } else {
            host.context.getString(R.string.accessibility_control_change_favorite)
        }
        val click = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
            AccessibilityNodeInfo.ACTION_CLICK,
            // “favorite/unfavorite”
            clickActionString)
        info.addAction(click)
    }

    private fun maybeAddMoveBeforeAction(host: View, info: AccessibilityNodeInfoCompat) {
        if (moveHelper?.canMoveBefore(positionRetriever()) ?: false) {
            val newPosition = positionRetriever() + 1 - 1
            val moveBefore = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                MOVE_BEFORE_ID,
                host.context.getString(R.string.accessibility_control_move, newPosition)
            )
            info.addAction(moveBefore)
            info.isContextClickable = true
        }
    }

    private fun maybeAddMoveAfterAction(host: View, info: AccessibilityNodeInfoCompat) {
        if (moveHelper?.canMoveAfter(positionRetriever()) ?: false) {
            val newPosition = positionRetriever() + 1 + 1
            val moveAfter = AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                MOVE_AFTER_ID,
                host.context.getString(R.string.accessibility_control_move, newPosition)
            )
            info.addAction(moveAfter)
            info.isContextClickable = true
        }
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
                top = topMargin * 2 // Use double margin, as we are not setting bottom
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
