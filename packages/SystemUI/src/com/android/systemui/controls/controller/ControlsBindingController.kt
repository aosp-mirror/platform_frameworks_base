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
import com.android.systemui.util.UserAwareController
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
     * @return a runnable to cancel the load
     */
    fun bindAndLoad(component: ComponentName, callback: LoadCallback): Runnable

    /**
     * Request bind to a service and load a limited number of suggested controls.
     *
     * @param component The [ComponentName] of the service to bind
     * @param callback a callback to return the loaded controls to (or an error).
     */
    fun bindAndLoadSuggested(component: ComponentName, callback: LoadCallback)

    /**
     * Request to bind to the given service. This should only be used for services using the full
     * [ControlsProviderService] API, where SystemUI renders the devices' UI.
     *
     * @param component The [ComponentName] of the service to bind
     */
    fun bindService(component: ComponentName)

    /**
     * Bind to a service that provides a Device Controls panel (embedded activity). This will allow
     * the app to remain "warm", and reduce latency.
     *
     * @param component The [ComponentName] of the [ControlsProviderService] to bind.
     */
    fun bindServiceForPanel(component: ComponentName)

    /**
     * Send a subscribe message to retrieve status of a set of controls.
     *
     * @param structureInfo structure containing the controls to update
     */
    fun subscribe(structureInfo: StructureInfo)

    /**
     * Send an action performed on a [Control].
     *
     * @param componentName name of the component
     * @param controlInfo information about the actioned control
     * @param action the action performed on the control
     */
    fun action(componentName: ComponentName, controlInfo: ControlInfo, action: ControlAction)

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
