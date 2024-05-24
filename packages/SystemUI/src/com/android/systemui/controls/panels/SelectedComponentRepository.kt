/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.controls.panels

import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.flags.Flags
import kotlinx.coroutines.flow.Flow

/** Stores user-selected preferred component. */
interface SelectedComponentRepository {

    /** Returns current set preferred component for the specified user. */
    fun selectedComponentFlow(userHandle: UserHandle): Flow<SelectedComponent?>

    /**
     * Returns the current set preferred component for the specified user, or null when nothing is
     * set. If no user is specified, the current user's preference is used. This method by default
     * operates in the context of the current user unless another user is explicitly specified.
     * Consider using [ControlsUiController.getPreferredSelectedItem] to get domain specific data.
     */
    fun getSelectedComponent(userHandle: UserHandle = UserHandle.CURRENT): SelectedComponent?

    /**
     * Sets the preferred component for the current user. Use [getSelectedComponent] to retrieve the
     * currently set preferred component. This method applies to the current user's settings.
     */
    fun setSelectedComponent(selectedComponent: SelectedComponent)

    /**
     * Clears the current user's preferred component. After this operation, [getSelectedComponent]
     * will return null for the current user.
     */
    fun removeSelectedComponent()

    /**
     * Return true when default preferred component should be set up and false the otherwise. This
     * is always true when [Flags.APP_PANELS_REMOVE_APPS_ALLOWED] is disabled
     */
    fun shouldAddDefaultComponent(): Boolean

    /**
     * Sets if default component should be added. This is ignored when
     * [Flags.APP_PANELS_REMOVE_APPS_ALLOWED] is disabled
     */
    fun setShouldAddDefaultComponent(shouldAdd: Boolean)

    data class SelectedComponent(
        val name: String,
        val componentName: ComponentName?,
        val isPanel: Boolean,
    ) {
        constructor(
            selectedItem: SelectedItem
        ) : this(
            name = selectedItem.name.toString(),
            componentName = selectedItem.componentName,
            isPanel = selectedItem is SelectedItem.PanelItem,
        )
    }
}
