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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.applications.ServiceListing
import com.android.settingslib.widget.CandidateInfo
import com.android.systemui.Dumpable
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.ActivityTaskManagerProxy
import com.android.systemui.util.asIndenting
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private fun createServiceListing(context: Context): ServiceListing {
    return ServiceListing.Builder(context).apply {
        setIntentAction(ControlsProviderService.SERVICE_CONTROLS)
        setPermission("android.permission.BIND_CONTROLS")
        setNoun("Controls Provider")
        setSetting("controls_providers")
        setTag("controls_providers")
        setAddDeviceLockedFlags(true)
    }.build()
}

/**
 * Provides a listing of components to be used as ControlsServiceProvider.
 *
 * This controller keeps track of components that satisfy:
 *
 * * Has an intent-filter responding to [ControlsProviderService.CONTROLS_ACTION]
 * * Has the bind permission `android.permission.BIND_CONTROLS`
 */
@SysUISingleton
class ControlsListingControllerImpl @VisibleForTesting constructor(
    private val context: Context,
    @Background private val backgroundExecutor: Executor,
    private val serviceListingBuilder: (Context) -> ServiceListing,
    private val userTracker: UserTracker,
    private val activityTaskManagerProxy: ActivityTaskManagerProxy,
    dumpManager: DumpManager,
    private val featureFlags: FeatureFlags
) : ControlsListingController, Dumpable {

    @Inject
    constructor(
            context: Context,
            @Background executor: Executor,
            userTracker: UserTracker,
            activityTaskManagerProxy: ActivityTaskManagerProxy,
            dumpManager: DumpManager,
            featureFlags: FeatureFlags
    ) : this(
        context,
        executor,
        ::createServiceListing,
        userTracker,
        activityTaskManagerProxy,
        dumpManager,
        featureFlags
    )

    private var serviceListing = serviceListingBuilder(context)
    // All operations in background thread
    private val callbacks = mutableSetOf<ControlsListingController.ControlsListingCallback>()

    companion object {
        private const val TAG = "ControlsListingControllerImpl"
    }

    private var availableServices = emptyList<ControlsServiceInfo>()
    private var userChangeInProgress = AtomicInteger(0)

    override var currentUserId = userTracker.userId
        private set

    private val serviceListingCallback = ServiceListing.Callback { list ->
        Log.d(TAG, "ServiceConfig reloaded, count: ${list.size}")
        val newServices = list.map { ControlsServiceInfo(userTracker.userContext, it) }
        // After here, `list` is not captured, so we don't risk modifying it outside of the callback
        backgroundExecutor.execute {
            if (userChangeInProgress.get() > 0) return@execute
            updateServices(newServices)
        }
    }

    init {
        Log.d(TAG, "Initializing")
        dumpManager.registerDumpable(TAG, this)
        serviceListing.addCallback(serviceListingCallback)
        serviceListing.setListening(true)
        serviceListing.reload()
    }

    private fun updateServices(newServices: List<ControlsServiceInfo>) {
        if (activityTaskManagerProxy.supportsMultiWindow(context)) {
            newServices.forEach {
                it.resolvePanelActivity() }
        }

        if (newServices != availableServices) {
            availableServices = newServices
            callbacks.forEach {
                it.onServicesUpdated(getCurrentServices())
            }
        }
    }

    override fun changeUser(newUser: UserHandle) {
        userChangeInProgress.incrementAndGet()
        serviceListing.setListening(false)

        backgroundExecutor.execute {
            if (userChangeInProgress.decrementAndGet() == 0) {
                currentUserId = newUser.identifier
                val contextForUser = context.createContextAsUser(newUser, 0)
                serviceListing = serviceListingBuilder(contextForUser)
                serviceListing.addCallback(serviceListingCallback)
                serviceListing.setListening(true)
                serviceListing.reload()
            }
        }
    }

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
            if (userChangeInProgress.get() > 0) {
                // repost this event, as callers may rely on the initial callback from
                // onServicesUpdated
                addCallback(listener)
            } else {
                val services = getCurrentServices()
                Log.d(TAG, "Subscribing callback, service count: ${services.size}")
                callbacks.add(listener)
                listener.onServicesUpdated(services)
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
            Log.d(TAG, "Unsubscribing callback")
            callbacks.remove(listener)
        }
    }

    /**
     * @return a list of components that satisfy the requirements to be a
     *         [ControlsProviderService]
     */
    override fun getCurrentServices(): List<ControlsServiceInfo> =
            availableServices.map(ControlsServiceInfo::copy)

    @WorkerThread
    override fun forceReload() {
        val packageManager = context.packageManager
        val intent = Intent(ControlsProviderService.SERVICE_CONTROLS)
        val user = userTracker.userHandle
        val flags = PackageManager.GET_SERVICES or
                PackageManager.GET_META_DATA or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE or
                PackageManager.MATCH_DIRECT_BOOT_AWARE
        val services = packageManager.queryIntentServicesAsUser(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
                user
        ).map { ControlsServiceInfo(userTracker.userContext, it.serviceInfo) }
        updateServices(services)
    }

    /**
     * Get the localized label for the component.
     *
     * @param name the name of the component
     * @return a label as returned by [CandidateInfo.loadLabel] or `null`.
     */
    override fun getAppLabel(name: ComponentName): CharSequence? {
        return availableServices.firstOrNull { it.componentName == name }
                ?.loadLabel()
    }

    override fun dump(writer: PrintWriter, args: Array<out String>) {
        writer.println("ControlsListingController:")
        writer.asIndenting().indentIfPossible {
            println("Callbacks: $callbacks")
            println("Services: ${getCurrentServices()}")
        }
    }
}
