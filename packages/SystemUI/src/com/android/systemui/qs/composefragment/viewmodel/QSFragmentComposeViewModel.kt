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

package com.android.systemui.qs.composefragment.viewmodel

import android.content.res.Resources
import android.graphics.Rect
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.LifecycleCoroutineScope
import com.android.app.animation.Interpolators
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.BouncerPanelExpansionCalculator
import com.android.systemui.Dumpable
import com.android.systemui.animation.ShadeInterpolation
import com.android.systemui.classifier.Classifier
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QQS
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager.Companion.LOCATION_QS
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule.QS_PANEL
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.QSEvent
import com.android.systemui.qs.composefragment.dagger.QSFragmentComposeLog
import com.android.systemui.qs.composefragment.dagger.QSFragmentComposeModule
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.panels.domain.interactor.TileSquishinessInteractor
import com.android.systemui.qs.panels.ui.viewmodel.InFirstPageViewModel
import com.android.systemui.qs.panels.ui.viewmodel.MediaInRowInLandscapeViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsContainerViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.disableflags.data.repository.DisableFlagsRepository
import com.android.systemui.statusbar.disableflags.domain.interactor.DisableFlagsInteractor
import com.android.systemui.util.LargeScreenUtils
import com.android.systemui.util.asIndenting
import com.android.systemui.util.kotlin.emitOnStart
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.io.PrintWriter
import javax.inject.Named
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
class QSFragmentComposeViewModel
@AssistedInject
constructor(
    containerViewModelFactory: QuickSettingsContainerViewModel.Factory,
    @Main private val resources: Resources,
    footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    private val sysuiStatusBarStateController: SysuiStatusBarStateController,
    deviceEntryInteractor: DeviceEntryInteractor,
    DisableFlagsInteractor: DisableFlagsInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val largeScreenShadeInterpolator: LargeScreenShadeInterpolator,
    private val shadeInteractor: ShadeInteractor,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    private val largeScreenHeaderHelper: LargeScreenHeaderHelper,
    private val squishinessInteractor: TileSquishinessInteractor,
    private val falsingInteractor: FalsingInteractor,
    private val inFirstPageViewModel: InFirstPageViewModel,
    @QSFragmentComposeLog private val tableLogBuffer: TableLogBuffer,
    mediaInRowInLandscapeViewModelFactory: MediaInRowInLandscapeViewModel.Factory,
    @Named(QUICK_QS_PANEL) val qqsMediaHost: MediaHost,
    @Named(QS_PANEL) val qsMediaHost: MediaHost,
    @Named(QSFragmentComposeModule.QS_USING_MEDIA_PLAYER) private val usingMedia: Boolean,
    private val uiEventLogger: UiEventLogger,
    @Assisted private val lifecycleScope: LifecycleCoroutineScope,
) : Dumpable, ExclusiveActivatable() {

    val containerViewModel = containerViewModelFactory.create(true)
    private val qqsMediaInRowViewModel = mediaInRowInLandscapeViewModelFactory.create(LOCATION_QQS)
    private val qsMediaInRowViewModel = mediaInRowInLandscapeViewModelFactory.create(LOCATION_QS)

    private val hydrator = Hydrator("QSFragmentComposeViewModel.hydrator", tableLogBuffer)

    val footerActionsViewModel =
        footerActionsViewModelFactory.create(lifecycleScope).also {
            lifecycleScope.launch { footerActionsController.init() }
        }

    var isQsExpanded by mutableStateOf(false)

    var isQsVisible by mutableStateOf(false)

    val isQsVisibleAndAnyShadeExpanded: Boolean
        get() = anyShadeExpanded && isQsVisible

    // This can only be negative if undefined (in which case it will be -1f), else it will be
    // in [0, 1]. In some cases, it could be set back to -1f internally to indicate that it's
    // different to every value in [0, 1].
    private var qsExpansion by mutableStateOf(-1f)

    fun setQsExpansionValue(value: Float) {
        if (value < 0f) {
            qsExpansion = -1f
        } else {
            qsExpansion = value.coerceIn(0f, 1f)
        }
    }

    val isQsFullyCollapsed by derivedStateOf { qsExpansion <= 0f }

    var panelExpansionFraction by mutableStateOf(0f)

    var squishinessFraction by mutableStateOf(1f)

    val qqsHeaderHeight by
        hydrator.hydratedStateOf(
            traceName = "qqsHeaderHeight",
            initialValue = 0,
            source =
                configurationInteractor.onAnyConfigurationChange.map {
                    if (LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources)) {
                        0
                    } else {
                        largeScreenHeaderHelper.getLargeScreenHeaderHeight()
                    }
                },
        )

    val qqsBottomPadding by
        hydrator.hydratedStateOf(
            traceName = "qqsBottomPadding",
            initialValue = resources.getDimensionPixelSize(R.dimen.qqs_layout_padding_bottom),
            source = configurationInteractor.dimensionPixelSize(R.dimen.qqs_layout_padding_bottom),
        )

    // Starting with a non-zero value makes it so that it has a non-zero height on first expansion
    // This is important for `QuickSettingsControllerImpl.mMinExpansionHeight` to detect a "change".
    var qqsHeight by mutableStateOf(1)

    var qsScrollHeight by mutableStateOf(0)

    val heightDiff: Int
        get() = qsScrollHeight - qqsHeight + qqsBottomPadding

    var isStackScrollerOverscrolling by mutableStateOf(false)

    var proposedTranslation by mutableStateOf(0f)

    /**
     * Whether QS is enabled by policy. This is normally true, except when it's disabled by some
     * policy. See [DisableFlagsRepository].
     */
    val isQsEnabled by
        hydrator.hydratedStateOf(
            traceName = "isQsEnabled",
            initialValue = DisableFlagsInteractor.disableFlags.value.isQuickSettingsEnabled(),
            source = DisableFlagsInteractor.disableFlags.map { it.isQuickSettingsEnabled() },
        )

    var isInSplitShade by mutableStateOf(false)

    var isTransitioningToFullShade by mutableStateOf(false)

    var lockscreenToShadeProgress by mutableStateOf(0f)

    var isSmallScreen by mutableStateOf(false)

    var heightOverride by mutableStateOf(-1)

    val expansionState by derivedStateOf {
        if (forceQs) {
            QSExpansionState(1f)
        } else {
            QSExpansionState(qsExpansion.coerceIn(0f, 1f))
        }
    }

    val isQsFullyExpanded by derivedStateOf { expansionState.progress >= 1f && isQsExpanded }

    /**
     * Accessibility action for collapsing/expanding QS. The provided runnable is responsible for
     * determining the correct action based on the expansion state.
     */
    var collapseExpandAccessibilityAction: Runnable? = null

    var overScrollAmount by mutableStateOf(0)

    val viewTranslationY by derivedStateOf {
        if (isOverscrolling) {
            overScrollAmount.toFloat()
        } else {
            if (onKeyguardAndExpanded) {
                translationScaleY * qqsHeight
            } else {
                headerTranslation
            }
        }
    }

    val qsScrollTranslationY by derivedStateOf {
        val panelTranslationY = translationScaleY * heightDiff
        if (onKeyguardAndExpanded) panelTranslationY else 0f
    }

    val viewAlpha by derivedStateOf {
        when {
            isInBouncerTransit ->
                BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(alphaProgress)
            isKeyguardState -> alphaProgress
            isSmallScreen -> ShadeInterpolation.getContentAlpha(alphaProgress)
            else -> largeScreenShadeInterpolator.getQsAlpha(alphaProgress)
        }
    }

    val showingMirror: Boolean
        get() = containerViewModel.brightnessSliderViewModel.showMirror

    // The initial values in these two are not meaningful. The flow will emit on start the correct
    // values. This is because we need to lazily fetch them after initMediaHosts.
    val qqsMediaVisible by
        hydrator.hydratedStateOf(
            traceName = "qqsMediaVisible",
            initialValue = usingMedia,
            source =
                if (usingMedia) {
                    mediaHostVisible(qqsMediaHost)
                } else {
                    flowOf(false)
                },
        )

    val qqsMediaInRow: Boolean
        get() = qqsMediaInRowViewModel.shouldMediaShowInRow

    val qsMediaVisible by
        hydrator.hydratedStateOf(
            traceName = "qsMediaVisible",
            initialValue = usingMedia,
            source = if (usingMedia) mediaHostVisible(qsMediaHost) else flowOf(false),
        )

    val qsMediaInRow: Boolean
        get() = qsMediaInRowViewModel.shouldMediaShowInRow

    var shouldUpdateSquishinessOnMedia by mutableStateOf(false)

    val qsMediaTranslationY by derivedStateOf {
        if (
            qsExpansion > 0f &&
                !isKeyguardState &&
                !qqsMediaVisible &&
                !qsMediaInRow &&
                !isInSplitShade
        ) {
            val interpolation = Interpolators.ACCELERATE.getInterpolation(1f - qsExpansion)
            -qsMediaHost.hostView.height * 1.3f * interpolation
        } else {
            0f
        }
    }

    val animateTilesExpansion: Boolean
        get() = inFirstPage && !mediaSuddenlyAppearingInLandscape

    private val inFirstPage: Boolean
        get() = inFirstPageViewModel.inFirstPage

    private val mediaSuddenlyAppearingInLandscape: Boolean
        get() = !qqsMediaInRow && qsMediaInRow

    private val collapsedLandscapeMedia by
        hydrator.hydratedStateOf(
            traceName = "collapsedLandscapeMedia",
            initialValue = resources.getBoolean(R.bool.config_quickSettingsMediaLandscapeCollapsed),
            source =
                configurationInteractor.onAnyConfigurationChange.emitOnStart().map {
                    resources.getBoolean(R.bool.config_quickSettingsMediaLandscapeCollapsed)
                },
        )

    private val qqsMediaExpansion: Float
        get() =
            if (qqsMediaInRow && collapsedLandscapeMedia) {
                MediaHostState.COLLAPSED
            } else {
                MediaHostState.EXPANDED
            }

    private val shouldApplySquishinessToMedia by derivedStateOf {
        shouldUpdateSquishinessOnMedia || (isInSplitShade && statusBarState == StatusBarState.SHADE)
    }

    private val mediaSquishiness by derivedStateOf {
        if (shouldApplySquishinessToMedia) {
            squishinessFraction
        } else {
            1f
        }
    }

    private var qsBounds by mutableStateOf(Rect())

    private val constrainedSquishinessFraction: Float
        get() = squishinessFraction.constrainSquishiness()

    private var _headerAnimating by mutableStateOf(false)

    /**
     * Tracks the current [StatusBarState]. It will switch early if the upcoming state is
     * [StatusBarState.KEYGUARD]
     */
    @get:VisibleForTesting
    val statusBarState by
        hydrator.hydratedStateOf(
            traceName = "statusBarState",
            initialValue = sysuiStatusBarStateController.state,
            source =
                conflatedCallbackFlow {
                        val callback =
                            object : StatusBarStateController.StateListener {
                                override fun onStateChanged(newState: Int) {
                                    trySend(newState)
                                }

                                override fun onUpcomingStateChanged(upcomingState: Int) {
                                    if (upcomingState == StatusBarState.KEYGUARD) {
                                        trySend(upcomingState)
                                    }
                                }
                            }
                        sysuiStatusBarStateController.addCallback(callback)

                        awaitClose { sysuiStatusBarStateController.removeCallback(callback) }
                    }
                    .onStart { emit(sysuiStatusBarStateController.state) },
        )

    private val isKeyguardState: Boolean
        get() = statusBarState == StatusBarState.KEYGUARD

    private var viewHeight by mutableStateOf(0)

    private val isBypassEnabled by
        hydrator.hydratedStateOf(
            traceName = "isBypassEnabled",
            source = deviceEntryInteractor.isBypassEnabled,
        )

    private val showCollapsedOnKeyguard by derivedStateOf {
        isBypassEnabled || (isTransitioningToFullShade && !isInSplitShade)
    }

    private val onKeyguardAndExpanded: Boolean
        get() = isKeyguardState && !showCollapsedOnKeyguard

    private val isOverscrolling: Boolean
        get() = overScrollAmount != 0

    private val forceQs by derivedStateOf {
        (isQsExpanded || isStackScrollerOverscrolling) &&
            (isKeyguardState && !showCollapsedOnKeyguard)
    }

    private val translationScaleY: Float
        get() = ((qsExpansion - 1) * (if (isInSplitShade) 1f else SHORT_PARALLAX_AMOUNT))

    private val headerTranslation by derivedStateOf {
        if (isTransitioningToFullShade) 0f else proposedTranslation
    }

    private val alphaProgress by derivedStateOf {
        when {
            isSmallScreen -> 1f
            isInSplitShade ->
                if (isTransitioningToFullShade || isKeyguardState) {
                    lockscreenToShadeProgress
                } else {
                    panelExpansionFraction
                }
            isTransitioningToFullShade -> lockscreenToShadeProgress
            else -> panelExpansionFraction
        }
    }

    private val isInBouncerTransit by
        hydrator.hydratedStateOf(
            traceName = "isInBouncerTransit",
            initialValue = false,
            source =
                keyguardTransitionInteractor.isInTransition(
                    Edge.create(to = Scenes.Bouncer),
                    Edge.create(to = KeyguardState.PRIMARY_BOUNCER),
                ),
        )

    private val anyShadeExpanded by
        hydrator.hydratedStateOf(
            traceName = "anyShadeExpanded",
            source = shadeInteractor.isAnyExpanded,
        )

    fun applyNewQsScrollerBounds(left: Float, top: Float, right: Float, bottom: Float) {
        if (usingMedia) {
            qsMediaHost.currentClipping.set(
                left.toInt(),
                top.toInt(),
                right.toInt(),
                bottom.toInt(),
            )
        }
    }

    fun emitMotionEventForFalsingSwipeNested() {
        falsingInteractor.isFalseTouch(Classifier.QS_SWIPE_NESTED)
    }

    fun onQQSOpen() {
        uiEventLogger.log(QSEvent.QQS_PANEL_EXPANDED)
    }

    fun onQSOpen() {
        uiEventLogger.log(QSEvent.QS_PANEL_EXPANDED)
    }

    override suspend fun onActivated(): Nothing {
        initMediaHosts() // init regardless of using media (same as current QS).
        coroutineScope {
            launch { hydrateSquishinessInteractor() }
            if (usingMedia) {
                launch { hydrateQqsMediaExpansion() }
                launch { hydrateMediaSquishiness() }
                launch { hydrateMediaDisappearParameters() }
            }
            launch { hydrator.activate() }
            launch { containerViewModel.activate() }
            launch { qqsMediaInRowViewModel.activate() }
            launch { qsMediaInRowViewModel.activate() }
            awaitCancellation()
        }
    }

    private fun initMediaHosts() {
        qqsMediaHost.apply {
            expansion = qqsMediaExpansion
            showsOnlyActiveMedia = true
            init(MediaHierarchyManager.LOCATION_QQS)
        }
        qsMediaHost.apply {
            expansion = MediaHostState.EXPANDED
            showsOnlyActiveMedia = false
            init(MediaHierarchyManager.LOCATION_QS)
        }
    }

    private suspend fun hydrateSquishinessInteractor() {
        snapshotFlow { constrainedSquishinessFraction }
            .collect { squishinessInteractor.setSquishinessValue(it) }
    }

    private suspend fun hydrateQqsMediaExpansion() {
        snapshotFlow { qqsMediaExpansion }.collect { qqsMediaHost.expansion = it }
    }

    private suspend fun hydrateMediaSquishiness() {
        snapshotFlow { mediaSquishiness }.collect { qsMediaHost.squishFraction = it }
    }

    private suspend fun hydrateMediaDisappearParameters() {
        coroutineScope {
            launch {
                snapshotFlow { qqsMediaInRow }.collect { qqsMediaHost.applyDisappearParameters(it) }
            }
            launch {
                snapshotFlow { qsMediaInRow }.collect { qsMediaHost.applyDisappearParameters(it) }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            printSection("Quick Settings state") {
                println("isQSExpanded", isQsExpanded)
                println("isQSVisible", isQsVisible)
                println("anyShadeExpanded", anyShadeExpanded)
                println("isQSVisibleAndAnyShadeExpanded", isQsVisibleAndAnyShadeExpanded)
                println("isQSEnabled", isQsEnabled)
                println("isCustomizing", containerViewModel.editModeViewModel.isEditing.value)
                println("inFirstPage", inFirstPage)
            }
            printSection("Expansion state") {
                println("qsExpansion", qsExpansion)
                println("panelExpansionFraction", panelExpansionFraction)
                println("squishinessFraction", squishinessFraction)
                println("proposedTranslation", proposedTranslation)
                println("expansionState", expansionState)
                println("forceQS", forceQs)
                printSection("Derived values") {
                    println("headerTranslation", headerTranslation)
                    println("translationScaleY", translationScaleY)
                    println("viewTranslationY", viewTranslationY)
                    println("qsScrollTranslationY", qsScrollTranslationY)
                    println("viewAlpha", viewAlpha)
                }
            }
            printSection("Shade state") {
                println("stackOverscrolling", isStackScrollerOverscrolling)
                println("overscrollAmount", overScrollAmount)
                println("statusBarState", StatusBarState.toString(statusBarState))
                println("isKeyguardState", isKeyguardState)
                println("isSmallScreen", isSmallScreen)
                println("heightOverride", "${heightOverride}px")
                println("qqsHeaderHeight", "${qqsHeaderHeight}px")
                println("qqsBottomPadding", "${qqsBottomPadding}px")
                println("isSplitShade", isInSplitShade)
                println("showCollapsedOnKeyguard", showCollapsedOnKeyguard)
                println("qqsHeight", "${qqsHeight}px")
                println("qsScrollHeight", "${qsScrollHeight}px")
            }
            printSection("Media") {
                println("qqsMediaVisible", qqsMediaVisible)
                println("qqsMediaInRow", qqsMediaInRow)
                println("qsMediaVisible", qsMediaVisible)
                println("qsMediaInRow", qsMediaInRow)
                println("collapsedLandscapeMedia", collapsedLandscapeMedia)
                println("qqsMediaExpansion", qqsMediaExpansion)
                println("shouldUpdateSquishinessOnMedia", shouldUpdateSquishinessOnMedia)
                println("mediaSquishiness", mediaSquishiness)
                println("qsMediaTranslationY", qsMediaTranslationY)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(lifecycleScope: LifecycleCoroutineScope): QSFragmentComposeViewModel
    }

    // In the future, this may have other relevant elements.
    data class QSExpansionState(@FloatRange(0.0, 1.0) val progress: Float)
}

private fun Float.constrainSquishiness(): Float {
    return (0.1f + this * 0.9f).coerceIn(0f, 1f)
}

private val SHORT_PARALLAX_AMOUNT = 0.1f

/**
 * Returns a flow to track the visibility of a [MediaHost]. The flow will emit on start the visible
 * state of the view.
 */
private fun mediaHostVisible(mediaHost: MediaHost): Flow<Boolean> {
    return callbackFlow {
            val listener: (Boolean) -> Unit = { visible: Boolean -> trySend(visible) }
            mediaHost.addVisibilityChangeListener(listener)

            awaitClose { mediaHost.removeVisibilityChangeListener(listener) }
        }
        // Need to use this to set initial state because on creation of the media host, the
        // view visibility is not in sync with [MediaHost.visible], which is what we track with
        // the listener. The correct state is set as part of init, so we need to get the state
        // lazily.
        .onStart { emit(mediaHost.visible) }
}

// Taken from QSPanelControllerBase
private fun MediaHost.applyDisappearParameters(inRow: Boolean) {
    disappearParameters.apply {
        fadeStartPosition = 0.95f
        disappearStart = 0f
        if (inRow) {
            disappearSize.set(0f, 0.4f)
            gonePivot.set(1f, 0f)
            contentTranslationFraction.set(0.25f, 1f)
            disappearEnd = 0.6f
        } else {
            disappearSize.set(1f, 0f)
            gonePivot.set(0f, 0f)
            contentTranslationFraction.set(0f, 1f)
            disappearEnd = 0.95f
        }
    }
}
