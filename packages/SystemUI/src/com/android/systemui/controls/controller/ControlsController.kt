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
import com.android.systemui.controls.UserAwareController
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.ui.ControlsUiController
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

    /**
     * Whether the controls system is available for the current user.
     */
    val available: Boolean

    // SERVICE COMMUNICATION

    /**
     * Load all available [Control] for a given service.
     *
     * @param componentName the [ComponentName] of the [ControlsProviderService] to load from
     * @param dataCallback a callback in which to retrieve the result.
     */
    fun loadForComponent(
        componentName: ComponentName,
        dataCallback: Consumer<LoadData>
    )

    /**
     * Request to subscribe for all favorite controls.
     *
     * @see [ControlsBindingController.subscribe]
     */
    fun subscribeToFavorites()

    /**
     * Request to unsubscribe to all providers.
     *
     * @see [ControlsBindingController.unsubscribe]
     */
    fun unsubscribe()

    /**
     * Notify a [ControlsProviderService] that an action has been performed on a [Control].
     *
     * @param controlInfo information of the [Control] receiving the action
     * @param action action performed on the [Control]
     * @see [ControlsBindingController.action]
     */
    fun action(controlInfo: ControlInfo, action: ControlAction)

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
     * Get a list of all favorite controls.
     *
     * @return a list of [ControlInfo] with persistent information about the controls, including
     *         their corresponding [ComponentName].
     */
    fun getFavoriteControls(): List<ControlInfo>

    /**
     * Get all the favorites for a given component.
     *
     * @param componentName the name of the component of the [ControlsProviderService] with
     *                      which to filter the favorites.
     * @return a list of the favorite controls for the given service. All the elements of the list
     *         will have the same [ControlInfo.component] matching the one requested.
     */
    fun getFavoritesForComponent(componentName: ComponentName): List<ControlInfo>

    /**
     * Replaces the favorites for the given component.
     *
     * Calling this method will eliminate the previous selection of favorites and replace it with a
     * new one.
     *
     * @param componentName The name of the component for the [ControlsProviderService]
     * @param favorites a list of [ControlInfo] to replace the previous favorites.
     */
    fun replaceFavoritesForComponent(componentName: ComponentName, favorites: List<ControlInfo>)

    /**
     * Change the favorite status of a single [Control].
     *
     * If the control is added to favorites, it will be added to the end of the list for that
     * particular component. Matching for removing the control will be done based on
     * [ControlInfo.component] and [ControlInfo.controlId].
     *
     * Trying to add an already favorite control or trying to remove one that is not a favorite is
     * a no-op.
     *
     * @param controlInfo persistent information about the [Control].
     * @param state `true` to add to favorites and `false` to remove.
     */
    fun changeFavoriteStatus(controlInfo: ControlInfo, state: Boolean)

    /**
     * Return the number of favorites for a given component.
     *
     * This call returns the same as `getFavoritesForComponent(componentName).size`.
     *
     * @param componentName the name of the component
     * @return the number of current favorites for the given component
     */
    fun countFavoritesForComponent(componentName: ComponentName): Int

    /**
     * Clears the list of all favorites.
     *
     * To clear the list of favorites for a given service, call [replaceFavoritesForComponent] with
     * an empty list.
     */
    fun clearFavorites()

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
