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

import android.annotation.WorkerThread
import android.content.ComponentName
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.util.UserAwareController
import com.android.systemui.statusbar.policy.CallbackController

/**
 * Controller for keeping track of services that can be bound given a particular [ServiceListing].
 */
interface ControlsListingController :
        CallbackController<ControlsListingController.ControlsListingCallback>,
    UserAwareController {

    /**
     * @return the current list of services that satisfies the [ServiceListing].
     */
    fun getCurrentServices(): List<ControlsServiceInfo>

    @WorkerThread
    fun forceReload()

    /**
     * Get the app label for a given component.
     *
     * This call may do Binder calls (to [PackageManager])
     *
     * @param name the component name to retrieve the label
     * @return the label for the component
     */
    fun getAppLabel(name: ComponentName): CharSequence? = ""

    @FunctionalInterface
    interface ControlsListingCallback {
        fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>)
    }
}
