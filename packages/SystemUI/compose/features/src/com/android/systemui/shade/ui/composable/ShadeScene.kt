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

package com.android.systemui.shade.ui.composable

import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexContentPicker
import com.android.compose.animation.scene.NestedScrollBehavior
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateSceneDpAsState
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.controls.ui.composable.MediaContentPicker
import com.android.systemui.media.controls.ui.composable.shouldElevateMedia
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaHostState.Companion.COLLAPSED
import com.android.systemui.media.controls.ui.view.MediaHostState.Companion.EXPANDED
import com.android.systemui.media.dagger.MediaModule.QS_PANEL
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.notifications.ui.composable.NotificationScrollingStack
import com.android.systemui.notifications.ui.composable.NotificationStackCutoffGuideline
import com.android.systemui.qs.footer.ui.compose.FooterActionsWithAnimatedVisibility
import com.android.systemui.qs.ui.composable.BrightnessMirror
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.qs.ui.composable.QuickSettings.SharedValues.MediaLandscapeTopOffset
import com.android.systemui.qs.ui.composable.QuickSettings.SharedValues.MediaOffset.InQQS
import com.android.systemui.res.R
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.ShadeSceneContentViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeUserActionsViewModel
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import com.android.systemui.util.Utils
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

object Shade {
    object Elements {
        val BackgroundScrim =
            ElementKey("ShadeBackgroundScrim", contentPicker = LowestZIndexContentPicker)
        val SplitShadeStartColumn = ElementKey("SplitShadeStartColumn")
    }

    object Dimensions {
        val ScrimCornerSize = 32.dp
        val HorizontalPadding = 16.dp
        val ScrimOverscrollLimit = 32.dp
        const val ScrimVisibilityThreshold = 5f
    }

    object Shapes {
        val Scrim =
            RoundedCornerShape(
                topStart = Dimensions.ScrimCornerSize,
                topEnd = Dimensions.ScrimCornerSize,
            )
    }
}

/** The shade scene shows scrolling list of notifications and some of the quick setting tiles. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeScene
@Inject
constructor(
    private val shadeSession: SaveableSession,
    private val notificationStackScrollView: Lazy<NotificationScrollView>,
    private val actionsViewModelFactory: ShadeUserActionsViewModel.Factory,
    private val contentViewModelFactory: ShadeSceneContentViewModel.Factory,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
    private val mediaCarouselController: MediaCarouselController,
    @Named(QUICK_QS_PANEL) private val qqsMediaHost: MediaHost,
    @Named(QS_PANEL) private val qsMediaHost: MediaHost,
) : ExclusiveActivatable(), Scene {

    override val key = Scenes.Shade

    private val actionsViewModel: ShadeUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    @Composable
    override fun SceneScope.Content(modifier: Modifier) =
        ShadeScene(
            notificationStackScrollView.get(),
            viewModel =
                rememberViewModel("ShadeScene-viewModel") { contentViewModelFactory.create() },
            notificationsPlaceholderViewModel =
                rememberViewModel("ShadeScene-notifPlaceholderViewModel") {
                    notificationsPlaceholderViewModelFactory.create()
                },
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            mediaCarouselController = mediaCarouselController,
            qqsMediaHost = qqsMediaHost,
            qsMediaHost = qsMediaHost,
            modifier = modifier,
            shadeSession = shadeSession,
        )

    init {
        qqsMediaHost.expansion = MediaHostState.EXPANDED
        qqsMediaHost.showsOnlyActiveMedia = true
        qqsMediaHost.init(MediaHierarchyManager.LOCATION_QQS)

        qsMediaHost.expansion = MediaHostState.EXPANDED
        qsMediaHost.showsOnlyActiveMedia = false
        qsMediaHost.init(MediaHierarchyManager.LOCATION_QS)
    }
}

@Composable
private fun SceneScope.ShadeScene(
    notificationStackScrollView: NotificationScrollView,
    viewModel: ShadeSceneContentViewModel,
    notificationsPlaceholderViewModel: NotificationsPlaceholderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    qqsMediaHost: MediaHost,
    qsMediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (layoutState.currentTransition?.fromContent == Scenes.Gone) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        }
    }

    val shadeMode by viewModel.shadeMode.collectAsStateWithLifecycle()
    when (shadeMode) {
        is ShadeMode.Single ->
            SingleShade(
                notificationStackScrollView = notificationStackScrollView,
                viewModel = viewModel,
                notificationsPlaceholderViewModel = notificationsPlaceholderViewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                mediaCarouselController = mediaCarouselController,
                mediaHost = qqsMediaHost,
                modifier = modifier,
                shadeSession = shadeSession,
            )
        is ShadeMode.Split ->
            SplitShade(
                notificationStackScrollView = notificationStackScrollView,
                viewModel = viewModel,
                notificationsPlaceholderViewModel = notificationsPlaceholderViewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                mediaCarouselController = mediaCarouselController,
                mediaHost = qsMediaHost,
                modifier = modifier,
                shadeSession = shadeSession,
            )
        is ShadeMode.Dual -> error("Dual shade is not yet implemented!")
    }
}

@Composable
private fun SceneScope.SingleShade(
    notificationStackScrollView: NotificationScrollView,
    viewModel: ShadeSceneContentViewModel,
    notificationsPlaceholderViewModel: NotificationsPlaceholderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val cutoutLocation = LocalDisplayCutout.current.location
    val cutoutInsets = WindowInsets.Companion.displayCutout
    val isLandscape = LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Compact
    val usingCollapsedLandscapeMedia =
        Utils.useCollapsedMediaInLandscape(LocalContext.current.resources)
    val isExpanded = !usingCollapsedLandscapeMedia || !isLandscape
    mediaHost.expansion = if (isExpanded) EXPANDED else COLLAPSED

    var maxNotifScrimTop by remember { mutableIntStateOf(0) }
    val tileSquishiness by
        animateSceneFloatAsState(
            value = 1f,
            key = QuickSettings.SharedValues.TilesSquishiness,
            canOverflow = false,
        )
    val isEmptySpaceClickable by viewModel.isEmptySpaceClickable.collectAsStateWithLifecycle()
    val isMediaVisible by viewModel.isMediaVisible.collectAsStateWithLifecycle()

    val shouldPunchHoleBehindScrim =
        layoutState.isTransitioningBetween(Scenes.Gone, Scenes.Shade) ||
            layoutState.isTransitioningBetween(Scenes.Lockscreen, Scenes.Shade)
    // Media is visible and we are in landscape on a small height screen
    val mediaInRow = isMediaVisible && isLandscape
    val mediaOffset by
        animateSceneDpAsState(value = InQQS, key = MediaLandscapeTopOffset, canOverflow = false)

    val navBarHeight = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val mediaOffsetProvider = remember {
        ShadeMediaOffsetProvider.Qqs(
            { @Suppress("UNUSED_EXPRESSION") tileSquishiness },
            viewModel.qsSceneAdapter,
        )
    }
    val shadeHorizontalPadding =
        dimensionResource(id = R.dimen.notification_panel_margin_horizontal)
    val shadeMeasurePolicy =
        remember(mediaInRow) {
            SingleShadeMeasurePolicy(
                isMediaInRow = mediaInRow,
                mediaOffset = { mediaOffset.roundToPx() },
                onNotificationsTopChanged = { maxNotifScrimTop = it },
                mediaZIndex = {
                    if (MediaContentPicker.shouldElevateMedia(layoutState)) 1f else 0f
                },
                cutoutInsetsProvider = {
                    if (cutoutLocation == CutoutLocation.CENTER) {
                        null
                    } else {
                        cutoutInsets
                    }
                },
            )
        }

    Box(
        modifier =
            modifier.thenIf(shouldPunchHoleBehindScrim) {
                // Render the scene to an offscreen buffer so that BlendMode.DstOut only clears this
                // scene
                // (and not the one under it) during a scene transition.
                Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            }
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .element(Shade.Elements.BackgroundScrim)
                    .background(colorResource(R.color.shade_scrim_background_dark))
        )
        Layout(
            modifier =
                Modifier.thenIf(isEmptySpaceClickable) {
                    Modifier.clickable { viewModel.onEmptySpaceClicked() }
                },
            content = {
                CollapsedShadeHeader(
                    viewModelFactory = viewModel.shadeHeaderViewModelFactory,
                    createTintedIconManager = createTintedIconManager,
                    createBatteryMeterViewController = createBatteryMeterViewController,
                    statusBarIconController = statusBarIconController,
                    modifier = Modifier.layoutId(SingleShadeMeasurePolicy.LayoutId.ShadeHeader),
                )

                Box(
                    Modifier.element(QuickSettings.Elements.QuickQuickSettings)
                        .layoutId(SingleShadeMeasurePolicy.LayoutId.QuickSettings)
                        .padding(horizontal = shadeHorizontalPadding)
                ) {
                    QuickSettings(
                        viewModel.qsSceneAdapter,
                        { viewModel.qsSceneAdapter.qqsHeight },
                        isSplitShade = false,
                        squishiness = { tileSquishiness },
                    )
                }

                ShadeMediaCarousel(
                    isVisible = isMediaVisible,
                    isInRow = mediaInRow,
                    mediaHost = mediaHost,
                    mediaOffsetProvider = mediaOffsetProvider,
                    carouselController = mediaCarouselController,
                    modifier = Modifier.layoutId(SingleShadeMeasurePolicy.LayoutId.Media),
                )

                NotificationScrollingStack(
                    shadeSession = shadeSession,
                    stackScrollView = notificationStackScrollView,
                    viewModel = notificationsPlaceholderViewModel,
                    maxScrimTop = { maxNotifScrimTop.toFloat() },
                    shadeMode = ShadeMode.Single,
                    shouldPunchHoleBehindScrim = shouldPunchHoleBehindScrim,
                    onEmptySpaceClick =
                        viewModel::onEmptySpaceClicked.takeIf { isEmptySpaceClickable },
                    modifier =
                        Modifier.layoutId(SingleShadeMeasurePolicy.LayoutId.Notifications)
                            .padding(horizontal = shadeHorizontalPadding),
                )
            },
            measurePolicy = shadeMeasurePolicy,
        )
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .height(navBarHeight)
                    .pointerInteropFilter { true }
                    .verticalNestedScrollToScene(
                        topBehavior = NestedScrollBehavior.EdgeAlways,
                        isExternalOverscrollGesture = { false },
                    )
        ) {
            NotificationStackCutoffGuideline(
                stackScrollView = notificationStackScrollView,
                viewModel = notificationsPlaceholderViewModel,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun SceneScope.SplitShade(
    notificationStackScrollView: NotificationScrollView,
    viewModel: ShadeSceneContentViewModel,
    notificationsPlaceholderViewModel: NotificationsPlaceholderViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val screenCornerRadius = LocalScreenCornerRadius.current

    val isCustomizing by viewModel.qsSceneAdapter.isCustomizing.collectAsStateWithLifecycle()
    val isCustomizerShowing by
        viewModel.qsSceneAdapter.isCustomizerShowing.collectAsStateWithLifecycle()
    val customizingAnimationDuration by
        viewModel.qsSceneAdapter.customizerAnimationDuration.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val footerActionsViewModel =
        remember(lifecycleOwner, viewModel) { viewModel.getFooterActionsViewModel(lifecycleOwner) }
    val tileSquishiness by
        animateSceneFloatAsState(
            value = 1f,
            key = QuickSettings.SharedValues.TilesSquishiness,
            canOverflow = false,
        )
    val unfoldTranslationXForStartSide by
        viewModel.unfoldTranslationX(isOnStartSide = true).collectAsStateWithLifecycle(0f)
    val unfoldTranslationXForEndSide by
        viewModel.unfoldTranslationX(isOnStartSide = false).collectAsStateWithLifecycle(0f)

    val navBarBottomHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding by
        animateDpAsState(
            targetValue = if (isCustomizing) 0.dp else navBarBottomHeight,
            animationSpec = tween(customizingAnimationDuration),
            label = "animateQSSceneBottomPaddingAsState",
        )
    val density = LocalDensity.current
    LaunchedEffect(navBarBottomHeight, density) {
        with(density) {
            viewModel.qsSceneAdapter.applyBottomNavBarPadding(navBarBottomHeight.roundToPx())
        }
    }

    val quickSettingsScrollState = rememberScrollState()
    val isScrollable = layoutState.transitionState is TransitionState.Idle
    LaunchedEffect(isCustomizing, quickSettingsScrollState) {
        if (isCustomizing) {
            quickSettingsScrollState.scrollTo(0)
        }
    }

    val brightnessMirrorViewModel =
        rememberViewModel("SplitShade-brightnessMirrorViewModel") {
            viewModel.brightnessMirrorViewModelFactory.create()
        }
    val brightnessMirrorShowing by brightnessMirrorViewModel.isShowing.collectAsStateWithLifecycle()
    val contentAlpha by
        animateFloatAsState(
            targetValue = if (brightnessMirrorShowing) 0f else 1f,
            label = "alphaAnimationBrightnessMirrorContentHiding",
        )

    notificationsPlaceholderViewModel.setAlphaForBrightnessMirror(contentAlpha)
    DisposableEffect(Unit) {
        onDispose { notificationsPlaceholderViewModel.setAlphaForBrightnessMirror(1f) }
    }

    val isEmptySpaceClickable by viewModel.isEmptySpaceClickable.collectAsStateWithLifecycle()
    val isMediaVisible by viewModel.isMediaVisible.collectAsStateWithLifecycle()

    val brightnessMirrorShowingModifier = Modifier.graphicsLayer { alpha = contentAlpha }

    val mediaOffsetProvider = remember {
        ShadeMediaOffsetProvider.Qs(
            { @Suppress("UNUSED_EXPRESSION") tileSquishiness },
            viewModel.qsSceneAdapter,
        )
    }

    Box {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .element(Shade.Elements.BackgroundScrim)
                    // Cannot set the alpha of the whole element to 0, because the mirror should be
                    // in the QS column.
                    .background(
                        colorResource(R.color.shade_scrim_background_dark)
                            .copy(alpha = contentAlpha)
                    )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            CollapsedShadeHeader(
                viewModelFactory = viewModel.shadeHeaderViewModelFactory,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                modifier =
                    Modifier.then(brightnessMirrorShowingModifier)
                        .padding(horizontal = { unfoldTranslationXForStartSide.roundToInt() }),
            )

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier =
                        Modifier.element(Shade.Elements.SplitShadeStartColumn)
                            .weight(1f)
                            .graphicsLayer { translationX = unfoldTranslationXForStartSide }
                ) {
                    BrightnessMirror(
                        viewModel = brightnessMirrorViewModel,
                        qsSceneAdapter = viewModel.qsSceneAdapter,
                        // Need to use the offset measured from the container as the header
                        // has to be accounted for
                        measureFromContainer = true,
                    )
                    Column(
                        verticalArrangement = Arrangement.Top,
                        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
                    ) {
                        Column(
                            modifier =
                                Modifier.fillMaxSize()
                                    .sysuiResTag("expanded_qs_scroll_view")
                                    .weight(1f)
                                    .thenIf(!isCustomizerShowing) {
                                        Modifier.verticalScroll(
                                                quickSettingsScrollState,
                                                enabled = isScrollable,
                                            )
                                            .clipScrollableContainer(Orientation.Horizontal)
                                    }
                                    .then(brightnessMirrorShowingModifier)
                        ) {
                            Box(
                                modifier =
                                    Modifier.element(QuickSettings.Elements.SplitShadeQuickSettings)
                            ) {
                                QuickSettings(
                                    qsSceneAdapter = viewModel.qsSceneAdapter,
                                    heightProvider = { viewModel.qsSceneAdapter.qsHeight },
                                    isSplitShade = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    squishiness = { tileSquishiness },
                                )
                            }

                            ShadeMediaCarousel(
                                isVisible = isMediaVisible,
                                isInRow = false,
                                mediaHost = mediaHost,
                                mediaOffsetProvider = mediaOffsetProvider,
                                modifier =
                                    Modifier.thenIf(
                                        MediaContentPicker.shouldElevateMedia(layoutState)
                                    ) {
                                        Modifier.zIndex(1f)
                                    },
                                carouselController = mediaCarouselController,
                            )
                        }
                        FooterActionsWithAnimatedVisibility(
                            viewModel = footerActionsViewModel,
                            isCustomizing = isCustomizing,
                            customizingAnimationDuration = customizingAnimationDuration,
                            lifecycleOwner = lifecycleOwner,
                            modifier =
                                Modifier.align(Alignment.CenterHorizontally)
                                    .sysuiResTag("qs_footer_actions")
                                    .then(brightnessMirrorShowingModifier),
                        )
                    }
                }

                NotificationScrollingStack(
                    shadeSession = shadeSession,
                    stackScrollView = notificationStackScrollView,
                    viewModel = notificationsPlaceholderViewModel,
                    maxScrimTop = { 0f },
                    shouldPunchHoleBehindScrim = false,
                    shouldReserveSpaceForNavBar = false,
                    shadeMode = ShadeMode.Split,
                    onEmptySpaceClick =
                        viewModel::onEmptySpaceClicked.takeIf { isEmptySpaceClickable },
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .padding(
                                end =
                                    dimensionResource(R.dimen.notification_panel_margin_horizontal),
                                bottom = navBarBottomHeight,
                            )
                            .then(brightnessMirrorShowingModifier),
                )
            }
        }
        NotificationStackCutoffGuideline(
            stackScrollView = notificationStackScrollView,
            viewModel = notificationsPlaceholderViewModel,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun SceneScope.ShadeMediaCarousel(
    isVisible: Boolean,
    isInRow: Boolean,
    mediaHost: MediaHost,
    carouselController: MediaCarouselController,
    mediaOffsetProvider: ShadeMediaOffsetProvider,
    modifier: Modifier = Modifier,
) {
    MediaCarousel(
        modifier = modifier.fillMaxWidth(),
        isVisible = isVisible,
        mediaHost = mediaHost,
        carouselController = carouselController,
        offsetProvider =
            if (isInRow || MediaContentPicker.shouldElevateMedia(layoutState)) {
                null
            } else {
                { mediaOffsetProvider.offset }
            },
    )
}
