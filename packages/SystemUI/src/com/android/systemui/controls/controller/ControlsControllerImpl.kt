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
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.UserHandle
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.util.ArrayMap
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControlsControllerImpl @Inject constructor (
    private val context: Context,
    @Background private val executor: DelayableExecutor,
    private val uiController: ControlsUiController,
    private val bindingController: ControlsBindingController,
    private val listingController: ControlsListingController,
    private val broadcastDispatcher: BroadcastDispatcher,
    optionalWrapper: Optional<ControlsFavoritePersistenceWrapper>,
    dumpManager: DumpManager
) : Dumpable, ControlsController {

    companion object {
        private const val TAG = "ControlsControllerImpl"
        internal const val CONTROLS_AVAILABLE = "systemui.controls_available"
        internal val URI = Settings.Secure.getUriFor(CONTROLS_AVAILABLE)
        private const val USER_CHANGE_RETRY_DELAY = 500L // ms
        private const val DEFAULT_ENABLED = 1
    }

    // Map of map: ComponentName -> (String -> ControlInfo).
    //
    @GuardedBy("currentFavorites")
    private val currentFavorites = ArrayMap<ComponentName, MutableList<ControlInfo>>()
            .withDefault { mutableListOf() }

    private var userChanging: Boolean = true

    private val contentResolver: ContentResolver
        get() = context.contentResolver
    override var available = Settings.Secure.getInt(
            contentResolver, CONTROLS_AVAILABLE, DEFAULT_ENABLED) != 0
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
        available = Settings.Secure.getIntForUser(contentResolver, CONTROLS_AVAILABLE,
                /* default */ DEFAULT_ENABLED, newUser.identifier) != 0
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
                listingController.removeCallback(listingCallback)
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

    @VisibleForTesting
    internal val settingObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri, userId: Int) {
            // Do not listen to changes in the middle of user change, those will be read by the
            // user-switch receiver.
            if (userChanging || userId != currentUserId) {
                return
            }
            available = Settings.Secure.getIntForUser(contentResolver, CONTROLS_AVAILABLE,
                    /* default */ DEFAULT_ENABLED, currentUserId) != 0
            synchronized(currentFavorites) {
                currentFavorites.clear()
            }
            if (available) {
                loadFavorites()
            }
        }
    }

    // Handling of removed components

    /**
     * Check if any component has been removed and if so, remove all its favorites.
     *
     * If some component has been removed, the new set of favorites will also be saved.
     */
    private val listingCallback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(candidates: List<ControlsServiceInfo>) {
            executor.execute {
                val candidateComponents = candidates.map(ControlsServiceInfo::componentName)
                synchronized(currentFavorites) {
                    val components = currentFavorites.keys.toSet() // create a copy
                    components.forEach {
                        if (it !in candidateComponents) {
                            currentFavorites.remove(it)
                            bindingController.onComponentRemoved(it)
                        }
                    }
                    // Check if something has been removed, if so, store the new list
                    if (components.size > currentFavorites.size) {
                        persistenceWrapper.storeFavorites(favoritesAsListLocked())
                    }
                }
            }
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
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
        contentResolver.registerContentObserver(URI, false, settingObserver, UserHandle.USER_ALL)
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
                currentFavorites.getOrPut(it.component, { mutableListOf() }).add(it)
            }
        }
        listingController.addCallback(listingCallback)
    }

    override fun loadForComponent(
        componentName: ComponentName,
        dataCallback: Consumer<ControlsController.LoadData>
    ) {
        if (!confirmAvailability()) {
            if (userChanging) {
                // Try again later, userChanging should not last forever. If so, we have bigger
                // problems
                executor.executeDelayed(
                        { loadForComponent(componentName, dataCallback) },
                        USER_CHANGE_RETRY_DELAY,
                        TimeUnit.MILLISECONDS
                )
            } else {
                dataCallback.accept(createLoadDataObject(emptyList(), emptyList(), true))
            }
            return
        }
        bindingController.bindAndLoad(
                componentName,
                object : ControlsBindingController.LoadCallback {
                    override fun accept(controls: List<Control>) {
                        val loadData = synchronized(currentFavorites) {
                            val favoritesForComponentKeys: List<String> =
                                    currentFavorites.getValue(componentName).map { it.controlId }
                            val changed = updateFavoritesLocked(componentName, controls,
                                    favoritesForComponentKeys)
                            if (changed) {
                                persistenceWrapper.storeFavorites(favoritesAsListLocked())
                            }
                            val removed = findRemovedLocked(favoritesForComponentKeys.toSet(),
                                    controls)
                            val controlsWithFavorite = controls.map {
                                ControlStatus(it, it.controlId in favoritesForComponentKeys)
                            }
                            createLoadDataObject(
                                    currentFavorites.getValue(componentName)
                                            .filter { it.controlId in removed }
                                            .map { createRemovedStatus(it) } +
                                            controlsWithFavorite,
                                    favoritesForComponentKeys
                            )
                        }
                        dataCallback.accept(loadData)
                    }

                    override fun error(message: String) {
                        val loadData = synchronized(currentFavorites) {
                            val favoritesForComponent = currentFavorites.getValue(componentName)
                            val favoritesForComponentKeys = favoritesForComponent
                                    .map { it.controlId }
                            createLoadDataObject(
                                    favoritesForComponent.map { createRemovedStatus(it, false) },
                                    favoritesForComponentKeys,
                                    true
                            )
                        }
                        dataCallback.accept(loadData)
                    }
                }
        )
    }

    private fun createRemovedStatus(
        controlInfo: ControlInfo,
        setRemoved: Boolean = true
    ): ControlStatus {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.`package` = controlInfo.component.packageName
        }
        val pendingIntent = PendingIntent.getActivity(context,
                controlInfo.component.hashCode(),
                intent,
                0)
        val control = Control.StatelessBuilder(controlInfo.controlId, pendingIntent)
                .setTitle(controlInfo.controlTitle)
                .setDeviceType(controlInfo.deviceType)
                .build()
        return ControlStatus(control, true, setRemoved)
    }

    @GuardedBy("currentFavorites")
    private fun findRemovedLocked(favoriteKeys: Set<String>, list: List<Control>): Set<String> {
        val controlsKeys = list.map { it.controlId }
        return favoriteKeys.minus(controlsKeys)
    }

    @GuardedBy("currentFavorites")
    private fun updateFavoritesLocked(
        componentName: ComponentName,
        list: List<Control>,
        favoriteKeys: List<String>
    ): Boolean {
        val favorites = currentFavorites.get(componentName) ?: mutableListOf()
        if (favoriteKeys.isEmpty()) return false // early return
        var changed = false
        list.forEach { control ->
            if (control.controlId in favoriteKeys) {
                val index = favorites.indexOfFirst { it.controlId == control.controlId }
                val value = favorites[index]
                if (value.controlTitle != control.title ||
                        value.deviceType != control.deviceType) {
                    favorites[index] = value.copy(
                            controlTitle = control.title,
                            deviceType = control.deviceType
                    )
                    changed = true
                }
            }
        }
        return changed
    }

    @GuardedBy("currentFavorites")
    private fun favoritesAsListLocked(): List<ControlInfo> {
        return currentFavorites.flatMap { it.value }
    }

    override fun subscribeToFavorites() {
        if (!confirmAvailability()) return
        // Make a copy of the favorites list
        val favorites = synchronized(currentFavorites) {
            currentFavorites.flatMap { it.value }
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
                    currentFavorites.put(controlInfo.component, mutableListOf())
                    changed = true
                }
                val controlsForComponent = currentFavorites.getValue(controlInfo.component)
                if (controlsForComponent.firstOrNull {
                            it.controlId == controlInfo.controlId
                        } == null) {
                    controlsForComponent.add(controlInfo)
                    changed = true
                }
            } else {
                changed = currentFavorites.get(controlInfo.component)
                        ?.remove(controlInfo) != null
            }
            favoritesAsListLocked()
        }
        if (changed) {
            persistenceWrapper.storeFavorites(listOfControls)
        }
    }

    override fun replaceFavoritesForComponent(
        componentName: ComponentName,
        favorites: List<ControlInfo>
    ) {
        if (!confirmAvailability()) return
        val filtered = favorites.filter { it.component == componentName }
        val listOfControls = synchronized(currentFavorites) {
            currentFavorites.put(componentName, filtered.toMutableList())
            favoritesAsListLocked()
        }
        persistenceWrapper.storeFavorites(listOfControls)
    }

    override fun refreshStatus(componentName: ComponentName, control: Control) {
        if (!confirmAvailability()) {
            Log.d(TAG, "Controls not available")
            return
        }
        executor.execute {
            synchronized(currentFavorites) {
                val favoriteKeysForComponent =
                        currentFavorites.get(componentName)?.map { it.controlId } ?: emptyList()
                val changed = updateFavoritesLocked(
                        componentName,
                        listOf(control),
                        favoriteKeysForComponent
                )
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

    override fun countFavoritesForComponent(componentName: ComponentName): Int {
        return synchronized(currentFavorites) {
            currentFavorites.get(componentName)?.size ?: 0
        }
    }

    override fun getFavoritesForComponent(componentName: ComponentName): List<ControlInfo> {
        return synchronized(currentFavorites) {
            currentFavorites.get(componentName) ?: emptyList()
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("ControlsController state:")
        pw.println("  Available: $available")
        pw.println("  Changing users: $userChanging")
        pw.println("  Current user: ${currentUser.identifier}")
        pw.println("  Favorites:")
        synchronized(currentFavorites) {
            favoritesAsListLocked().forEach {
                pw.println("    ${ it }")
            }
        }
        pw.println(bindingController.toString())
    }
}