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

package com.android.systemui.qs.ui.adapter

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import com.android.settingslib.applications.InterestingConfigChanges
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.QSContainerImpl
import com.android.systemui.qs.QSImpl
import com.android.systemui.qs.dagger.QSSceneComponent
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO(307945185) Split View concerns into a ViewBinder
/** Adapter to use between Scene system and [QSImpl] */
interface QSSceneAdapter {
    /** Whether [QSImpl] is currently customizing */
    val isCustomizing: StateFlow<Boolean>

    /**
     * A view with the QS content ([QSContainerImpl]), managed by an instance of [QSImpl] tracked by
     * the interactor.
     */
    val qsView: Flow<View>

    /**
     * Inflate an instance of [QSImpl] for this context. Once inflated, it will be available in
     * [qsView]. Re-inflations due to configuration changes will use the last used [context].
     */
    suspend fun inflate(context: Context)

    /** Set the current state for QS. [state]. */
    fun setState(state: State)

    /** The current height of QQS in the current [qsView], or 0 if there's no view. */
    val qqsHeight: Int

    /**
     * The current height of QS in the current [qsView], or 0 if there's no view. If customizing, it
     * will return the height allocated to the customizer.
     */
    val qsHeight: Int

    sealed class State(
        val isVisible: Boolean,
        val expansion: Float,
    ) {
        data object CLOSED : State(false, 0f)
        data class Expanding(val progress: Float) : State(true, progress)

        companion object {
            // These are special cases of the expansion.
            val QQS = Expanding(0f)
            val QS = Expanding(1f)

            fun Collapsing(progress: Float) = Expanding(1f - progress)
        }
    }
}

@SysUISingleton
class QSSceneAdapterImpl
@VisibleForTesting
constructor(
    private val qsSceneComponentFactory: QSSceneComponent.Factory,
    private val qsImplProvider: Provider<QSImpl>,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application applicationScope: CoroutineScope,
    private val configurationInteractor: ConfigurationInteractor,
    private val asyncLayoutInflaterFactory: (Context) -> AsyncLayoutInflater,
) : QSContainerController, QSSceneAdapter {

    @Inject
    constructor(
        qsSceneComponentFactory: QSSceneComponent.Factory,
        qsImplProvider: Provider<QSImpl>,
        @Main dispatcher: CoroutineDispatcher,
        @Application scope: CoroutineScope,
        configurationInteractor: ConfigurationInteractor,
    ) : this(
        qsSceneComponentFactory,
        qsImplProvider,
        dispatcher,
        scope,
        configurationInteractor,
        ::AsyncLayoutInflater,
    )

    private val state = MutableStateFlow<QSSceneAdapter.State>(QSSceneAdapter.State.CLOSED)
    private val _isCustomizing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isCustomizing = _isCustomizing.asStateFlow()

    private val _qsImpl: MutableStateFlow<QSImpl?> = MutableStateFlow(null)
    val qsImpl = _qsImpl.asStateFlow()
    override val qsView: Flow<View> = _qsImpl.map { it?.view }.filterNotNull()

    override val qqsHeight: Int
        get() = qsImpl.value?.qqsHeight ?: 0
    override val qsHeight: Int
        get() = qsImpl.value?.qsHeight ?: 0

    // Same config changes as in FragmentHostManager
    private val interestingChanges =
        InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE or
                ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_ASSETS_PATHS
        )

    init {
        applicationScope.launch {
            launch {
                state.sample(_isCustomizing, ::Pair).collect { (state, customizing) ->
                    _qsImpl.value?.apply {
                        if (state != QSSceneAdapter.State.QS && customizing) {
                            this@apply.closeCustomizerImmediately()
                        }
                        applyState(state)
                    }
                }
            }
            launch {
                configurationInteractor.configurationValues.collect { config ->
                    if (interestingChanges.applyNewConfig(config)) {
                        // Assumption: The context is always the same and with the same theme.
                        // If colors change they will be reflected as attributes in the theme.
                        qsImpl.value?.view?.let { inflate(it.context) }
                    } else {
                        qsImpl.value?.onConfigurationChanged(config)
                        qsImpl.value?.view?.dispatchConfigurationChanged(config)
                    }
                }
            }
        }
    }

    override fun setCustomizerAnimating(animating: Boolean) {}

    override fun setCustomizerShowing(showing: Boolean) {
        _isCustomizing.value = showing
    }

    override fun setCustomizerShowing(showing: Boolean, animationDuration: Long) {
        setCustomizerShowing(showing)
    }

    override fun setDetailShowing(showing: Boolean) {}

    override suspend fun inflate(context: Context) {
        withContext(mainDispatcher) {
            val inflater = asyncLayoutInflaterFactory(context)
            val view = suspendCoroutine { continuation ->
                inflater.inflate(R.layout.qs_panel, null) { view, _, _ ->
                    continuation.resume(view)
                }
            }
            val bundle = Bundle()
            _qsImpl.value?.onSaveInstanceState(bundle)
            _qsImpl.value?.onDestroy()
            val component = qsSceneComponentFactory.create(view)
            val qs = qsImplProvider.get()
            qs.onCreate(null)
            qs.onComponentCreated(component, bundle)
            _qsImpl.value = qs
            qs.view.setPadding(0, 0, 0, 0)
            qs.setContainerController(this@QSSceneAdapterImpl)
            qs.applyState(state.value)
        }
    }
    override fun setState(state: QSSceneAdapter.State) {
        this.state.value = state
    }

    private fun QSImpl.applyState(state: QSSceneAdapter.State) {
        setQsVisible(state.isVisible)
        setExpanded(state.isVisible)
        setListening(state.isVisible)
        setQsExpansion(state.expansion, 1f, 0f, 1f)
        setTransitionToFullShadeProgress(false, 1f, 1f)
    }
}
