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

package com.android.systemui.qs.tiles.viewmodel

import android.content.Context
import android.os.UserHandle
import android.util.Log
import androidx.annotation.GuardedBy
import com.android.internal.logging.InstanceId
import com.android.systemui.Dumpable
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

// TODO(b/http://b/299909989): Use QSTileViewModel directly after the rollout
class QSTileViewModelAdapter
@AssistedInject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val qsHost: QSHost,
    @Assisted private val qsTileViewModel: QSTileViewModel,
) : QSTile, Dumpable {

    private val context
        get() = qsHost.context

    @GuardedBy("callbacks")
    private val callbacks: MutableCollection<QSTile.Callback> = mutableSetOf()
    @GuardedBy("listeningClients")
    private val listeningClients: MutableCollection<Any> = mutableSetOf()

    // Cancels the jobs when the adapter is no longer alive
    private var tileAdapterJob: Job? = null
    // Cancels the jobs when clients stop listening
    private var stateJob: Job? = null

    init {
        tileAdapterJob =
            applicationScope.launch {
                launch {
                    qsTileViewModel.isAvailable.collectIndexed { index, isAvailable ->
                        if (!isAvailable) {
                            qsHost.removeTile(tileSpec)
                        }
                        // qsTileViewModel.isAvailable flow often starts with isAvailable == true.
                        // That's
                        // why we only allow isAvailable == true once and throw an exception
                        // afterwards.
                        if (index > 0 && isAvailable) {
                            // See com.android.systemui.qs.pipeline.domain.model.AutoAddable for
                            // additional
                            // guidance on how to auto add your tile
                            throw UnsupportedOperationException(
                                "Turning on tile is not supported now"
                            )
                        }
                    }
                }
                // Warm up tile with some initial state
                launch { qsTileViewModel.state.first() }
            }

        // QSTileHost doesn't call this when userId is initialized
        userSwitch(qsHost.userId)

        if (DEBUG) {
            Log.d(TAG, "Using new tiles for: $tileSpec")
        }
    }

    override fun isAvailable(): Boolean = qsTileViewModel.isAvailable.value

    override fun setTileSpec(tileSpec: String?) {
        throw UnsupportedOperationException("Tile spec is immutable in new tiles")
    }

    override fun refreshState() {
        qsTileViewModel.forceUpdate()
    }

    override fun addCallback(callback: QSTile.Callback?) {
        callback ?: return
        synchronized(callbacks) {
            callbacks.add(callback)
            state?.let(callback::onStateChanged)
        }
    }

    override fun removeCallback(callback: QSTile.Callback?) {
        callback ?: return
        synchronized(callbacks) { callbacks.remove(callback) }
    }

    override fun removeCallbacks() {
        synchronized(callbacks) { callbacks.clear() }
    }

    override fun click(expandable: Expandable?) {
        if (isActionSupported(QSTileState.UserAction.CLICK)) {
            qsTileViewModel.onActionPerformed(QSTileUserAction.Click(expandable))
        }
    }

    override fun secondaryClick(expandable: Expandable?) {
        if (isActionSupported(QSTileState.UserAction.CLICK)) {
            qsTileViewModel.onActionPerformed(QSTileUserAction.Click(expandable))
        }
    }

    override fun longClick(expandable: Expandable?) {
        if (isActionSupported(QSTileState.UserAction.LONG_CLICK)) {
            qsTileViewModel.onActionPerformed(QSTileUserAction.LongClick(expandable))
        }
    }

    private fun isActionSupported(action: QSTileState.UserAction): Boolean =
        qsTileViewModel.currentState?.supportedActions?.contains(action) == true

    override fun userSwitch(currentUser: Int) {
        qsTileViewModel.onUserChanged(UserHandle.of(currentUser))
    }

    @Deprecated(
        "Not needed as {@link com.android.internal.logging.UiEvent} will use #getMetricsSpec",
        replaceWith = ReplaceWith("getMetricsSpec"),
    )
    override fun getMetricsCategory(): Int = 0

    override fun isTileReady(): Boolean = qsTileViewModel.currentState != null

    override fun setListening(client: Any?, listening: Boolean) {
        client ?: return
        synchronized(listeningClients) {
            if (listening) {
                listeningClients.add(client)
                if (listeningClients.size == 1) {
                    stateJob =
                        qsTileViewModel.state
                            .map { mapState(context, it, qsTileViewModel.config) }
                            .onEach { legacyState ->
                                synchronized(callbacks) {
                                    callbacks.forEach { it.onStateChanged(legacyState) }
                                }
                            }
                            .launchIn(applicationScope)
                }
            } else {
                listeningClients.remove(client)
                if (listeningClients.isEmpty()) {
                    stateJob?.cancel()
                }
            }
        }
    }

    override fun isListening(): Boolean =
        synchronized(listeningClients) { listeningClients.isNotEmpty() }

    override fun setDetailListening(show: Boolean) {
        // do nothing like QSTileImpl
    }

    override fun destroy() {
        stateJob?.cancel()
        tileAdapterJob?.cancel()
        qsTileViewModel.destroy()
    }

    override fun getState(): QSTile.State? =
        qsTileViewModel.currentState?.let { mapState(context, it, qsTileViewModel.config) }

    override fun getInstanceId(): InstanceId = qsTileViewModel.config.instanceId
    override fun getTileLabel(): CharSequence =
        with(qsTileViewModel.config.uiConfig) {
            when (this) {
                is QSTileUIConfig.Empty -> qsTileViewModel.currentState?.label ?: ""
                is QSTileUIConfig.Resource -> context.getString(labelRes)
            }
        }

    override fun getTileSpec(): String = qsTileViewModel.config.tileSpec.spec

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        (qsTileViewModel as? Dumpable)?.dump(pw, args)
            ?: pw.println("${getTileSpec()}: QSTileViewModel isn't dumpable")

    private companion object {

        const val DEBUG = false
        const val TAG = "QSTileVMAdapter"

        fun mapState(
            context: Context,
            viewModelState: QSTileState,
            config: QSTileConfig
        ): QSTile.State =
            // we have to use QSTile.BooleanState to support different side icons
            // which are bound to instanceof QSTile.BooleanState in QSTileView.
            QSTile.AdapterState().apply {
                spec = config.tileSpec.spec
                label = viewModelState.label
                // This value is synthetic and doesn't have any meaning. It's only needed to satisfy
                // CTS tests.
                value = viewModelState.activationState == QSTileState.ActivationState.ACTIVE

                secondaryLabel = viewModelState.secondaryLabel
                handlesLongClick =
                    viewModelState.supportedActions.contains(QSTileState.UserAction.LONG_CLICK)

                iconSupplier = Supplier {
                    when (val stateIcon = viewModelState.icon()) {
                        is Icon.Loaded -> DrawableIcon(stateIcon.drawable)
                        is Icon.Resource -> ResourceIcon.get(stateIcon.res)
                        null -> null
                    }
                }
                state = viewModelState.activationState.legacyState

                contentDescription = viewModelState.contentDescription
                stateDescription = viewModelState.stateDescription

                disabledByPolicy = viewModelState.enabledState == QSTileState.EnabledState.DISABLED
                expandedAccessibilityClassName = viewModelState.expandedAccessibilityClassName

                // Use LoopedAnimatable2DrawableWrapper to achieve animated tile icon
                isTransient = false

                when (viewModelState.sideViewIcon) {
                    is QSTileState.SideViewIcon.Custom -> {
                        sideViewCustomDrawable =
                            when (viewModelState.sideViewIcon.icon) {
                                is Icon.Loaded -> viewModelState.sideViewIcon.icon.drawable
                                is Icon.Resource ->
                                    context.getDrawable(viewModelState.sideViewIcon.icon.res)
                            }
                    }
                    is QSTileState.SideViewIcon.Chevron -> {
                        forceExpandIcon = true
                    }
                    is QSTileState.SideViewIcon.None -> {
                        forceExpandIcon = false
                    }
                }
            }
    }

    @AssistedFactory
    interface Factory {

        fun create(qsTileViewModel: QSTileViewModel): QSTileViewModelAdapter
    }
}
