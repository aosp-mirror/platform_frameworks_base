/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.policy.DeviceControlsController.Callback
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject

/**
 * Watches for Device Controls QS Tile activation, which can happen in two ways:
 * <ol>
 * <li>Migration from Power Menu - For existing Android 11 users, create a tile in a high priority
 *   position.
 * <li>Device controls service becomes available - For non-migrated users, create a tile and place
 *   at the end of active tiles, and initiate seeding where possible.
 * </ol>
 */
@SysUISingleton
public class DeviceControlsControllerImpl
@Inject
constructor(
    private val context: Context,
    private val controlsComponent: ControlsComponent,
    private val userContextProvider: UserContextProvider,
    private val secureSettings: SecureSettings
) : DeviceControlsController {

    private var callback: Callback? = null
    internal var position: Int? = null

    private val listingCallback =
        object : ControlsListingController.ControlsListingCallback {
            override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                if (!serviceInfos.isEmpty()) {
                    seedFavorites(serviceInfos)
                }
            }
        }

    companion object {
        private const val TAG = "DeviceControlsControllerImpl"
        internal const val QS_PRIORITY_POSITION = 3
        internal const val QS_DEFAULT_POSITION = 7

        internal const val PREFS_CONTROLS_SEEDING_COMPLETED = "SeedingCompleted"
        const val PREFS_CONTROLS_FILE = "controls_prefs"
        private const val SEEDING_MAX = 2
    }

    private fun checkMigrationToQs() {
        controlsComponent.getControlsController().ifPresent {
            if (!it.getFavorites().isEmpty()) {
                position = QS_PRIORITY_POSITION
                fireControlsUpdate()
            }
        }
    }

    /**
     * This migration logic assumes that something like [AutoAddTracker] is tracking state
     * externally, and won't call this method after receiving a response via
     * [Callback#onControlsUpdate], once per user. Otherwise the calculated position may be
     * incorrect.
     */
    override fun setCallback(callback: Callback) {
        if (!controlsComponent.isEnabled()) {
            callback.removeControlsAutoTracker()
            return
        }
        // Treat any additional call as a reset before recalculating
        removeCallback()
        this.callback = callback

        if (secureSettings.getInt(Settings.Secure.CONTROLS_ENABLED, 1) == 0) {
            fireControlsUpdate()
        } else {
            checkMigrationToQs()
            controlsComponent.getControlsListingController().ifPresent {
                it.addCallback(listingCallback)
            }
        }
    }

    override fun removeCallback() {
        position = null
        callback = null
        controlsComponent.getControlsListingController().ifPresent {
            it.removeCallback(listingCallback)
        }
    }

    private fun fireControlsUpdate() {
        Log.i(TAG, "Setting DeviceControlsTile position: $position")
        callback?.onControlsUpdate(position)
    }

    /**
     * See if any available control service providers match one of the preferred components. If they
     * do, and there are no current favorites for that component, query the preferred component for
     * a limited number of suggested controls.
     */
    private fun seedFavorites(serviceInfos: List<ControlsServiceInfo>) {
        val preferredControlsPackages =
            context.getResources().getStringArray(R.array.config_controlsPreferredPackages)

        val prefs =
            userContextProvider.userContext.getSharedPreferences(
                PREFS_CONTROLS_FILE,
                Context.MODE_PRIVATE
            )
        val seededPackages =
            prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, emptySet()) ?: emptySet()

        val controlsController = controlsComponent.getControlsController().get()
        val componentsToSeed = mutableListOf<ComponentName>()
        var i = 0
        while (i < Math.min(SEEDING_MAX, preferredControlsPackages.size)) {
            val pkg = preferredControlsPackages[i]
            serviceInfos.forEach {
                if (pkg.equals(it.componentName.packageName) && !seededPackages.contains(pkg)) {
                    if (controlsController.countFavoritesForComponent(it.componentName) > 0) {
                        // When there are existing controls but no saved preference, assume it
                        // is out of sync, perhaps through a device restore, and update the
                        // preference
                        addPackageToSeededSet(prefs, pkg)
                    } else if (it.panelActivity != null) {
                        // Do not seed for packages with panels
                        addPackageToSeededSet(prefs, pkg)
                    } else {
                        componentsToSeed.add(it.componentName)
                    }
                }
            }
            i++
        }

        if (componentsToSeed.isEmpty()) return

        controlsController.seedFavoritesForComponents(
            componentsToSeed,
            { response ->
                Log.d(TAG, "Controls seeded: $response")
                if (response.accepted) {
                    addPackageToSeededSet(prefs, response.packageName)
                    if (position == null) {
                        position = QS_DEFAULT_POSITION
                    }
                    fireControlsUpdate()

                    controlsComponent.getControlsListingController().ifPresent {
                        it.removeCallback(listingCallback)
                    }
                }
            }
        )
    }

    private fun addPackageToSeededSet(prefs: SharedPreferences, pkg: String) {
        val seededPackages =
            prefs.getStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, emptySet()) ?: emptySet()
        val updatedPkgs = seededPackages.toMutableSet()
        updatedPkgs.add(pkg)
        prefs.edit().putStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, updatedPkgs).apply()
    }
}
