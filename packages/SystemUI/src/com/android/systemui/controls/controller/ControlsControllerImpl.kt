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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.UserHandle
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.util.ArrayMap
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.systemui.DumpController
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControlsControllerImpl @Inject constructor (
    private val context: Context,
    @Background private val executor: DelayableExecutor,
    private val uiController: ControlsUiController,
    private val bindingController: ControlsBindingController,
    private val listingController: ControlsListingController,
    broadcastDispatcher: BroadcastDispatcher,
    optionalWrapper: Optional<ControlsFavoritePersistenceWrapper>,
    dumpController: DumpController
) : Dumpable, ControlsController {

    companion object {
        private const val TAG = "ControlsControllerImpl"
        const val CONTROLS_AVAILABLE = "systemui.controls_available"
        const val USER_CHANGE_RETRY_DELAY = 500L // ms
    }

    // Map of map: ComponentName -> (String -> ControlInfo).
    // Only for current user
    @GuardedBy("currentFavorites")
    private val currentFavorites = ArrayMap<ComponentName, MutableMap<String, ControlInfo>>()

    private var userChanging = true
    override var available = Settings.Secure.getInt(
            context.contentResolver, CONTROLS_AVAILABLE, 0) != 0
        private set

    private var currentUser = context.user
    override val currentUserId
        get() = currentUser.identifier

    private val persistenceWrapper = optionalWrapper.orElseGet {
        ControlsFavoritePersistenceWrapper(
                Environment.buildPath(
                    context.filesDir,
                    ControlsFavoritePersistenceWrapper.FILE_NAME
                ),
                executor
        )
    }

    private fun setValuesForUser(newUser: UserHandle) {
        Log.d(TAG, "Changing to user: $newUser")
        currentUser = newUser
        val userContext = context.createContextAsUser(currentUser, 0)
        val fileName = Environment.buildPath(
                userContext.filesDir, ControlsFavoritePersistenceWrapper.FILE_NAME)
        persistenceWrapper.changeFile(fileName)
        available = Settings.Secure.getIntForUser(
                context.contentResolver, CONTROLS_AVAILABLE, 0) != 0
        synchronized(currentFavorites) {
            currentFavorites.clear()
        }
        if (available) {
            loadFavorites()
        }
        bindingController.changeUser(newUser)
        listingController.changeUser(newUser)
        userChanging = false
    }

    private val userSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_SWITCHED) {
                userChanging = true
                val newUser =
                        UserHandle.of(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, sendingUserId))
                if (currentUser == newUser) {
                    userChanging = false
                    return
                }
                setValuesForUser(newUser)
            }
        }
    }

    init {
        dumpController.registerDumpable(this)
        if (available) {
            loadFavorites()
        }
        userChanging = false
        broadcastDispatcher.registerReceiver(
                userSwitchReceiver,
                IntentFilter(Intent.ACTION_USER_SWITCHED),
                executor,
                UserHandle.ALL
        )
    }

    private fun confirmAvailability(): Boolean {
        if (userChanging) {
            Log.w(TAG, "Controls not available while user is changing")
            return false
        }
        if (!available) {
            Log.d(TAG, "Controls not available")
            return false
        }
        return true
    }

    private fun loadFavorites() {
        val infos = persistenceWrapper.readFavorites()
        synchronized(currentFavorites) {
            infos.forEach {
                currentFavorites.getOrPut(it.component, { ArrayMap<String, ControlInfo>() })
                        .put(it.controlId, it)
            }
        }
    }

    override fun loadForComponent(
        componentName: ComponentName,
        callback: (List<ControlStatus>) -> Unit
    ) {
        if (!confirmAvailability()) {
            if (userChanging) {
                // Try again later, userChanging should not last forever. If so, we have bigger
                // problems
                executor.executeDelayed(
                        { loadForComponent(componentName, callback) },
                        USER_CHANGE_RETRY_DELAY,
                        TimeUnit.MILLISECONDS
                )
            } else {
                callback(emptyList())
            }
            return
        }
        bindingController.bindAndLoad(componentName) {
            synchronized(currentFavorites) {
                val favoritesForComponentKeys: Set<String> =
                        currentFavorites.get(componentName)?.keys ?: emptySet()
                val changed = updateFavoritesLocked(componentName, it)
                if (changed) {
                    persistenceWrapper.storeFavorites(favoritesAsListLocked())
                }
                val removed = findRemovedLocked(favoritesForComponentKeys, it)
                callback(removed.map { currentFavorites.getValue(componentName).getValue(it) }
                            .map(::createRemovedStatus) +
                        it.map { ControlStatus(it, it.controlId in favoritesForComponentKeys) })
            }
        }
    }

    private fun createRemovedStatus(controlInfo: ControlInfo): ControlStatus {
        val intent = Intent(context, ControlsFavoritingActivity::class.java).apply {
            putExtra(ControlsFavoritingActivity.EXTRA_COMPONENT, controlInfo.component)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context,
                controlInfo.component.hashCode(),
                intent,
                0)
        val control = Control.StatelessBuilder(controlInfo.controlId, pendingIntent)
                .setTitle(controlInfo.controlTitle)
                .setDeviceType(controlInfo.deviceType)
                .build()
        return ControlStatus(control, true, true)
    }

    @GuardedBy("currentFavorites")
    private fun findRemovedLocked(favoriteKeys: Set<String>, list: List<Control>): Set<String> {
        val controlsKeys = list.map { it.controlId }
        return favoriteKeys.minus(controlsKeys)
    }

    @GuardedBy("currentFavorites")
    private fun updateFavoritesLocked(componentName: ComponentName, list: List<Control>): Boolean {
        val favorites = currentFavorites.get(componentName) ?: mutableMapOf()
        val favoriteKeys = favorites.keys
        if (favoriteKeys.isEmpty()) return false // early return
        var changed = false
        list.forEach {
            if (it.controlId in favoriteKeys) {
                val value = favorites.getValue(it.controlId)
                if (value.controlTitle != it.title || value.deviceType != it.deviceType) {
                    favorites[it.controlId] = value.copy(controlTitle = it.title,
                            deviceType = it.deviceType)
                    changed = true
                }
            }
        }
        return changed
    }

    @GuardedBy("currentFavorites")
    private fun favoritesAsListLocked(): List<ControlInfo> {
        return currentFavorites.flatMap { it.value.values }
    }

    override fun subscribeToFavorites() {
        if (!confirmAvailability()) return
        // Make a copy of the favorites list
        val favorites = synchronized(currentFavorites) {
            currentFavorites.flatMap { it.value.values.toList() }
        }
        bindingController.subscribe(favorites)
    }

    override fun unsubscribe() {
        if (!confirmAvailability()) return
        bindingController.unsubscribe()
    }

    override fun changeFavoriteStatus(controlInfo: ControlInfo, state: Boolean) {
        if (!confirmAvailability()) return
        var changed = false
        val listOfControls = synchronized(currentFavorites) {
            if (state) {
                if (controlInfo.component !in currentFavorites) {
                    currentFavorites.put(controlInfo.component, ArrayMap<String, ControlInfo>())
                    changed = true
                }
                val controlsForComponent = currentFavorites.getValue(controlInfo.component)
                if (controlInfo.controlId !in controlsForComponent) {
                    controlsForComponent.put(controlInfo.controlId, controlInfo)
                    changed = true
                } else {
                    if (controlsForComponent.getValue(controlInfo.controlId) != controlInfo) {
                        controlsForComponent.put(controlInfo.controlId, controlInfo)
                        changed = true
                    }
                }
            } else {
                changed = currentFavorites.get(controlInfo.component)
                        ?.remove(controlInfo.controlId) != null
            }
            favoritesAsListLocked()
        }
        if (changed) {
            persistenceWrapper.storeFavorites(listOfControls)
        }
    }

    override fun refreshStatus(componentName: ComponentName, control: Control) {
        if (!confirmAvailability()) {
            Log.d(TAG, "Controls not available")
            return
        }
        executor.execute {
            synchronized(currentFavorites) {
                val changed = updateFavoritesLocked(componentName, listOf(control))
                if (changed) {
                    persistenceWrapper.storeFavorites(favoritesAsListLocked())
                }
            }
        }
        uiController.onRefreshState(componentName, listOf(control))
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        if (!confirmAvailability()) return
        uiController.onActionResponse(componentName, controlId, response)
    }

    override fun getFavoriteControls(): List<ControlInfo> {
        if (!confirmAvailability()) return emptyList()
        synchronized(currentFavorites) {
            return favoritesAsListLocked()
        }
    }

    override fun action(controlInfo: ControlInfo, action: ControlAction) {
        if (!confirmAvailability()) return
        bindingController.action(controlInfo, action)
    }

    override fun clearFavorites() {
        if (!confirmAvailability()) return
        val changed = synchronized(currentFavorites) {
            currentFavorites.isNotEmpty().also {
                currentFavorites.clear()
            }
        }
        if (changed) {
            persistenceWrapper.storeFavorites(emptyList())
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("ControlsController state:")
        pw.println("  Available: $available")
        pw.println("  Changing users: $userChanging")
        pw.println("  Current user: ${currentUser.identifier}")
        pw.println("  Favorites:")
        synchronized(currentFavorites) {
            currentFavorites.forEach {
                it.value.forEach {
                    pw.println("    ${it.value}")
                }
            }
        }
        pw.println(bindingController.toString())
    }
}