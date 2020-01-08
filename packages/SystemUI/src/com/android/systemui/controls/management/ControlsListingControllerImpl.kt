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
import android.content.Context
import android.content.pm.ServiceInfo
import android.service.controls.ControlsProviderService
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.applications.DefaultAppInfo
import com.android.settingslib.applications.ServiceListing
import com.android.settingslib.widget.CandidateInfo
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a listing of components to be used as ControlsServiceProvider.
 *
 * This controller keeps track of components that satisfy:
 *
 * * Has an intent-filter responding to [ControlsProviderService.CONTROLS_ACTION]
 * * Has the bind permission `android.permission.BIND_CONTROLS`
 */
@Singleton
class ControlsListingControllerImpl @VisibleForTesting constructor(
    private val context: Context,
    @Background private val backgroundExecutor: Executor,
    private val serviceListing: ServiceListing
) : ControlsListingController {

    @Inject
    constructor(context: Context, executor: Executor): this(
            context,
            executor,
            ServiceListing.Builder(context)
                    .setIntentAction(ControlsProviderService.SERVICE_CONTROLS)
                    .setPermission("android.permission.BIND_CONTROLS")
                    .setNoun("Controls Provider")
                    .setSetting("controls_providers")
                    .setTag("controls_providers")
                    .build()
    )

    companion object {
        private const val TAG = "ControlsListingControllerImpl"
    }

    private var availableServices = emptyList<ServiceInfo>()

    init {
        serviceListing.addCallback {
            Log.d(TAG, "ServiceConfig reloaded")
            availableServices = it.toList()

            backgroundExecutor.execute {
                callbacks.forEach {
                    it.onServicesUpdated(getCurrentServices())
                }
            }
        }
    }

    // All operations in background thread
    private val callbacks = mutableSetOf<ControlsListingController.ControlsListingCallback>()

    /**
     * Adds a callback to this controller.
     *
     * The callback will be notified after it is added as well as any time that the valid
     * components change.
     *
     * @param listener a callback to be notified
     */
    override fun addCallback(listener: ControlsListingController.ControlsListingCallback) {
        backgroundExecutor.execute {
            callbacks.add(listener)
            if (callbacks.size == 1) {
                serviceListing.setListening(true)
                serviceListing.reload()
            } else {
                listener.onServicesUpdated(getCurrentServices())
            }
        }
    }

    /**
     * Removes a callback from this controller.
     *
     * @param listener the callback to be removed.
     */
    override fun removeCallback(listener: ControlsListingController.ControlsListingCallback) {
        backgroundExecutor.execute {
            callbacks.remove(listener)
            if (callbacks.size == 0) {
                serviceListing.setListening(false)
            }
        }
    }

    /**
     * @return a list of components that satisfy the requirements to be a
     *         [ControlsProviderService]
     */
    override fun getCurrentServices(): List<CandidateInfo> =
            availableServices.map { ControlsServiceInfo(context, it) }

    /**
     * Get the localized label for the component.
     *
     * @param name the name of the component
     * @return a label as returned by [CandidateInfo.loadLabel] or `null`.
     */
    override fun getAppLabel(name: ComponentName): CharSequence? {
        return getCurrentServices().firstOrNull { (it as? DefaultAppInfo)?.componentName == name }
                ?.loadLabel()
    }
}