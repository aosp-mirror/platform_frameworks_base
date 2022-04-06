/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.smartspace

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.smartspace.SmartspacePrecondition
import com.android.systemui.smartspace.SmartspaceTargetFilter
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DREAM_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DREAM_SMARTSPACE_PRECONDITION
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DREAM_SMARTSPACE_TARGET_FILTER
import com.android.systemui.smartspace.dagger.SmartspaceViewComponent
import com.android.systemui.util.concurrency.Execution
import java.lang.RuntimeException
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/**
 * Controller for managing the smartspace view on the dream
 */
@SysUISingleton
class DreamsSmartspaceController @Inject constructor(
    private val context: Context,
    private val smartspaceManager: SmartspaceManager,
    private val execution: Execution,
    @Main private val uiExecutor: Executor,
    private val smartspaceViewComponentFactory: SmartspaceViewComponent.Factory,
    @Named(DREAM_SMARTSPACE_PRECONDITION) private val precondition: SmartspacePrecondition,
    @Named(DREAM_SMARTSPACE_TARGET_FILTER)
    private val optionalTargetFilter: Optional<SmartspaceTargetFilter>,
    @Named(DREAM_SMARTSPACE_DATA_PLUGIN) optionalPlugin: Optional<BcSmartspaceDataPlugin>
) {
    companion object {
        private const val TAG = "DreamsSmartspaceCtrlr"
    }

    private var session: SmartspaceSession? = null
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private var targetFilter: SmartspaceTargetFilter? = optionalTargetFilter.orElse(null)

    // A shadow copy of listeners is maintained to track whether the session should remain open.
    private var listeners = mutableSetOf<BcSmartspaceDataPlugin.SmartspaceTargetListener>()

    // Smartspace can be used on multiple displays, such as when the user casts their screen
    private var smartspaceViews = mutableSetOf<SmartspaceView>()

    var preconditionListener = object : SmartspacePrecondition.Listener {
        override fun onCriteriaChanged() {
            reloadSmartspace()
        }
    }

    init {
        precondition.addListener(preconditionListener)
    }

    var filterListener = object : SmartspaceTargetFilter.Listener {
        override fun onCriteriaChanged() {
            reloadSmartspace()
        }
    }

    init {
        targetFilter?.addListener(filterListener)
    }

    var stateChangeListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val view = v as SmartspaceView
            // Until there is dream color matching
            view.setPrimaryTextColor(Color.WHITE)
            smartspaceViews.add(view)
            connectSession()
        }

        override fun onViewDetachedFromWindow(v: View) {
            smartspaceViews.remove(v as SmartspaceView)

            if (smartspaceViews.isEmpty()) {
                disconnect()
            }
        }
    }

    private val sessionListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        execution.assertIsMainThread()

        val filteredTargets = targets.filter { targetFilter?.filterSmartspaceTarget(it) ?: true }
        plugin?.onTargetsAvailable(filteredTargets)
    }

    /**
     * Constructs the smartspace view and connects it to the smartspace service.
     */
    fun buildAndConnectView(parent: ViewGroup): View? {
        execution.assertIsMainThread()

        if (!precondition.conditionsMet()) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view = buildView(parent)
        connectSession()

        return view
    }

    private fun buildView(parent: ViewGroup): View? {
        return if (plugin != null) {
            var view = smartspaceViewComponentFactory.create(parent, plugin, stateChangeListener)
                    .getView()

            if (view is View) {
                return view
            }

            return null
        } else {
            null
        }
    }

    private fun hasActiveSessionListeners(): Boolean {
        return smartspaceViews.isNotEmpty() || listeners.isNotEmpty()
    }

    private fun connectSession() {
        if (plugin == null || session != null || !hasActiveSessionListeners()) {
            return
        }

        if (!precondition.conditionsMet()) {
            return
        }

        val newSession = smartspaceManager.createSmartspaceSession(
                SmartspaceConfig.Builder(context, "dream").build())
        Log.d(TAG, "Starting smartspace session for dream")
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        plugin.registerSmartspaceEventNotifier {
                e -> session?.notifySmartspaceEvent(e)
        }

        reloadSmartspace()
    }

    /**
     * Disconnects the smartspace view from the smartspace service and cleans up any resources.
     */
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
        Log.d(TAG, "Ending smartspace session for dream")
    }

    fun addListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.registerListener(listener)
        listeners.add(listener)

        connectSession()
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        execution.assertIsMainThread()
        plugin?.unregisterListener(listener)
        listeners.remove(listener)
        disconnect()
    }

    private fun reloadSmartspace() {
        session?.requestSmartspaceUpdate()
    }
}
