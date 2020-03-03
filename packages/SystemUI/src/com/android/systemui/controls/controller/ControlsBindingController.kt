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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.actions.ControlAction
import com.android.systemui.controls.UserAwareController
import java.util.function.Consumer

/**
 * Controller for keeping track of any [ControlsProviderService] that needs to be bound.
 *
 * This controller serves as an interface between [ControlsController] and the services.
 *
 * This controller being a [UserAwareController] means that all binding and requests will be
 * performed on services bound as the current user.
 */
interface ControlsBindingController : UserAwareController {

    /**
     * Request bind to a service and load all controls.
     *
     * @param component The [ComponentName] of the service to bind
     * @param callback a callback to return the loaded controls to (or an error).
     */
    fun bindAndLoad(component: ComponentName, callback: LoadCallback)

    /**
     * Request to bind to the given services.
     *
     * @param components a list of [ComponentName] of the services to bind
     */
    fun bindServices(components: List<ComponentName>)

    /**
     * Send a subscribe message to retrieve status of a set of controls.
     *
     * The controls passed do not have to belong to a single [ControlsProviderService]. The
     * corresponding service [ComponentName] is associated with each control.
     *
     * @param controls a list of controls with corresponding [ComponentName] to request status
     *                 update
     */
    fun subscribe(controls: List<ControlInfo>)

    /**
     * Send an action performed on a [Control].
     *
     * @param controlInfo information about the actioned control, including the [ComponentName]
     * @param action the action performed on the control
     */
    fun action(controlInfo: ControlInfo, action: ControlAction)

    /**
     * Unsubscribe from all services to stop status updates.
     */
    fun unsubscribe()

    /**
     * Notify this controller that this component has been removed (uninstalled).
     */
    fun onComponentRemoved(componentName: ComponentName)

    /**
     * Consumer for load calls.
     *
     * Supports also sending error messages.
     */
    interface LoadCallback : Consumer<List<Control>> {

        /**
         * Indicates an error loading.
         *
         * @message an error message.
         */
        fun error(message: String)
    }
}