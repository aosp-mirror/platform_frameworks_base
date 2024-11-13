/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.composefragment

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.util.IndentingPrintWriter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.transitions
import com.android.compose.modifiers.height
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.PlatformTheme
import com.android.systemui.Dumpable
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dump.DumpManager
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule.QS_PANEL
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.composefragment.SceneKeys.QuickQuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.QuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.toIdleSceneKey
import com.android.systemui.qs.composefragment.ui.notificationScrimClip
import com.android.systemui.qs.composefragment.ui.quickQuickSettingsToQuickSettings
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.panels.ui.compose.QuickQuickSettings
import com.android.systemui.qs.shared.ui.ElementKeys
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.qs.ui.composable.ShadeBody
import com.android.systemui.res.R
import com.android.systemui.util.LifecycleFragment
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import java.io.PrintWriter
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SuppressLint("ValidFragment")
class QSFragmentCompose
@Inject
constructor(
    private val qsFragmentComposeViewModelFactory: QSFragmentComposeViewModel.Factory,
    private val dumpManager: DumpManager,
    @Named(QUICK_QS_PANEL) private val qqsMediaHost: MediaHost,
    @Named(QS_PANEL) private val qsMediaHost: MediaHost,
) : LifecycleFragment(), QS, Dumpable {

    private val scrollListener = MutableStateFlow<QS.ScrollListener?>(null)
    private val heightListener = MutableStateFlow<QS.HeightListener?>(null)
    private val qsContainerController = MutableStateFlow<QSContainerController?>(null)

    private lateinit var viewModel: QSFragmentComposeViewModel

    // Starting with a non-zero value makes it so that it has a non-zero height on first expansion
    // This is important for `QuickSettingsControllerImpl.mMinExpansionHeight` to detect a "change".
    private val qqsHeight = MutableStateFlow(1)
    private val qsHeight = MutableStateFlow(0)
    private val qqsVisible = MutableStateFlow(false)
    private val qqsPositionOnRoot = Rect()
    private val composeViewPositionOnScreen = Rect()

    // Inside object for namespacing
    private val notificationScrimClippingParams =
        object {
            var isEnabled by mutableStateOf(false)
            var leftInset by mutableStateOf(0)
            var rightInset by mutableStateOf(0)
            var top by mutableStateOf(0)
            var bottom by mutableStateOf(0)
            var radius by mutableStateOf(0)

            fun dump(pw: IndentingPrintWriter) {
                pw.printSection("NotificationScrimClippingParams") {
                    pw.println("isEnabled", isEnabled)
                    pw.println("leftInset", "${leftInset}px")
                    pw.println("rightInset", "${rightInset}px")
                    pw.println("top", "${top}px")
                    pw.println("bottom", "${bottom}px")
                    pw.println("radius", "${radius}px")
                }
            }
        }

    override fun onStart() {
        super.onStart()
        registerDumpable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        QSComposeFragment.isUnexpectedlyInLegacyMode()
        viewModel = qsFragmentComposeViewModelFactory.create(lifecycleScope)

        qqsMediaHost.init(MediaHierarchyManager.LOCATION_QQS)
        qsMediaHost.init(MediaHierarchyManager.LOCATION_QS)
        setListenerCollections()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = inflater.context
        return ComposeView(context).apply {
            setBackPressedDispatcher()
            setContent {
                PlatformTheme {
                    val visible by viewModel.qsVisible.collectAsStateWithLifecycle()

                    AnimatedVisibility(
                        visible = visible,
                        modifier =
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                .thenIf(notificationScrimClippingParams.isEnabled) {
                                    Modifier.notificationScrimClip(
                                        notificationScrimClippingParams.leftInset,
                                        notificationScrimClippingParams.top,
                                        notificationScrimClippingParams.rightInset,
                                        notificationScrimClippingParams.bottom,
                                        notificationScrimClippingParams.radius,
                                    )
                                }
                                .graphicsLayer { elevation = 4.dp.toPx() },
                    ) {
                        val sceneState = remember {
                            MutableSceneTransitionLayoutState(
                                viewModel.expansionState.value.toIdleSceneKey(),
                                transitions =
                                    transitions {
                                        from(QuickQuickSettings, QuickSettings) {
                                            quickQuickSettingsToQuickSettings()
                                        }
                                    },
                            )
                        }

                        LaunchedEffect(Unit) {
                            synchronizeQsState(
                                sceneState,
                                viewModel.expansionState.map { it.progress },
                            )
                        }

                        SceneTransitionLayout(
                            state = sceneState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            scene(QuickSettings) { QuickSettingsElement() }

                            scene(QuickQuickSettings) { QuickQuickSettingsElement() }
                        }
                    }
                }
            }
        }
    }

    override fun setPanelView(notificationPanelView: QS.HeightListener?) {
        heightListener.value = notificationPanelView
    }

    override fun hideImmediately() {
        //        view?.animate()?.cancel()
        //        view?.y = -qsMinExpansionHeight.toFloat()
    }

    override fun getQsMinExpansionHeight(): Int {
        // TODO (b/353253277) implement split screen
        return qqsHeight.value
    }

    override fun getDesiredHeight(): Int {
        /*
         * Looking at the code, it seems that
         * * If customizing, then the height is that of the view post-layout, which is set by
         *   QSContainerImpl.calculateContainerHeight, which is the height the customizer takes
         * * If not customizing, it's the measured height. So we may want to surface that.
         */
        return view?.height ?: 0
    }

    override fun setHeightOverride(desiredHeight: Int) {
        viewModel.heightOverrideValue = desiredHeight
    }

    override fun setHeaderClickable(qsExpansionEnabled: Boolean) {
        // Empty method
    }

    override fun isCustomizing(): Boolean {
        return viewModel.containerViewModel.editModeViewModel.isEditing.value
    }

    override fun closeCustomizer() {
        viewModel.containerViewModel.editModeViewModel.stopEditing()
    }

    override fun setOverscrolling(overscrolling: Boolean) {
        viewModel.stackScrollerOverscrollingValue = overscrolling
    }

    override fun setExpanded(qsExpanded: Boolean) {
        viewModel.isQSExpanded = qsExpanded
    }

    override fun setListening(listening: Boolean) {
        // Not needed, views start listening and collection when composed
    }

    override fun setQsVisible(qsVisible: Boolean) {
        viewModel.isQSVisible = qsVisible
    }

    override fun isShowingDetail(): Boolean {
        return isCustomizing
    }

    override fun closeDetail() {
        closeCustomizer()
    }

    override fun animateHeaderSlidingOut() {
        // TODO(b/353254353)
    }

    override fun setQsExpansion(
        qsExpansionFraction: Float,
        panelExpansionFraction: Float,
        headerTranslation: Float,
        squishinessFraction: Float,
    ) {
        viewModel.qsExpansionValue = qsExpansionFraction
        viewModel.panelExpansionFractionValue = panelExpansionFraction
        viewModel.squishinessFractionValue = squishinessFraction

        // TODO(b/353254353) Handle header translation
    }

    override fun setHeaderListening(listening: Boolean) {
        // Not needed, header will start listening as soon as it's composed
    }

    override fun notifyCustomizeChanged() {
        // Not needed, only called from inside customizer
    }

    override fun setContainerController(controller: QSContainerController?) {
        qsContainerController.value = controller
    }

    override fun setCollapseExpandAction(action: Runnable?) {
        viewModel.collapseExpandAccessibilityAction = action
    }

    override fun getHeightDiff(): Int {
        return 0 // For now TODO(b/353254353)
    }

    override fun getHeader(): View? {
        QSComposeFragment.isUnexpectedlyInLegacyMode()
        return null
    }

    override fun setShouldUpdateSquishinessOnMedia(shouldUpdate: Boolean) {
        super.setShouldUpdateSquishinessOnMedia(shouldUpdate)
        // TODO (b/353253280)
    }

    override fun setInSplitShade(shouldTranslate: Boolean) {
        // TODO (b/356435605)
    }

    override fun setTransitionToFullShadeProgress(
        isTransitioningToFullShade: Boolean,
        qsTransitionFraction: Float,
        qsSquishinessFraction: Float,
    ) {
        super.setTransitionToFullShadeProgress(
            isTransitioningToFullShade,
            qsTransitionFraction,
            qsSquishinessFraction,
        )
    }

    override fun setFancyClipping(
        leftInset: Int,
        top: Int,
        rightInset: Int,
        bottom: Int,
        cornerRadius: Int,
        visible: Boolean,
        fullWidth: Boolean,
    ) {
        notificationScrimClippingParams.isEnabled = visible
        notificationScrimClippingParams.top = top
        notificationScrimClippingParams.bottom = bottom
        // Full width means that QS will show in the entire width allocated to it (for example
        // phone) vs. showing in a narrower column (for example, tablet portrait).
        notificationScrimClippingParams.leftInset = if (fullWidth) 0 else leftInset
        notificationScrimClippingParams.rightInset = if (fullWidth) 0 else rightInset
        notificationScrimClippingParams.radius = cornerRadius
    }

    override fun isFullyCollapsed(): Boolean {
        return viewModel.qsExpansionValue <= 0f
    }

    override fun setCollapsedMediaVisibilityChangedListener(listener: Consumer<Boolean>?) {
        // TODO (b/353253280)
    }

    override fun setScrollListener(scrollListener: QS.ScrollListener?) {
        this.scrollListener.value = scrollListener
    }

    override fun setOverScrollAmount(overScrollAmount: Int) {
        super.setOverScrollAmount(overScrollAmount)
    }

    override fun setIsNotificationPanelFullWidth(isFullWidth: Boolean) {
        viewModel.isSmallScreenValue = isFullWidth
    }

    override fun getHeaderTop(): Int {
        return qqsPositionOnRoot.top
    }

    override fun getHeaderBottom(): Int {
        return qqsPositionOnRoot.bottom
    }

    override fun getHeaderLeft(): Int {
        return qqsPositionOnRoot.left
    }

    override fun getHeaderBoundsOnScreen(outBounds: Rect) {
        outBounds.set(qqsPositionOnRoot)
        view?.getBoundsOnScreen(composeViewPositionOnScreen)
            ?: run { composeViewPositionOnScreen.setEmpty() }
        outBounds.offset(composeViewPositionOnScreen.left, composeViewPositionOnScreen.top)
    }

    override fun isHeaderShown(): Boolean {
        return qqsVisible.value
    }

    private fun setListenerCollections() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    //                    TODO
                    //                    setListenerJob(
                    //                            scrollListener,
                    //
                    //                    )
                }
                launch {
                    setListenerJob(
                        heightListener,
                        viewModel.containerViewModel.editModeViewModel.isEditing,
                    ) {
                        onQsHeightChanged()
                    }
                }
                launch {
                    setListenerJob(
                        qsContainerController,
                        viewModel.containerViewModel.editModeViewModel.isEditing,
                    ) {
                        setCustomizerShowing(it)
                    }
                }
            }
        }
    }

    @Composable
    private fun SceneScope.QuickQuickSettingsElement() {
        val qqsPadding by viewModel.qqsHeaderHeight.collectAsStateWithLifecycle()
        val bottomPadding = dimensionResource(id = R.dimen.qqs_layout_padding_bottom)
        DisposableEffect(Unit) {
            qqsVisible.value = true

            onDispose { qqsVisible.value = false }
        }
        Column(modifier = Modifier.sysuiResTag("quick_qs_panel")) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .onPlaced { coordinates ->
                            val (leftFromRoot, topFromRoot) = coordinates.positionInRoot().round()
                            qqsPositionOnRoot.set(
                                leftFromRoot,
                                topFromRoot,
                                leftFromRoot + coordinates.size.width,
                                topFromRoot + coordinates.size.height,
                            )
                        }
                        .onSizeChanged { size -> qqsHeight.value = size.height }
                        .padding(top = { qqsPadding }, bottom = { bottomPadding.roundToPx() })
            ) {
                val qsEnabled by viewModel.qsEnabled.collectAsStateWithLifecycle()
                if (qsEnabled) {
                    QuickQuickSettings(
                        viewModel = viewModel.containerViewModel.quickQuickSettingsViewModel,
                        modifier =
                            Modifier.collapseExpandSemanticAction(
                                    stringResource(
                                        id = R.string.accessibility_quick_settings_expand
                                    )
                                )
                                .padding(
                                    horizontal = {
                                        QuickSettingsShade.Dimensions.Padding.roundToPx()
                                    }
                                ),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun SceneScope.QuickSettingsElement() {
        val qqsPadding by viewModel.qqsHeaderHeight.collectAsStateWithLifecycle()
        val qsExtraPadding = dimensionResource(R.dimen.qs_panel_padding_top)
        Column(
            modifier =
                Modifier.collapseExpandSemanticAction(
                    stringResource(id = R.string.accessibility_quick_settings_collapse)
                )
        ) {
            val qsEnabled by viewModel.qsEnabled.collectAsStateWithLifecycle()
            if (qsEnabled) {
                Box(
                    modifier =
                        Modifier.element(ElementKeys.QuickSettingsContent).fillMaxSize().weight(1f)
                ) {
                    Column {
                        Spacer(
                            modifier = Modifier.height { qqsPadding + qsExtraPadding.roundToPx() }
                        )
                        ShadeBody(viewModel = viewModel.containerViewModel)
                    }
                }
                QuickSettingsTheme {
                    FooterActions(
                        viewModel = viewModel.footerActionsViewModel,
                        qsVisibilityLifecycleOwner = this@QSFragmentCompose,
                        modifier =
                            Modifier.sysuiResTag("qs_footer_actions")
                                .element(ElementKeys.FooterActions),
                    )
                }
            }
        }
    }

    private fun Modifier.collapseExpandSemanticAction(label: String): Modifier {
        return viewModel.collapseExpandAccessibilityAction?.let {
            semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(label) {
                            it.run()
                            true
                        }
                    )
            }
        } ?: this
    }

    private fun registerDumpable() {
        val instanceId = instanceProvider.getNextId()
        // Add an instanceId because the system may have more than 1 of these when re-inflating and
        // DumpManager doesn't like repeated identifiers. Also, put it first because DumpHandler
        // matches by end.
        val stringId = "$instanceId-QSFragmentCompose"
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    dumpManager.registerNormalDumpable(stringId, this@QSFragmentCompose)
                    awaitCancellation()
                } finally {
                    dumpManager.unregisterDumpable(stringId)
                }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            notificationScrimClippingParams.dump(this)
            printSection("QQS positioning") {
                println("qqsHeight", "${headerHeight}px")
                println("qqsTop", "${headerTop}px")
                println("qqsBottom", "${headerBottom}px")
                println("qqsLeft", "${headerLeft}px")
                println("qqsPositionOnRoot", qqsPositionOnRoot)
                val rect = Rect()
                getHeaderBoundsOnScreen(rect)
                println("qqsPositionOnScreen", rect)
            }
            println("QQS visible", qqsVisible.value)
            if (::viewModel.isInitialized) {
                printSection("View Model") { viewModel.dump(this@run, args) }
            }
        }
    }
}

private fun View.setBackPressedDispatcher() {
    repeatWhenAttached {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            setViewTreeOnBackPressedDispatcherOwner(
                object : OnBackPressedDispatcherOwner {
                    override val onBackPressedDispatcher =
                        OnBackPressedDispatcher().apply {
                            setOnBackInvokedDispatcher(it.viewRootImpl.onBackInvokedDispatcher)
                        }

                    override val lifecycle: Lifecycle = this@repeatWhenAttached.lifecycle
                }
            )
        }
    }
}

private suspend inline fun <Listener : Any, Data> setListenerJob(
    listenerFlow: MutableStateFlow<Listener?>,
    dataFlow: Flow<Data>,
    crossinline onCollect: suspend Listener.(Data) -> Unit,
) {
    coroutineScope {
        try {
            listenerFlow.collectLatest { listenerOrNull ->
                listenerOrNull?.let { currentListener ->
                    launch {
                        // Called when editing mode changes
                        dataFlow.collect { currentListener.onCollect(it) }
                    }
                }
            }
            awaitCancellation()
        } finally {
            listenerFlow.value = null
        }
    }
}

private val instanceProvider =
    object {
        private var currentId = 0

        fun getNextId(): Int {
            return currentId++
        }
    }

object SceneKeys {
    val QuickQuickSettings = SceneKey("QuickQuickSettingsScene")
    val QuickSettings = SceneKey("QuickSettingsScene")

    fun QSFragmentComposeViewModel.QSExpansionState.toIdleSceneKey(): SceneKey {
        return when {
            progress < 0.5f -> QuickQuickSettings
            else -> QuickSettings
        }
    }
}

suspend fun synchronizeQsState(state: MutableSceneTransitionLayoutState, expansion: Flow<Float>) {
    coroutineScope {
        val animationScope = this

        var currentTransition: ExpansionTransition? = null

        fun snapTo(scene: SceneKey) {
            state.snapToScene(scene)
            currentTransition = null
        }

        expansion.collectLatest { progress ->
            when (progress) {
                0f -> snapTo(QuickQuickSettings)
                1f -> snapTo(QuickSettings)
                else -> {
                    val transition = currentTransition
                    if (transition != null) {
                        transition.progress = progress
                        return@collectLatest
                    }

                    val newTransition =
                        ExpansionTransition(progress).also { currentTransition = it }
                    state.startTransitionImmediately(
                        animationScope = animationScope,
                        transition = newTransition,
                    )
                }
            }
        }
    }
}

private class ExpansionTransition(currentProgress: Float) :
    TransitionState.Transition.ChangeScene(
        fromScene = QuickQuickSettings,
        toScene = QuickSettings,
    ) {
    override val currentScene: SceneKey
        get() {
            // This should return the logical scene. If the QS STLState is only driven by
            // synchronizeQSState() then it probably does not matter which one we return, this is
            // only used to compute the current user actions of a STL.
            return QuickQuickSettings
        }

    override var progress: Float by mutableFloatStateOf(currentProgress)

    override val progressVelocity: Float
        get() = 0f

    override val isInitiatedByUserInput: Boolean
        get() = true

    override val isUserInputOngoing: Boolean
        get() = true

    private val finishCompletable = CompletableDeferred<Unit>()

    override suspend fun run() {
        // This transition runs until it is interrupted by another one.
        finishCompletable.await()
    }

    override fun freezeAndAnimateToCurrentState() {
        finishCompletable.complete(Unit)
    }
}
