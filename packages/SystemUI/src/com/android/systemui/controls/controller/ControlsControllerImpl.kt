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

import android.app.ActivityManager
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
        internal const val CONTROLS_AVAILABLE = Settings.Secure.CONTROLS_ENABLED
        internal val URI = Settings.Secure.getUriFor(CONTROLS_AVAILABLE)
        private const val USER_CHANGE_RETRY_DELAY = 500L // ms
        private const val DEFAULT_ENABLED = 1
    }

    private var userChanging: Boolean = true

    private var loadCanceller: Runnable? = null

    private var seedingInProgress = false
    private val seedingCallbacks = mutableListOf<Consumer<Boolean>>()

    private var currentUser = UserHandle.of(ActivityManager.getCurrentUser())
    override val currentUserId
        get() = currentUser.identifier

    private val contentResolver: ContentResolver
        get() = context.contentResolver
    override var available = Settings.Secure.getIntForUser(
            contentResolver, CONTROLS_AVAILABLE, DEFAULT_ENABLED, currentUserId) != 0
        private set

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
                DEFAULT_ENABLED, newUser.identifier) != 0
        resetFavorites(available)
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
        override fun onChange(
            selfChange: Boolean,
            uris: Collection<Uri>,
            flags: Int,
            userId: Int
        ) {
            // Do not listen to changes in the middle of user change, those will be read by the
            // user-switch receiver.
            if (userChanging || userId != currentUserId) {
                return
            }
            available = Settings.Secure.getIntForUser(contentResolver, CONTROLS_AVAILABLE,
                DEFAULT_ENABLED, currentUserId) != 0
            resetFavorites(available)
        }
    }

    // Handling of removed components

    /**
     * Check if any component has been removed and if so, remove all its favorites.
     *
     * If some component has been removed, the new set of favorites will also be saved.
     */
    private val listingCallback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
            executor.execute {
                val serviceInfoSet = serviceInfos.map(ControlsServiceInfo::componentName).toSet()
                val favoriteComponentSet = Favorites.getAllStructures().map {
                    it.componentName
                }.toSet()

                var changed = false
                favoriteComponentSet.subtract(serviceInfoSet).forEach {
                    changed = true
                    Favorites.removeStructures(it)
                    bindingController.onComponentRemoved(it)
                }

                // Check if something has been removed, if so, store the new list
                if (changed) {
                    persistenceWrapper.storeFavorites(Favorites.getAllStructures())
                }
            }
        }
    }

    init {
        dumpManager.registerDumpable(javaClass.name, this)
        resetFavorites(available)
        userChanging = false
        broadcastDispatcher.registerReceiver(
                userSwitchReceiver,
                IntentFilter(Intent.ACTION_USER_SWITCHED),
                executor,
                UserHandle.ALL
        )
        contentResolver.registerContentObserver(URI, false, settingObserver, UserHandle.USER_ALL)
    }

    private fun resetFavorites(shouldLoad: Boolean) {
        Favorites.clear()

        if (shouldLoad) {
            Favorites.load(persistenceWrapper.readFavorites())
            listingController.addCallback(listingCallback)
        }
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

    override fun loadForComponent(
        componentName: ComponentName,
        dataCallback: Consumer<ControlsController.LoadData>
    ) {
        if (!confirmAvailability()) {
            if (userChanging) {
                // Try again later, userChanging should not last forever. If so, we have bigger
                // problems. This will return a runnable that allows to cancel the delayed version,
                // it will not be able to cancel the load if
                loadCanceller = executor.executeDelayed(
                        { loadForComponent(componentName, dataCallback) },
                        USER_CHANGE_RETRY_DELAY,
                        TimeUnit.MILLISECONDS
                )
            } else {
                dataCallback.accept(createLoadDataObject(emptyList(), emptyList(), true))
            }
            return
        }
        loadCanceller = bindingController.bindAndLoad(
                componentName,
                object : ControlsBindingController.LoadCallback {
                    override fun accept(controls: List<Control>) {
                        loadCanceller = null
                        executor.execute {
                            val favoritesForComponentKeys = Favorites
                                .getControlsForComponent(componentName).map { it.controlId }

                            val changed = Favorites.updateControls(componentName, controls)
                            if (changed) {
                                persistenceWrapper.storeFavorites(Favorites.getAllStructures())
                            }
                            val removed = findRemoved(favoritesForComponentKeys.toSet(), controls)
                            val controlsWithFavorite = controls.map {
                                ControlStatus(
                                    it,
                                    componentName,
                                    it.controlId in favoritesForComponentKeys
                                )
                            }
                            val loadData = createLoadDataObject(
                                Favorites.getControlsForComponent(componentName)
                                    .filter { it.controlId in removed }
                                    .map { createRemovedStatus(componentName, it) } +
                                controlsWithFavorite,
                                favoritesForComponentKeys
                            )
                            dataCallback.accept(loadData)
                        }
                    }

                    override fun error(message: String) {
                        loadCanceller = null
                        executor.execute {
                            val loadData = Favorites.getControlsForComponent(componentName)
                                .let { controls ->
                                val keys = controls.map { it.controlId }
                                createLoadDataObject(
                                        controls.map {
                                            createRemovedStatus(componentName, it, false)
                                        },
                                        keys,
                                        true
                                )
                            }
                            dataCallback.accept(loadData)
                        }
                    }
                }
        )
    }

    override fun addSeedingFavoritesCallback(callback: Consumer<Boolean>): Boolean {
        if (!seedingInProgress) return false
        executor.execute {
            // status may have changed by this point, so check again and inform the
            // caller if necessary
            if (seedingInProgress) seedingCallbacks.add(callback)
            else callback.accept(false)
        }
        return true
    }

    override fun seedFavoritesForComponent(
        componentName: ComponentName,
        callback: Consumer<Boolean>
    ) {
        Log.i(TAG, "Beginning request to seed favorites for: $componentName")
        if (!confirmAvailability()) {
            if (userChanging) {
                // Try again later, userChanging should not last forever. If so, we have bigger
                // problems. This will return a runnable that allows to cancel the delayed version,
                // it will not be able to cancel the load if
                executor.executeDelayed(
                    { seedFavoritesForComponent(componentName, callback) },
                    USER_CHANGE_RETRY_DELAY,
                    TimeUnit.MILLISECONDS
                )
            } else {
                callback.accept(false)
            }
            return
        }
        seedingInProgress = true
        bindingController.bindAndLoadSuggested(
            componentName,
            object : ControlsBindingController.LoadCallback {
                override fun accept(controls: List<Control>) {
                    executor.execute {
                        val structureToControls =
                            ArrayMap<CharSequence, MutableList<ControlInfo>>()

                        controls.forEach {
                            val structure = it.structure ?: ""
                            val list = structureToControls.get(structure)
                                ?: mutableListOf<ControlInfo>()
                            list.add(
                                ControlInfo(it.controlId, it.title, it.subtitle, it.deviceType))
                            structureToControls.put(structure, list)
                        }

                        structureToControls.forEach {
                            (s, cs) -> Favorites.replaceControls(
                                StructureInfo(componentName, s, cs))
                        }

                        persistenceWrapper.storeFavorites(Favorites.getAllStructures())
                        callback.accept(true)
                        endSeedingCall(true)
                    }
                }

                override fun error(message: String) {
                    Log.e(TAG, "Unable to seed favorites: $message")
                    executor.execute {
                        callback.accept(false)
                        endSeedingCall(false)
                    }
                }
            }
        )
    }

    private fun endSeedingCall(state: Boolean) {
        seedingInProgress = false
        seedingCallbacks.forEach {
            it.accept(state)
        }
        seedingCallbacks.clear()
    }

    override fun cancelLoad() {
        loadCanceller?.let {
            executor.execute(it)
        }
    }

    private fun createRemovedStatus(
        componentName: ComponentName,
        controlInfo: ControlInfo,
        setRemoved: Boolean = true
    ): ControlStatus {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.`package` = componentName.packageName
        }
        val pendingIntent = PendingIntent.getActivity(context,
                componentName.hashCode(),
                intent,
                0)
        val control = Control.StatelessBuilder(controlInfo.controlId, pendingIntent)
                .setTitle(controlInfo.controlTitle)
                .setDeviceType(controlInfo.deviceType)
                .build()
        return ControlStatus(control, componentName, true, setRemoved)
    }

    private fun findRemoved(favoriteKeys: Set<String>, list: List<Control>): Set<String> {
        val controlsKeys = list.map { it.controlId }
        return favoriteKeys.minus(controlsKeys)
    }

    override fun subscribeToFavorites(structureInfo: StructureInfo) {
        if (!confirmAvailability()) return

        bindingController.subscribe(structureInfo)
    }

    override fun unsubscribe() {
        if (!confirmAvailability()) return
        bindingController.unsubscribe()
    }

    override fun addFavorite(
        componentName: ComponentName,
        structureName: CharSequence,
        controlInfo: ControlInfo
    ) {
        if (!confirmAvailability()) return
        executor.execute {
            if (Favorites.addFavorite(componentName, structureName, controlInfo)) {
                persistenceWrapper.storeFavorites(Favorites.getAllStructures())
            }
        }
    }

    override fun replaceFavoritesForStructure(structureInfo: StructureInfo) {
        if (!confirmAvailability()) return
        executor.execute {
            Favorites.replaceControls(structureInfo)
            persistenceWrapper.storeFavorites(Favorites.getAllStructures())
        }
    }

    override fun refreshStatus(componentName: ComponentName, control: Control) {
        if (!confirmAvailability()) {
            Log.d(TAG, "Controls not available")
            return
        }
        executor.execute {
            val changed = Favorites.updateControls(
                componentName,
                listOf(control)
            )
            if (changed) {
                persistenceWrapper.storeFavorites(Favorites.getAllStructures())
            }
        }
        uiController.onRefreshState(componentName, listOf(control))
    }

    override fun onActionResponse(componentName: ComponentName, controlId: String, response: Int) {
        if (!confirmAvailability()) return
        uiController.onActionResponse(componentName, controlId, response)
    }

    override fun action(
        componentName: ComponentName,
        controlInfo: ControlInfo,
        action: ControlAction
    ) {
        if (!confirmAvailability()) return
        bindingController.action(componentName, controlInfo, action)
    }

    override fun getFavorites(): List<StructureInfo> = Favorites.getAllStructures()

    override fun countFavoritesForComponent(componentName: ComponentName): Int =
        Favorites.getControlsForComponent(componentName).size

    override fun getFavoritesForComponent(componentName: ComponentName): List<StructureInfo> =
        Favorites.getStructuresForComponent(componentName)

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("ControlsController state:")
        pw.println("  Available: $available")
        pw.println("  Changing users: $userChanging")
        pw.println("  Current user: ${currentUser.identifier}")
        pw.println("  Favorites:")
        Favorites.getAllStructures().forEach { s ->
            pw.println("    ${ s }")
            s.controls.forEach { c ->
                pw.println("      ${ c }")
            }
        }
        pw.println(bindingController.toString())
    }
}

/**
 * Relies on immutable data for thread safety. When necessary to update favMap, use reassignment to
 * replace it, which will not disrupt any ongoing map traversal.
 *
 * Update/replace calls should use thread isolation to avoid race conditions.
 */
private object Favorites {
    private var favMap = mapOf<ComponentName, List<StructureInfo>>()

    fun getAllStructures(): List<StructureInfo> = favMap.flatMap { it.value }

    fun getStructuresForComponent(componentName: ComponentName): List<StructureInfo> =
        favMap.get(componentName) ?: emptyList()

    fun getControlsForStructure(structure: StructureInfo): List<ControlInfo> =
        getStructuresForComponent(structure.componentName)
            .firstOrNull { it.structure == structure.structure }
            ?.controls ?: emptyList()

    fun getControlsForComponent(componentName: ComponentName): List<ControlInfo> =
        getStructuresForComponent(componentName).flatMap { it.controls }

    fun load(structures: List<StructureInfo>) {
        favMap = structures.groupBy { it.componentName }
    }

    fun updateControls(componentName: ComponentName, controls: List<Control>): Boolean {
        val controlsById = controls.associateBy { it.controlId }

        // utilize a new map to allow for changes to structure names
        val structureToControls = mutableMapOf<CharSequence, MutableList<ControlInfo>>()

        // Must retain the current control order within each structure
        var changed = false
        getStructuresForComponent(componentName).forEach { s ->
            s.controls.forEach { c ->
                val (sName, ci) = controlsById.get(c.controlId)?.let { updatedControl ->
                    val controlInfo = if (updatedControl.title != c.controlTitle ||
                        updatedControl.subtitle != c.controlSubtitle ||
                        updatedControl.deviceType != c.deviceType) {
                        changed = true
                        c.copy(
                            controlTitle = updatedControl.title,
                            controlSubtitle = updatedControl.subtitle,
                            deviceType = updatedControl.deviceType
                        )
                    } else { c }

                    val updatedStructure = updatedControl.structure ?: ""
                    if (s.structure != updatedStructure) {
                        changed = true
                    }

                    Pair(updatedStructure, controlInfo)
                } ?: Pair(s.structure, c)

                structureToControls.getOrPut(sName, { mutableListOf() }).add(ci)
            }
        }
        if (!changed) return false

        val structures = structureToControls.map { (s, cs) -> StructureInfo(componentName, s, cs) }

        val newFavMap = favMap.toMutableMap()
        newFavMap.put(componentName, structures)
        favMap = newFavMap

        return true
    }

    fun removeStructures(componentName: ComponentName) {
        val newFavMap = favMap.toMutableMap()
        newFavMap.remove(componentName)
        favMap = newFavMap
    }

    fun addFavorite(
        componentName: ComponentName,
        structureName: CharSequence,
        controlInfo: ControlInfo
    ): Boolean {
        // Check if control is in favorites
        if (getControlsForComponent(componentName)
                        .any { it.controlId == controlInfo.controlId }) {
            return false
        }
        val structureInfo = favMap.get(componentName)
                ?.firstOrNull { it.structure == structureName }
                ?: StructureInfo(componentName, structureName, emptyList())
        val newStructureInfo = structureInfo.copy(controls = structureInfo.controls + controlInfo)
        replaceControls(newStructureInfo)
        return true
    }

    fun replaceControls(updatedStructure: StructureInfo) {
        val newFavMap = favMap.toMutableMap()
        val structures = mutableListOf<StructureInfo>()
        val componentName = updatedStructure.componentName

        var replaced = false
        getStructuresForComponent(componentName).forEach { s ->
            val newStructure = if (s.structure == updatedStructure.structure) {
                replaced = true
                updatedStructure
            } else { s }

            if (!newStructure.controls.isEmpty()) {
                structures.add(newStructure)
            }
        }

        if (!replaced && !updatedStructure.controls.isEmpty()) {
            structures.add(updatedStructure)
        }

        newFavMap.put(componentName, structures)
        favMap = newFavMap
    }

    fun clear() {
        favMap = mapOf<ComponentName, List<StructureInfo>>()
    }
}
