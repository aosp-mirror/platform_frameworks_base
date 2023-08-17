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

import android.content.Context
import android.service.controls.Control

/**
 * All control interactions should be routed through this coordinator. It handles dispatching of
 * actions, haptic support, and all detail panels
 */
interface ControlActionCoordinator {

    // If launched from an Activity, continue within this stack
    var activityContext: Context

    /**
     * Close any dialogs which may have been open
     */
    fun closeDialogs()

    /**
     * Create a [BooleanAction], and inform the service of a request to change the device state
     *
     * @param cvh [ControlViewHolder] for the control
     * @param templateId id of the control's template, as given by the service
     * @param isChecked new requested state of the control
     */
    fun toggle(cvh: ControlViewHolder, templateId: String, isChecked: Boolean)

    /**
     * For non-toggle controls, touching may create a dialog or invoke a [CommandAction].
     *
     * @param cvh [ControlViewHolder] for the control
     * @param templateId id of the control's template, as given by the service
     * @param control the control as sent by the service
     */
    fun touch(cvh: ControlViewHolder, templateId: String, control: Control)

    /**
     * When a ToggleRange control is interacting with, a drag event is sent.
     *
     * @param cvh [ControlViewHolder] for the control
     * @param isEdge did the drag event reach a control edge
     */
    fun drag(cvh: ControlViewHolder, isEdge: Boolean)

    /**
     * Send a request to update the value of a device using the [FloatAction].
     *
     * @param cvh [ControlViewHolder] for the control
     * @param templateId id of the control's template, as given by the service
     * @param newValue value to set for the device
     */
    fun setValue(cvh: ControlViewHolder, templateId: String, newValue: Float)

    /**
     * Actions may have been put on hold while the device is unlocked. Invoke this action if
     * present.
     */
    fun runPendingAction(controlId: String)

    /**
     * User interaction with a control may be blocked for a period of time while actions are being
     * executed by the application.  When the response returns, run this method to enable further
     * user interaction.
     */
    fun enableActionOnTouch(controlId: String)

    /**
     * All long presses will be shown in a 3/4 height bottomsheet panel, in order for the user to
     * retain context with their favorited controls in the power menu.
     */
    fun longPress(cvh: ControlViewHolder)
}
