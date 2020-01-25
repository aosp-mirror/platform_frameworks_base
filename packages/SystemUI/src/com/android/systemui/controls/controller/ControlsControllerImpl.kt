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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import android.util.ArrayMap
import android.util.Log
import com.android.internal.annotations.GuardedBy
import com.android.systemui.DumpController
import com.android.systemui.Dumpable
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.management.ControlsFavoritingActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ControlsControllerImpl @Inject constructor (
    private val context: Context,
    @Background private val executor: DelayableExecutor,
    private val uiController: ControlsUiController,
    private val bindingController: ControlsBindingController,
    private val optionalWrapper: Optional<ControlsFavoritePersistenceWrapper>,
    dumpController: DumpController
) : Dumpable, ControlsController {

    companion object {
        private const val TAG = "ControlsControllerImpl"
        const val CONTROLS_AVAILABLE = "systemui.controls_available"
    }

    override val available = Settings.Secure.getInt(
            context.contentResolver, CONTROLS_AVAILABLE, 0) != 0
    val persistenceWrapper = optionalWrapper.orElseGet {
        ControlsFavoritePersistenceWrapper(
                Environment.buildPath(
                        context.filesDir,
                        ControlsFavoritePersistenceWrapper.FILE_NAME),
                executor
        )
    }

    // Map of map: ComponentName -> (String -> ControlInfo)
    @GuardedBy("currentFavorites")
    private val currentFavorites = ArrayMap<ComponentName, MutableMap<String, ControlInfo>>()

    init {
        if (available) {
            dumpController.registerDumpable(this)
            loadFavorites()
        }
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
        if (!available) {
            Log.d(TAG, "Controls not available")
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
        if (!available) {
            Log.d(TAG, "Controls not available")
            return
        }
        // Make a copy of the favorites list
        val favorites = synchronized(currentFavorites) {
            currentFavorites.flatMap { it.value.values.toList() }
        }
        bindingController.subscribe(favorites)
    }

    override fun unsubscribe() {
        if (!available) {
            Log.d(TAG, "Controls not available")
            return
        }
        bindingController.unsubscribe()
    }

    override fun changeFavoriteStatus(controlInfo: ControlInfo, state: Boolean) {
        if (!available) {
            Log.d(TAG, "Controls not available")
            return
        }
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
        if (!available) {
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
        if (!available) {
            Log.d(TAG, "Controls not available")
            return
        }
        uiController.onActionResponse(componentName, controlId, response)
    }

    override fun getFavoriteControls(): List<ControlInfo> {
        if (!available) {
            Log.d(TAG, "Controls not available")
            return emptyList()
        }
        synchronized(currentFavorites) {
            return favoritesAsListLocked()
        }
    }

    override fun action(controlInfo: ControlInfo, action: ControlAction) {
        bindingController.action(controlInfo, action)
    }

    override fun clearFavorites() {
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
        pw.println("  Favorites:")
        synchronized(currentFavorites) {
            currentFavorites.forEach {
                it.value.forEach {
                    pw.println("    ${it.value}")
                }
            }
        }
    }
}
