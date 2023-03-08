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
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.flags.Flags

/** Stores user-selected preferred component. */
interface SelectedComponentRepository {

    /**
     * Returns currently set preferred component, or null when nothing is set. Consider using
     * [ControlsUiController.getPreferredSelectedItem] to get domain specific data
     */
    fun getSelectedComponent(): SelectedComponent?

    /** Sets preferred component. Use [getSelectedComponent] to get current one */
    fun setSelectedComponent(selectedComponent: SelectedComponent)

    /** Clears current preferred component. [getSelectedComponent] will return null afterwards */
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
