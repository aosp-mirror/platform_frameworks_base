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

package com.android.systemui.qs.ui.composable

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateSceneDpAsState
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.modifiers.thenIf
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.common.ui.compose.windowinsets.CutoutLocation
import com.android.systemui.common.ui.compose.windowinsets.LocalDisplayCutout
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.controls.ui.composable.isLandscape
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.notifications.ui.composable.HeadsUpNotificationSpace
import com.android.systemui.notifications.ui.composable.NotificationScrollingStack
import com.android.systemui.notifications.ui.composable.NotificationStackCutoffGuideline
import com.android.systemui.qs.footer.ui.compose.FooterActionsWithAnimatedVisibility
import com.android.systemui.qs.ui.composable.QuickSettings.SharedValues.MediaLandscapeTopOffset
import com.android.systemui.qs.ui.composable.QuickSettings.SharedValues.MediaOffset.InQS
import com.android.systemui.qs.ui.viewmodel.QuickSettingsSceneContentViewModel
import com.android.systemui.qs.ui.viewmodel.QuickSettingsUserActionsViewModel
import com.android.systemui.res.R
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.shade.ui.composable.CollapsedShadeHeader
import com.android.systemui.shade.ui.composable.ExpandedShadeHeader
import com.android.systemui.shade.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

/** The Quick Settings (AKA "QS") scene shows the quick setting tiles. */
@SysUISingleton
class QuickSettingsScene
@Inject
constructor(
    private val shadeSession: SaveableSession,
    private val notificationStackScrollView: Lazy<NotificationScrollView>,
    private val notificationsPlaceholderViewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val actionsViewModelFactory: QuickSettingsUserActionsViewModel.Factory,
    private val contentViewModelFactory: QuickSettingsSceneContentViewModel.Factory,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
    private val mediaCarouselController: MediaCarouselController,
    @Named(MediaModule.QS_PANEL) private val mediaHost: MediaHost,
) : ExclusiveActivatable(), Scene {
    override val key = Scenes.QuickSettings

    private val actionsViewModel: QuickSettingsUserActionsViewModel by lazy {
        actionsViewModelFactory.create()
    }

    override val userActions: Flow<Map<UserAction, UserActionResult>> = actionsViewModel.actions

    override suspend fun onActivated(): Nothing {
        actionsViewModel.activate()
    }

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        QuickSettingsScene(
            notificationStackScrollView = notificationStackScrollView.get(),
            viewModelFactory = contentViewModelFactory,
            notificationsPlaceholderViewModel =
                rememberViewModel("QuickSettingsScene-notifPlaceholderViewModel") {
                    notificationsPlaceholderViewModelFactory.create()
                },
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            mediaCarouselController = mediaCarouselController,
            mediaHost = mediaHost,
            modifier = modifier,
            shadeSession = shadeSession,
        )
    }
}

@Composable
private fun SceneScope.QuickSettingsScene(
    notificationStackScrollView: NotificationScrollView,
    viewModelFactory: QuickSettingsSceneContentViewModel.Factory,
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

    val viewModel = rememberViewModel("QuickSettingsScene-viewModel") { viewModelFactory.create() }
    val brightnessMirrorViewModel =
        rememberViewModel("QuickSettingsScene-brightnessMirrorViewModel") {
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

    val shadeHorizontalPadding =
        dimensionResource(id = R.dimen.notification_panel_margin_horizontal)

    BrightnessMirror(
        viewModel = brightnessMirrorViewModel,
        qsSceneAdapter = viewModel.qsSceneAdapter,
        modifier =
            Modifier.thenIf(cutoutLocation != CutoutLocation.CENTER) {
                    Modifier.displayCutoutPadding()
                }
                .padding(horizontal = shadeHorizontalPadding),
    )

    val shouldPunchHoleBehindScrim =
        layoutState.isTransitioningBetween(Scenes.Gone, Scenes.QuickSettings) ||
            layoutState.isTransitioningBetween(Scenes.Lockscreen, Scenes.QuickSettings)

    // TODO(b/280887232): implement the real UI.
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                .thenIf(shouldPunchHoleBehindScrim) {
                    // Render the scene to an offscreen buffer so that BlendMode.DstOut only clears
                    // this
                    // scene (and not the one under it) during a scene transition.
                    Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                }
                .thenIf(cutoutLocation != CutoutLocation.CENTER) { Modifier.displayCutoutPadding() }
    ) {
        val density = LocalDensity.current
        val isCustomizing by viewModel.qsSceneAdapter.isCustomizing.collectAsStateWithLifecycle()
        val isCustomizerShowing by
            viewModel.qsSceneAdapter.isCustomizerShowing.collectAsStateWithLifecycle()
        val customizingAnimationDuration by
            viewModel.qsSceneAdapter.customizerAnimationDuration.collectAsStateWithLifecycle()
        val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

        BackHandler(enabled = isCustomizing) { viewModel.qsSceneAdapter.requestCloseCustomizer() }

        val lifecycleOwner = LocalLifecycleOwner.current
        val footerActionsViewModel =
            remember(lifecycleOwner, viewModel) {
                viewModel.getFooterActionsViewModel(lifecycleOwner)
            }
        animateSceneFloatAsState(value = 1f, key = QuickSettings.SharedValues.TilesSquishiness)

        // ############## SCROLLING ################

        val scrollState = rememberScrollState()
        // When animating into the scene, we don't want it to be able to scroll, as it could mess
        // up with the expansion animation.
        val isScrollable =
            when (val state = layoutState.transitionState) {
                is TransitionState.Idle -> true
                is TransitionState.Transition -> state.fromContent == Scenes.QuickSettings
            }

        LaunchedEffect(isCustomizing, scrollState) {
            if (isCustomizing) {
                scrollState.scrollTo(0)
            }
        }

        // ############# NAV BAR paddings ###############

        val navBarBottomHeight =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomPadding by
            animateDpAsState(
                targetValue = if (isCustomizing) 0.dp else navBarBottomHeight,
                animationSpec = tween(customizingAnimationDuration),
                label = "animateQSSceneBottomPaddingAsState",
            )
        val topPadding by
            animateDpAsState(
                targetValue = if (isCustomizing) ShadeHeader.Dimensions.CollapsedHeight else 0.dp,
                animationSpec = tween(customizingAnimationDuration),
                label = "animateQSSceneTopPaddingAsState",
            )

        LaunchedEffect(navBarBottomHeight, density) {
            with(density) {
                viewModel.qsSceneAdapter.applyBottomNavBarPadding(navBarBottomHeight.roundToPx())
            }
        }

        // ############# Media ###############
        val isMediaVisible by viewModel.isMediaVisible.collectAsStateWithLifecycle()
        val mediaInRow = isMediaVisible && isLandscape()
        val mediaOffset by
            animateSceneDpAsState(value = InQS, key = MediaLandscapeTopOffset, canOverflow = false)

        // This is the background for the whole scene, as the elements don't necessarily provide
        // a background that extends to the edges.
        Spacer(
            modifier =
                Modifier.element(Shade.Elements.BackgroundScrim)
                    .fillMaxSize()
                    .background(colorResource(R.color.shade_scrim_background_dark))
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        top = topPadding.coerceAtLeast(0.dp),
                        bottom = bottomPadding.coerceAtLeast(0.dp),
                    ),
        ) {
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                val shadeHeaderAndQuickSettingsModifier =
                    if (isCustomizerShowing) {
                        Modifier.fillMaxHeight().align(Alignment.TopCenter)
                    } else {
                        Modifier.verticalScroll(scrollState, enabled = isScrollable)
                            .clipScrollableContainer(Orientation.Horizontal)
                            .fillMaxWidth()
                            .wrapContentHeight(unbounded = true)
                            .align(Alignment.TopCenter)
                    }

                Column(
                    modifier =
                        shadeHeaderAndQuickSettingsModifier.sysuiResTag("expanded_qs_scroll_view")
                ) {
                    when (LocalWindowSizeClass.current.widthSizeClass) {
                        WindowWidthSizeClass.Compact ->
                            AnimatedVisibility(
                                visible = !isCustomizing,
                                enter =
                                    expandVertically(
                                        animationSpec = tween(customizingAnimationDuration),
                                        expandFrom = Alignment.Top,
                                    ) +
                                        slideInVertically(
                                            animationSpec = tween(customizingAnimationDuration)
                                        ) +
                                        fadeIn(tween(customizingAnimationDuration)),
                                exit =
                                    shrinkVertically(
                                        animationSpec = tween(customizingAnimationDuration),
                                        shrinkTowards = Alignment.Top,
                                    ) +
                                        slideOutVertically(
                                            animationSpec = tween(customizingAnimationDuration)
                                        ) +
                                        fadeOut(tween(customizingAnimationDuration)),
                            ) {
                                ExpandedShadeHeader(
                                    viewModelFactory = viewModel.shadeHeaderViewModelFactory,
                                    createTintedIconManager = createTintedIconManager,
                                    createBatteryMeterViewController =
                                        createBatteryMeterViewController,
                                    statusBarIconController = statusBarIconController,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        else ->
                            CollapsedShadeHeader(
                                viewModelFactory = viewModel.shadeHeaderViewModelFactory,
                                createTintedIconManager = createTintedIconManager,
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                statusBarIconController = statusBarIconController,
                            )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // This view has its own horizontal padding
                    val content: @Composable () -> Unit = {
                        QuickSettings(
                            viewModel.qsSceneAdapter,
                            { viewModel.qsSceneAdapter.qsHeight },
                            isSplitShade = false,
                            modifier = Modifier.layoutId(QSMediaMeasurePolicy.LayoutId.QS),
                        )

                        MediaCarousel(
                            isVisible = isMediaVisible,
                            mediaHost = mediaHost,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .layoutId(QSMediaMeasurePolicy.LayoutId.Media),
                            carouselController = mediaCarouselController,
                        )
                    }
                    val landscapeQsMediaMeasurePolicy = remember {
                        QSMediaMeasurePolicy(
                            { viewModel.qsSceneAdapter.qsHeight },
                            { mediaOffset.roundToPx() },
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = shadeHorizontalPadding)) {
                        if (mediaInRow) {
                            Layout(content = content, measurePolicy = landscapeQsMediaMeasurePolicy)
                        } else {
                            content()
                        }
                    }
                }
            }

            FooterActionsWithAnimatedVisibility(
                viewModel = footerActionsViewModel,
                isCustomizing = isCustomizing,
                customizingAnimationDuration = customizingAnimationDuration,
                lifecycleOwner = lifecycleOwner,
                modifier =
                    Modifier.align(Alignment.CenterHorizontally)
                        .sysuiResTag("qs_footer_actions")
                        .padding(horizontal = shadeHorizontalPadding),
            )
        }
        HeadsUpNotificationSpace(
            stackScrollView = notificationStackScrollView,
            viewModel = notificationsPlaceholderViewModel,
            useHunBounds = { shouldUseQuickSettingsHunBounds(layoutState.transitionState) },
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = shadeHorizontalPadding),
        )

        // The minimum possible value for the top of the notification stack. In other words: how
        // high is the notification stack allowed to get when the scene is at rest. It may still be
        // translated farther upwards by a transition animation but, at rest, the top edge of its
        // bounds must be limited to be at or below this value.
        //
        // A 1 pixel is added to compensate for any kind of rounding errors to make sure 100% that
        // the notification stack is entirely "below" the entire screen.
        val minNotificationStackTop = screenHeight.roundToInt() + 1
        NotificationScrollingStack(
            shadeSession = shadeSession,
            stackScrollView = notificationStackScrollView,
            viewModel = notificationsPlaceholderViewModel,
            maxScrimTop = { minNotificationStackTop.toFloat() },
            shouldPunchHoleBehindScrim = shouldPunchHoleBehindScrim,
            shouldIncludeHeadsUpSpace = false,
            supportNestedScrolling = true,
            modifier =
                Modifier.fillMaxWidth()
                    .offset { IntOffset(x = 0, y = minNotificationStackTop) }
                    .padding(horizontal = shadeHorizontalPadding),
        )
        NotificationStackCutoffGuideline(
            stackScrollView = notificationStackScrollView,
            viewModel = notificationsPlaceholderViewModel,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .offset { IntOffset(x = 0, y = minNotificationStackTop) }
                    .padding(horizontal = shadeHorizontalPadding),
        )
    }
}

private fun shouldUseQuickSettingsHunBounds(state: TransitionState): Boolean {
    return state is TransitionState.Idle && state.currentScene == Scenes.QuickSettings
}
