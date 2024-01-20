/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.smartspace

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.content.Context
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.BcSmartspaceDataPlugin.UI_SURFACE_GLANCEABLE_HUB
import com.android.systemui.smartspace.SmartspacePrecondition
import com.android.systemui.smartspace.SmartspaceTargetFilter
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.LOCKSCREEN_SMARTSPACE_PRECONDITION
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.LOCKSCREEN_SMARTSPACE_TARGET_FILTER
import com.android.systemui.util.concurrency.Execution
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/** Controller for managing the smartspace view on the glanceable hub */
@SysUISingleton
class CommunalSmartspaceController
@Inject
constructor(
    private val context: Context,
    private val smartspaceManager: SmartspaceManager?,
    private val execution: Execution,
    @Main private val uiExecutor: Executor,
    @Named(LOCKSCREEN_SMARTSPACE_PRECONDITION) private val precondition: SmartspacePrecondition,
    @Named(LOCKSCREEN_SMARTSPACE_TARGET_FILTER)
    private val optionalTargetFilter: Optional<SmartspaceTargetFilter>,
    @Named(GLANCEABLE_HUB_SMARTSPACE_DATA_PLUGIN) optionalPlugin: Optional<BcSmartspaceDataPlugin>,
) {
    companion object {
        private const val TAG = "CommunalSmartspaceCtrlr"
    }

    private var session: SmartspaceSession? = null
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private var targetFilter: SmartspaceTargetFilter? = optionalTargetFilter.orElse(null)

    // A shadow copy of listeners is maintained to track whether the session should remain open.
    private var listeners = mutableSetOf<SmartspaceTargetListener>()

    private var unfilteredListeners = mutableSetOf<SmartspaceTargetListener>()

    // Smartspace can be used on multiple displays, such as when the user casts their screen
    private var smartspaceViews = mutableSetOf<SmartspaceView>()

    var preconditionListener =
        object : SmartspacePrecondition.Listener {
            override fun onCriteriaChanged() {
                reloadSmartspace()
            }
        }

    init {
        precondition.addListener(preconditionListener)
    }

    var filterListener =
        object : SmartspaceTargetFilter.Listener {
            override fun onCriteriaChanged() {
                reloadSmartspace()
            }
        }

    init {
        targetFilter?.addListener(filterListener)
    }

    private val sessionListener =
        SmartspaceSession.OnTargetsAvailableListener { targets ->
            execution.assertIsMainThread()

            val filteredTargets =
                targets.filter { targetFilter?.filterSmartspaceTarget(it) ?: true }
            plugin?.onTargetsAvailable(filteredTargets)
        }

    private fun hasActiveSessionListeners(): Boolean {
        return smartspaceViews.isNotEmpty() ||
            listeners.isNotEmpty() ||
            unfilteredListeners.isNotEmpty()
    }

    private fun connectSession() {
        if (smartspaceManager == null) {
            return
        }
        if (plugin == null) {
            return
        }
        if (session != null || !hasActiveSessionListeners()) {
            return
        }

        if (!precondition.conditionsMet()) {
            return
        }

        val newSession =
            smartspaceManager.createSmartspaceSession(
                SmartspaceConfig.Builder(context, UI_SURFACE_GLANCEABLE_HUB).build()
            )
        Log.d(TAG, "Starting smartspace session for communal")
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        plugin?.registerSmartspaceEventNotifier { e -> session?.notifySmartspaceEvent(e) }

        reloadSmartspace()
    }

    /** Disconnects the smartspace view from the smartspace service and cleans up any resources. */
    private fun disconnect() {
        if (hasActiveSessionListeners()) return

        execution.assertIsMainThread()

        if (session == null) {
            return
        }

        session?.let {
            it.removeOnTargetsAvailableListener(sessionListener)
            it.close()
        }

        session = null

        plugin?.registerSmartspaceEventNotifier(null)
        plugin?.onTargetsAvailable(emptyList())
        Log.d(TAG, "Ending smartspace session for communal")
    }

    fun addListener(listener: SmartspaceTargetListener) {
        addAndRegisterListener(listener, plugin)
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        removeAndUnregisterListener(listener, plugin)
    }

    private fun addAndRegisterListener(
        listener: SmartspaceTargetListener,
        smartspaceDataPlugin: BcSmartspaceDataPlugin?
    ) {
        execution.assertIsMainThread()
        smartspaceDataPlugin?.registerListener(listener)
        listeners.add(listener)

        connectSession()
    }

    private fun removeAndUnregisterListener(
        listener: SmartspaceTargetListener,
        smartspaceDataPlugin: BcSmartspaceDataPlugin?
    ) {
        execution.assertIsMainThread()
        smartspaceDataPlugin?.unregisterListener(listener)
        listeners.remove(listener)
        disconnect()
    }

    private fun reloadSmartspace() {
        session?.requestSmartspaceUpdate()
    }

    private fun onTargetsAvailableUnfiltered(targets: List<SmartspaceTarget>) {
        unfilteredListeners.forEach { it.onSmartspaceTargetsUpdated(targets) }
    }
}
