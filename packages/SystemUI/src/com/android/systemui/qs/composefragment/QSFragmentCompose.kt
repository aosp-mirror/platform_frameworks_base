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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.round
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.modifiers.height
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule.QS_PANEL
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.composefragment.ui.notificationScrimClip
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.panels.ui.compose.QuickQuickSettings
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.qs.ui.composable.ShadeBody
import com.android.systemui.res.R
import com.android.systemui.util.LifecycleFragment
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@SuppressLint("ValidFragment")
class QSFragmentCompose
@Inject
constructor(
    private val qsFragmentComposeViewModelFactory: QSFragmentComposeViewModel.Factory,
    @Named(QUICK_QS_PANEL) private val qqsMediaHost: MediaHost,
    @Named(QS_PANEL) private val qsMediaHost: MediaHost,
) : LifecycleFragment(), QS {

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
        savedInstanceState: Bundle?
    ): View {
        val context = inflater.context
        return ComposeView(context).apply {
            setBackPressedDispatcher()
            setContent {
                PlatformTheme {
                    val visible by viewModel.qsVisible.collectAsStateWithLifecycle()
                    val qsState by viewModel.expansionState.collectAsStateWithLifecycle()

                    AnimatedVisibility(
                        visible = visible,
                        modifier =
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars).thenIf(
                                notificationScrimClippingParams.isEnabled
                            ) {
                                Modifier.notificationScrimClip(
                                    notificationScrimClippingParams.leftInset,
                                    notificationScrimClippingParams.top,
                                    notificationScrimClippingParams.rightInset,
                                    notificationScrimClippingParams.bottom,
                                    notificationScrimClippingParams.radius,
                                )
                            }
                    ) {
                        AnimatedContent(targetState = qsState) {
                            when (it) {
                                QSFragmentComposeViewModel.QSExpansionState.QQS -> {
                                    QuickQuickSettingsElement()
                                }
                                QSFragmentComposeViewModel.QSExpansionState.QS -> {
                                    QuickSettingsElement()
                                }
                                else -> {}
                            }
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
        squishinessFraction: Float
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
        qsSquishinessFraction: Float
    ) {
        super.setTransitionToFullShadeProgress(
            isTransitioningToFullShade,
            qsTransitionFraction,
            qsSquishinessFraction
        )
    }

    override fun setFancyClipping(
        leftInset: Int,
        top: Int,
        rightInset: Int,
        bottom: Int,
        cornerRadius: Int,
        visible: Boolean,
        fullWidth: Boolean
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
        return viewModel.qqsHeaderHeight.value
    }

    override fun getHeaderBottom(): Int {
        return headerTop + qqsHeight.value
    }

    override fun getHeaderLeft(): Int {
        return qqsPositionOnRoot.left
    }

    override fun getHeaderBoundsOnScreen(outBounds: Rect) {
        outBounds.set(qqsPositionOnRoot)
        view?.getBoundsOnScreen(composeViewPositionOnScreen)
            ?: run { composeViewPositionOnScreen.setEmpty() }
        qqsPositionOnRoot.offset(composeViewPositionOnScreen.left, composeViewPositionOnScreen.top)
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
                        viewModel.containerViewModel.editModeViewModel.isEditing
                    ) {
                        onQsHeightChanged()
                    }
                }
                launch {
                    setListenerJob(
                        qsContainerController,
                        viewModel.containerViewModel.editModeViewModel.isEditing
                    ) {
                        setCustomizerShowing(it)
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickQuickSettingsElement() {
        val qqsPadding by viewModel.qqsHeaderHeight.collectAsStateWithLifecycle()
        DisposableEffect(Unit) {
            qqsVisible.value = true

            onDispose { qqsVisible.value = false }
        }
        Column(modifier = Modifier.sysuiResTag("quick_qs_panel")) {
            Box(modifier = Modifier.fillMaxWidth()) {
                val qsEnabled by viewModel.qsEnabled.collectAsStateWithLifecycle()
                if (qsEnabled) {
                    QuickQuickSettings(
                        viewModel = viewModel.containerViewModel.quickQuickSettingsViewModel,
                        modifier =
                            Modifier.onGloballyPositioned { coordinates ->
                                    val (leftFromRoot, topFromRoot) =
                                        coordinates.positionInRoot().round()
                                    val (width, height) = coordinates.size
                                    qqsPositionOnRoot.set(
                                        leftFromRoot,
                                        topFromRoot,
                                        leftFromRoot + width,
                                        topFromRoot + height
                                    )
                                }
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    qqsHeight.value = placeable.height

                                    layout(placeable.width, placeable.height) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .padding(top = { qqsPadding })
                                .collapseExpandSemanticAction(
                                    stringResource(
                                        id = R.string.accessibility_quick_settings_expand
                                    )
                                )
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun QuickSettingsElement() {
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
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
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
                        modifier = Modifier.sysuiResTag("qs_footer_actions")
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
    crossinline onCollect: suspend Listener.(Data) -> Unit
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
