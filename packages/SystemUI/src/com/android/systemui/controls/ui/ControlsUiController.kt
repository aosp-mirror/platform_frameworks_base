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

package com.android.systemui.controls.ui

import android.content.ComponentName
import android.content.Context
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.view.ViewGroup
import com.android.systemui.controls.controller.StructureInfo

interface ControlsUiController {
    companion object {
        public const val TAG = "ControlsUiController"
        public const val EXTRA_ANIMATE = "extra_animate"
        public const val EXIT_TO_DREAM = "extra_exit_to_dream"
    }

    fun show(parent: ViewGroup, onDismiss: Runnable, activityContext: Context)

    /**
     * Hide the controls content if it's attached to this parent.
     */
    fun hide(parent: ViewGroup)

    val isShowing: Boolean

    /**
     * Returns the preferred activity to start, depending on if the user has favorited any
     * controls or whether there are any app providing panels.
     */
    fun resolveActivity(): Class<*>

    /**
     * Request all open dialogs be closed. Set [immediately] to true to dismiss without
     * animations.
     */
    fun closeDialogs(immediately: Boolean)

    fun onRefreshState(componentName: ComponentName, controls: List<Control>)
    fun onActionResponse(
        componentName: ComponentName,
        controlId: String,
        @ControlAction.ResponseResult response: Int
    )

    /**
     * Returns the element that is currently preferred by the user.
     *
     * This element will be the one that appears when the user first opens the controls activity.
     */
    fun getPreferredSelectedItem(structures: List<StructureInfo>): SelectedItem

    fun onSizeChange()
}

sealed class SelectedItem {

    abstract val name: CharSequence
    abstract val hasControls: Boolean
    abstract val componentName: ComponentName

    /**
     * Represents the currently selected item for a structure.
     */
    data class StructureItem(val structure: StructureInfo) : SelectedItem() {
        override val name: CharSequence = structure.structure
        override val hasControls: Boolean = structure.controls.isNotEmpty()
        override val componentName: ComponentName = structure.componentName
    }

    /**
     * Represents the currently selected item for a service that provides a panel activity.
     *
     * The [componentName] is that of the service, as that is the expected identifier that should
     * not change (to always provide proper migration).
     */
    data class PanelItem(
            val appName: CharSequence,
            override val componentName:
            ComponentName
    ) : SelectedItem() {
        override val name: CharSequence = appName
        override val hasControls: Boolean = true
    }
    companion object {
        val EMPTY_SELECTION: SelectedItem = StructureItem(StructureInfo.EMPTY_STRUCTURE)
    }
}