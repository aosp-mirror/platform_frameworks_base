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
import com.android.systemui.Dumpable
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.QSContainerImpl
import com.android.systemui.qs.QSImpl
import com.android.systemui.qs.dagger.QSSceneComponent
import com.android.systemui.res.R
import com.android.systemui.settings.brightness.MirrorController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.util.kotlin.sample
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO(307945185) Split View concerns into a ViewBinder
/** Adapter to use between Scene system and [QSImpl] */
interface QSSceneAdapter {

    /**
     * Whether we are currently customizing or entering the customizer.
     *
     * @see CustomizerState.isCustomizing
     */
    val isCustomizing: StateFlow<Boolean>

    /**
     * Whether the customizer is showing. This includes animating into and out of it.
     *
     * @see CustomizerState.isShowing
     */
    val isCustomizerShowing: StateFlow<Boolean>

    /**
     * The duration of the current animation in/out of customizer. If not in an animating state,
     * this duration is 0 (to match show/hide immediately).
     *
     * @see CustomizerState.Animating.animationDuration
     */
    val customizerAnimationDuration: StateFlow<Int>

    /**
     * A view with the QS content ([QSContainerImpl]), managed by an instance of [QSImpl] tracked by
     * the interactor.
     */
    val qsView: Flow<View>

    /** Sets the [MirrorController] in [QSImpl]. Set to `null` to remove. */
    fun setBrightnessMirrorController(mirrorController: MirrorController?)

    /**
     * Inflate an instance of [QSImpl] for this context. Once inflated, it will be available in
     * [qsView]. Re-inflations due to configuration changes will use the last used [context].
     */
    suspend fun inflate(context: Context)

    /** Set the current state for QS. [state]. */
    fun setState(state: State)

    /** Propagates the bottom nav bar size to [QSImpl] to be used as necessary. */
    suspend fun applyBottomNavBarPadding(padding: Int)

    /** The current height of QQS in the current [qsView], or 0 if there's no view. */
    val qqsHeight: Int

    /**
     * The current height of QS in the current [qsView], or 0 if there's no view. If customizing, it
     * will return the height allocated to the customizer.
     */
    val qsHeight: Int

    /** Compatibility for use by LockscreenShadeTransitionController. Matches default from [QS] */
    val isQsFullyCollapsed: Boolean
        get() = true

    /** Request that the customizer be closed. Possibly animating it. */
    fun requestCloseCustomizer()

    sealed interface State {

        val isVisible: Boolean
        val expansion: Float
        val squishiness: () -> Float

        data object CLOSED : State {
            override val isVisible = false
            override val expansion = 0f
            override val squishiness = { 1f }
        }

        /** State for expanding between QQS and QS */
        data class Expanding(override val expansion: Float) : State {
            override val isVisible = true
            override val squishiness = { 1f }
        }

        /** State for appearing QQS from Lockscreen or Gone */
        data class UnsquishingQQS(override val squishiness: () -> Float) : State {
            override val isVisible = true
            override val expansion = 0f
        }

        /** State for appearing QS from Lockscreen or Gone, used in Split shade */
        data class UnsquishingQS(override val squishiness: () -> Float) : State {
            override val isVisible = true
            override val expansion = 1f
        }

        companion object {
            // These are special cases of the expansion.
            val QQS = Expanding(0f)
            val QS = Expanding(1f)

            /** Collapsing from QS to QQS. [progress] is 0f in QS and 1f in QQS. */
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
    shadeInteractor: ShadeInteractor,
    dumpManager: DumpManager,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application applicationScope: CoroutineScope,
    private val configurationInteractor: ConfigurationInteractor,
    private val asyncLayoutInflaterFactory: (Context) -> AsyncLayoutInflater,
) : QSContainerController, QSSceneAdapter, Dumpable {

    @Inject
    constructor(
        qsSceneComponentFactory: QSSceneComponent.Factory,
        qsImplProvider: Provider<QSImpl>,
        shadeInteractor: ShadeInteractor,
        dumpManager: DumpManager,
        @Main dispatcher: CoroutineDispatcher,
        @Application scope: CoroutineScope,
        configurationInteractor: ConfigurationInteractor,
    ) : this(
        qsSceneComponentFactory,
        qsImplProvider,
        shadeInteractor,
        dumpManager,
        dispatcher,
        scope,
        configurationInteractor,
        ::AsyncLayoutInflater,
    )

    private val bottomNavBarSize =
        MutableSharedFlow<Int>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val state = MutableStateFlow<QSSceneAdapter.State>(QSSceneAdapter.State.CLOSED)
    private val _customizingState: MutableStateFlow<CustomizerState> =
        MutableStateFlow(CustomizerState.Hidden)
    val customizerState = _customizingState.asStateFlow()

    override val isCustomizing: StateFlow<Boolean> =
        customizerState
            .map { it.isCustomizing }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                customizerState.value.isCustomizing,
            )
    override val isCustomizerShowing: StateFlow<Boolean> =
        customizerState
            .map { it.isShowing }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                customizerState.value.isShowing
            )
    override val customizerAnimationDuration: StateFlow<Int> =
        customizerState
            .map { (it as? CustomizerState.Animating)?.animationDuration?.toInt() ?: 0 }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                (customizerState.value as? CustomizerState.Animating)?.animationDuration?.toInt()
                    ?: 0,
            )

    private val _qsImpl: MutableStateFlow<QSImpl?> = MutableStateFlow(null)
    val qsImpl = _qsImpl.asStateFlow()
    override val qsView: Flow<View> = _qsImpl.map { it?.view }.filterNotNull()

    override val qqsHeight: Int
        get() = qsImpl.value?.qqsHeight ?: 0
    override val qsHeight: Int
        get() = qsImpl.value?.qsHeight ?: 0

    // If value is null, there's no QS and therefore it's fully collapsed.
    override val isQsFullyCollapsed: Boolean
        get() = qsImpl.value?.isFullyCollapsed ?: true

    // Same config changes as in FragmentHostManager
    private val interestingChanges =
        InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE or
                ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_ASSETS_PATHS
        )

    init {
        dumpManager.registerDumpable(this)
        applicationScope.launch {
            launch {
                state.sample(_customizingState, ::Pair).collect { (state, customizing) ->
                    qsImpl.value?.apply {
                        if (state != QSSceneAdapter.State.QS && customizing.isShowing) {
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
            launch {
                combine(bottomNavBarSize, qsImpl.filterNotNull(), ::Pair).collect {
                    it.second.applyBottomNavBarToCustomizerPadding(it.first)
                }
            }
            launch {
                shadeInteractor.shadeMode.collect {
                    qsImpl.value?.setInSplitShade(it == ShadeMode.Split)
                }
            }
        }
    }

    override fun setCustomizerAnimating(animating: Boolean) {
        if (_customizingState.value is CustomizerState.Animating && !animating) {
            _customizingState.update {
                if (it is CustomizerState.AnimatingIntoCustomizer) {
                    CustomizerState.Showing
                } else {
                    CustomizerState.Hidden
                }
            }
        }
    }

    override fun setCustomizerShowing(showing: Boolean) {
        setCustomizerShowing(showing, 0L)
    }

    override fun setCustomizerShowing(showing: Boolean, animationDuration: Long) {
        _customizingState.update { _ ->
            if (showing) {
                if (animationDuration > 0) {
                    CustomizerState.AnimatingIntoCustomizer(animationDuration)
                } else {
                    CustomizerState.Showing
                }
            } else {
                if (animationDuration > 0) {
                    CustomizerState.AnimatingOutOfCustomizer(animationDuration)
                } else {
                    CustomizerState.Hidden
                }
            }
        }
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

    override suspend fun applyBottomNavBarPadding(padding: Int) {
        bottomNavBarSize.emit(padding)
    }

    override fun requestCloseCustomizer() {
        qsImpl.value?.closeCustomizer()
    }

    override fun setBrightnessMirrorController(mirrorController: MirrorController?) {
        qsImpl.value?.setBrightnessMirrorController(mirrorController)
    }

    private fun QSImpl.applyState(state: QSSceneAdapter.State) {
        setQsVisible(state.isVisible)
        setExpanded(state.isVisible && state.expansion > 0f)
        setListening(state.isVisible)
        setQsExpansion(state.expansion, 1f, 0f, state.squishiness())
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("Last state: ${state.value}")
            println("CustomizerState: ${_customizingState.value}")
            println("QQS height: $qqsHeight")
            println("QS height: $qsHeight")
        }
    }
}

/** Current state of the customizer */
sealed interface CustomizerState {

    /**
     * This indicates that some part of the customizer is showing. It could be animating in or out.
     */
    val isShowing: Boolean
        get() = true

    /**
     * This indicates that we are currently customizing or animating into it. In particular, when
     * animating out, this is false.
     *
     * @see QSCustomizer.isCustomizing
     */
    val isCustomizing: Boolean
        get() = false

    sealed interface Animating : CustomizerState {
        val animationDuration: Long
    }

    /** Customizer is completely hidden, and not animating */
    data object Hidden : CustomizerState {
        override val isShowing = false
    }

    /** Customizer is completely showing, and not animating */
    data object Showing : CustomizerState {
        override val isCustomizing = true
    }

    /** Animating from [Hidden] into [Showing]. */
    data class AnimatingIntoCustomizer(override val animationDuration: Long) : Animating {
        override val isCustomizing = true
    }

    /** Animating from [Showing] into [Hidden]. */
    data class AnimatingOutOfCustomizer(override val animationDuration: Long) : Animating
}
