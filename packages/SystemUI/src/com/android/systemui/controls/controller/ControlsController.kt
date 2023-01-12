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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.actions.ControlAction
import com.android.systemui.controls.ControlStatus
import com.android.systemui.util.UserAwareController
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.SelectedItem
import java.util.function.Consumer

/**
 * Controller to handle communication between different parts of the controls system.
 *
 * This controller is in charge of:
 *  * Keeping track of favorites
 *  * Determining and keeping track of whether controls are enabled
 *  * Listening for user change and propagating that message in the system
 *  * Communicate between the UI and the [ControlsBindingController]
 *
 *  This controller being a [UserAwareController] means that all operations will be conducted on
 *  information for the current user only.
 */
interface ControlsController : UserAwareController {

    // SERVICE COMMUNICATION

    /**
     * Load all available [Control] for a given service.
     *
     * @param componentName the [ComponentName] of the [ControlsProviderService] to load from
     * @param dataCallback a callback in which to retrieve the result
     * @param cancelWrapper a callback to receive a [Runnable] that can be run to cancel the
     *                      request
     */
    fun loadForComponent(
        componentName: ComponentName,
        dataCallback: Consumer<LoadData>,
        cancelWrapper: Consumer<Runnable>
    )

    /**
     * Request to subscribe for favorited controls per structure
     *
     * @param structureInfo structure to limit the subscription to
     * @see [ControlsBindingController.subscribe]
     */
    fun subscribeToFavorites(structureInfo: StructureInfo)

    /**
     * Request to unsubscribe to the current provider.
     *
     * @see [ControlsBindingController.unsubscribe]
     */
    fun unsubscribe()

    /**
     * Notify a [ControlsProviderService] that an action has been performed on a [Control].
     *
     * @param componentName the name of the service that provides the [Control]
     * @param controlInfo information of the [Control] receiving the action
     * @param action action performed on the [Control]
     * @see [ControlsBindingController.action]
     */
    fun action(componentName: ComponentName, controlInfo: ControlInfo, action: ControlAction)

    /**
     * Refresh the status of a [Control] with information provided from the service.
     *
     * @param componentName the name of the service that provides the [Control]
     * @param control a stateful [Control] with updated information
     * @see [ControlsUiController.onRefreshState]
     */
    fun refreshStatus(componentName: ComponentName, control: Control)

    /**
     * Indicate the result of a [ControlAction] performed on a [Control].
     *
     * @param componentName the name of the service that provides the [Control]
     * @param controlId the id of the [Control] the actioned was performed on
     * @param response the result of the action.
     * @see [ControlsUiController.onActionResponse]
     */
    fun onActionResponse(
        componentName: ComponentName,
        controlId: String,
        @ControlAction.ResponseResult response: Int
    )

    // FAVORITE MANAGEMENT

    /**
     * Send a request to seed favorites into the persisted XML file
     *
     * @param componentNames the list of components to seed controls from
     * @param callback one [SeedResponse] per componentName
     */
    fun seedFavoritesForComponents(
        componentNames: List<ComponentName>,
        callback: Consumer<SeedResponse>
    )

    /**
     * Callback to be informed when the seeding process has finished
     *
     * @param callback consumer accepts true if successful
     * @return true if seeding is in progress and the callback was added
     */
    fun addSeedingFavoritesCallback(callback: Consumer<Boolean>): Boolean

    /**
     * Get all the favorites.
     *
     * @return a list of the structures that have at least one favorited control
     */
    fun getFavorites(): List<StructureInfo>

    /**
     * Get all the favorites for a given component.
     *
     * @param componentName the name of the service that provides the [Control]
     * @return a list of the structures that have at least one favorited control
     */
    fun getFavoritesForComponent(componentName: ComponentName): List<StructureInfo>

    /**
     * Get all the favorites for a given structure.
     *
     * @param componentName the name of the service that provides the [Control]
     * @param structureName the name of the structure
     * @return a list of the current favorites in that structure
     */
    fun getFavoritesForStructure(
        componentName: ComponentName,
        structureName: CharSequence
    ): List<ControlInfo>

    /**
     * Adds a single favorite to a given component and structure
     * @param componentName the name of the service that provides the [Control]
     * @param structureName the name of the structure that holds the [Control]
     * @param controlInfo persistent information about the [Control] to be added.
     */
    fun addFavorite(
        componentName: ComponentName,
        structureName: CharSequence,
        controlInfo: ControlInfo
    )

    /**
     * Replaces the favorites for the given structure.
     *
     * Calling this method will eliminate the previous selection of favorites and replace it with a
     * new one.
     *
     * @param structureInfo common structure for all of the favorited controls
     */
    fun replaceFavoritesForStructure(structureInfo: StructureInfo)

    /**
     * Return the number of favorites for a given component.
     *
     * This call returns the same as `getFavoritesForComponent(componentName).size`.
     *
     * @param componentName the name of the component
     * @return the number of current favorites for the given component
     */
    fun countFavoritesForComponent(componentName: ComponentName): Int

    /** See [ControlsUiController.getPreferredSelectedItem]. */
    fun getPreferredSelection(): SelectedItem

    /**
     * Bind to a service that provides a Device Controls panel (embedded activity). This will allow
     * the app to remain "warm", and reduce latency.
     *
     * @param component The [ComponentName] of the [ControlsProviderService] to bind.
     */
    fun bindComponentForPanel(componentName: ComponentName)

    /**
     * Interface for structure to pass data to [ControlsFavoritingActivity].
     */
    interface LoadData {
        /**
         * All of the available controls for the loaded [ControlsProviderService].
         *
         * This will indicate if they are currently a favorite and whether they were removed (a
         * favorite but not retrieved on load).
         */
        val allControls: List<ControlStatus>

        /**
         * Ordered list of ids of favorite controls.
         */
        val favoritesIds: List<String>

        /**
         * Whether there was an error in loading.
         *
         * In this case, [allControls] will only contain those that were favorited and will not be
         * marked as removed.
         */
        val errorOnLoad: Boolean
    }
}

/**
 * Creates a basic implementation of a [LoadData].
 */
fun createLoadDataObject(
    allControls: List<ControlStatus>,
    favorites: List<String>,
    error: Boolean = false
): ControlsController.LoadData {
    return object : ControlsController.LoadData {
        override val allControls = allControls
        override val favoritesIds = favorites
        override val errorOnLoad = error
    }
}

data class SeedResponse(val packageName: String, val accepted: Boolean)
