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
import android.graphics.drawable.Icon
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.controls.ControlInterface
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.controller.ControlInfo

/**
 * Model for using with [ControlAdapter].
 *
 * Implementations of this interface provide different views of the controls to show.
 */
interface ControlsModel {

    /**
     * List of favorites in order.
     *
     * This should be obtained prior to storing the favorites using
     * [ControlsController.replaceFavoritesForComponent].
     */
    val favorites: List<ControlInfo>

    /**
     * List of all the elements to display by the corresponding [RecyclerView].
     */
    val elements: List<ElementWrapper>

    val moveHelper: MoveHelper?

    /**
     * Change the favorite status of a particular control.
     */
    fun changeFavoriteStatus(controlId: String, favorite: Boolean) {}

    /**
     * Move an item (in elements) from one position to another.
     */
    fun onMoveItem(from: Int, to: Int) {}

    /**
     * Attach an adapter to the model.
     *
     * This can be used to notify the adapter of changes in the model.
     */
    fun attachAdapter(adapter: RecyclerView.Adapter<*>) {}

    /**
     * Callback to notify elements (other than the adapter) of relevant changes in the model.
     */
    interface ControlsModelCallback {

        /**
         * Use to notify that the model has changed for the first time
         */
        fun onFirstChange()
    }

    /**
     * Interface to facilitate moving controls from an [AccessibilityDelegate].
     *
     * All positions should be 0 based.
     */
    interface MoveHelper {

        /**
         * Whether the control in `position` can be moved to the position before it.
         */
        fun canMoveBefore(position: Int): Boolean

        /**
         * Whether the control in `position` can be moved to the position after it.
         */
        fun canMoveAfter(position: Int): Boolean

        /**
         * Move the control in `position` to the position before it.
         */
        fun moveBefore(position: Int)

        /**
         * Move the control in `position` to the position after it.
         */
        fun moveAfter(position: Int)
    }
}

/**
 * Wrapper classes for the different types of elements shown in the [RecyclerView]s in
 * [ControlAdapter].
 */
sealed class ElementWrapper

data class ZoneNameWrapper(val zoneName: CharSequence) : ElementWrapper()

data class ControlStatusWrapper(
    val controlStatus: ControlStatus
) : ElementWrapper(), ControlInterface by controlStatus

@Suppress("UNUSED_PARAMETER") // Use function instead of lambda for compile time alloc
private fun nullIconGetter(_a: ComponentName, _b: String): Icon? = null

data class ControlInfoWrapper(
    override val component: ComponentName,
    val controlInfo: ControlInfo,
    override var favorite: Boolean
) : ElementWrapper(), ControlInterface {

    var customIconGetter: (ComponentName, String) -> Icon? = ::nullIconGetter
        private set

    // Separate constructor so the getter is not used in auto-generated methods
    constructor(
        component: ComponentName,
        controlInfo: ControlInfo,
        favorite: Boolean,
        customIconGetter: (ComponentName, String) -> Icon?
    ): this(component, controlInfo, favorite) {
        this.customIconGetter = customIconGetter
    }

    override val controlId: String
        get() = controlInfo.controlId
    override val title: CharSequence
        get() = controlInfo.controlTitle
    override val subtitle: CharSequence
        get() = controlInfo.controlSubtitle
    override val deviceType: Int
        get() = controlInfo.deviceType
    override val customIcon: Icon?
        get() = customIconGetter(component, controlId)
}

data class DividerWrapper(
    var showNone: Boolean = false,
    var showDivider: Boolean = false
) : ElementWrapper()
