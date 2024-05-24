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
import android.app.smartspace.SmartspaceTarget
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
import com.android.systemui.plugins.BcSmartspaceDataPlugin.UI_SURFACE_DREAM
import com.android.systemui.smartspace.SmartspacePrecondition
import com.android.systemui.smartspace.SmartspaceTargetFilter
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DREAM_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.DREAM_WEATHER_SMARTSPACE_DATA_PLUGIN
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.LOCKSCREEN_SMARTSPACE_PRECONDITION
import com.android.systemui.smartspace.dagger.SmartspaceModule.Companion.LOCKSCREEN_SMARTSPACE_TARGET_FILTER
import com.android.systemui.smartspace.dagger.SmartspaceViewComponent
import com.android.systemui.util.concurrency.Execution
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/**
 * Controller for managing the smartspace view on the dream
 */
@SysUISingleton
class DreamSmartspaceController @Inject constructor(
    private val context: Context,
    private val smartspaceManager: SmartspaceManager?,
    private val execution: Execution,
    @Main private val uiExecutor: Executor,
    private val smartspaceViewComponentFactory: SmartspaceViewComponent.Factory,
    @Named(LOCKSCREEN_SMARTSPACE_PRECONDITION) private val precondition: SmartspacePrecondition,
    @Named(LOCKSCREEN_SMARTSPACE_TARGET_FILTER)
    private val optionalTargetFilter: Optional<SmartspaceTargetFilter>,
    @Named(DREAM_SMARTSPACE_DATA_PLUGIN) optionalPlugin: Optional<BcSmartspaceDataPlugin>,
    @Named(DREAM_WEATHER_SMARTSPACE_DATA_PLUGIN)
    optionalWeatherPlugin: Optional<BcSmartspaceDataPlugin>,
) {
    companion object {
        private const val TAG = "DreamSmartspaceCtrlr"
    }

    private var session: SmartspaceSession? = null
    private val weatherPlugin: BcSmartspaceDataPlugin? = optionalWeatherPlugin.orElse(null)
    private val plugin: BcSmartspaceDataPlugin? = optionalPlugin.orElse(null)
    private var targetFilter: SmartspaceTargetFilter? = optionalTargetFilter.orElse(null)

    // A shadow copy of listeners is maintained to track whether the session should remain open.
    private var listeners = mutableSetOf<SmartspaceTargetListener>()

    private var unfilteredListeners = mutableSetOf<SmartspaceTargetListener>()

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
            view.setDozeAmount(0f)
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

        // The weather data plugin takes unfiltered targets and performs the filtering internally.
        weatherPlugin?.onTargetsAvailable(targets)

        onTargetsAvailableUnfiltered(targets)
        val filteredTargets = targets.filter { targetFilter?.filterSmartspaceTarget(it) ?: true }
        plugin?.onTargetsAvailable(filteredTargets)
    }

    /**
     * Constructs the weather view with custom layout and connects it to the weather plugin.
     */
    fun buildAndConnectWeatherView(parent: ViewGroup, customView: View?): View? {
        return buildAndConnectViewWithPlugin(parent, weatherPlugin, customView)
    }

    /**
     * Constructs the smartspace view and connects it to the smartspace service.
     */
    fun buildAndConnectView(parent: ViewGroup): View? {
        return buildAndConnectViewWithPlugin(parent, plugin, null)
    }

    private fun buildAndConnectViewWithPlugin(
        parent: ViewGroup,
        smartspaceDataPlugin: BcSmartspaceDataPlugin?,
        customView: View?
    ): View? {
        execution.assertIsMainThread()

        if (!precondition.conditionsMet()) {
            throw RuntimeException("Cannot build view when not enabled")
        }

        val view = buildView(parent, smartspaceDataPlugin, customView)

        connectSession()

        return view
    }

    private fun buildView(
        parent: ViewGroup,
        smartspaceDataPlugin: BcSmartspaceDataPlugin?,
        customView: View?
    ): View? {
        return if (smartspaceDataPlugin != null) {
            val view = smartspaceViewComponentFactory.create(parent, smartspaceDataPlugin,
                stateChangeListener, customView)
                .getView()
            if (view !is View) {
                return null
            }
            return view
        } else {
            null
        }
    }

    private fun hasActiveSessionListeners(): Boolean {
        return smartspaceViews.isNotEmpty() || listeners.isNotEmpty() ||
            unfilteredListeners.isNotEmpty()
    }

    private fun connectSession() {
        if (smartspaceManager == null) {
            return
        }
        if (plugin == null && weatherPlugin == null) {
            return
        }
        if (session != null || !hasActiveSessionListeners()) {
            return
        }

        if (!precondition.conditionsMet()) {
            return
        }

        val newSession = smartspaceManager.createSmartspaceSession(
            SmartspaceConfig.Builder(context, UI_SURFACE_DREAM).build()
        )
        Log.d(TAG, "Starting smartspace session for dream")
        newSession.addOnTargetsAvailableListener(uiExecutor, sessionListener)
        this.session = newSession

        weatherPlugin?.registerSmartspaceEventNotifier { e -> session?.notifySmartspaceEvent(e) }
        plugin?.registerSmartspaceEventNotifier {
                e ->
            session?.notifySmartspaceEvent(e)
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

        weatherPlugin?.registerSmartspaceEventNotifier(null)
        weatherPlugin?.onTargetsAvailable(emptyList())

        plugin?.registerSmartspaceEventNotifier(null)
        plugin?.onTargetsAvailable(emptyList())
        Log.d(TAG, "Ending smartspace session for dream")
    }

    fun addListener(listener: SmartspaceTargetListener) {
        addAndRegisterListener(listener, plugin)
    }

    fun removeListener(listener: SmartspaceTargetListener) {
        removeAndUnregisterListener(listener, plugin)
    }

    fun addListenerForWeatherPlugin(listener: SmartspaceTargetListener) {
        addAndRegisterListener(listener, weatherPlugin)
    }

    fun removeListenerForWeatherPlugin(listener: SmartspaceTargetListener) {
        removeAndUnregisterListener(listener, weatherPlugin)
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

    /**
     * Adds a listener for the raw, unfiltered list of smartspace targets. This should be used
     * carefully, as it doesn't filter out targets which the user may not want shown.
     */
    fun addUnfilteredListener(listener: SmartspaceTargetListener) {
        unfilteredListeners.add(listener)
        connectSession()
    }

    fun removeUnfilteredListener(listener: SmartspaceTargetListener) {
        unfilteredListeners.remove(listener)
        disconnect()
    }
}
