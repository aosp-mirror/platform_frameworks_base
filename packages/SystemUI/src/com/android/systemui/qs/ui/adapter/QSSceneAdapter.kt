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
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.QSImpl
import com.android.systemui.qs.dagger.QSSceneComponent
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
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
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
     * [qsView]
     */
    suspend fun inflate(context: Context, parent: ViewGroup? = null)

    /** Set the current state for QS. [state] must not be [State.INITIAL]. */
    fun setState(state: State)

    sealed class State(
        val isVisible: Boolean,
        val expansion: Float,
    ) {
        data object CLOSED : State(false, 0f)
        data object QQS : State(true, 0f)
        data object QS : State(true, 1f)
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
    private val asyncLayoutInflaterFactory: (Context) -> AsyncLayoutInflater,
) : QSContainerController, QSSceneAdapter {

    @Inject
    constructor(
        qsSceneComponentFactory: QSSceneComponent.Factory,
        qsImplProvider: Provider<QSImpl>,
        @Main dispatcher: CoroutineDispatcher,
        @Application scope: CoroutineScope,
    ) : this(qsSceneComponentFactory, qsImplProvider, dispatcher, scope, ::AsyncLayoutInflater)

    private val state = MutableStateFlow<QSSceneAdapter.State>(QSSceneAdapter.State.CLOSED)
    private val _isCustomizing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isCustomizing = _isCustomizing.asStateFlow()

    private val _qsImpl: MutableStateFlow<QSImpl?> = MutableStateFlow(null)
    val qsImpl = _qsImpl.asStateFlow()
    override val qsView: Flow<View> = _qsImpl.map { it?.view }.filterNotNull()

    init {
        applicationScope.launch {
            state.sample(_isCustomizing, ::Pair).collect { (state, customizing) ->
                _qsImpl.value?.apply {
                    if (state != QSSceneAdapter.State.QS && customizing) {
                        this@apply.closeCustomizerImmediately()
                    }
                    applyState(state)
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

    override suspend fun inflate(context: Context, parent: ViewGroup?) {
        withContext(mainDispatcher) {
            val inflater = asyncLayoutInflaterFactory(context)
            val view = suspendCoroutine { continuation ->
                inflater.inflate(R.layout.qs_panel, parent) { view, _, _ ->
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
