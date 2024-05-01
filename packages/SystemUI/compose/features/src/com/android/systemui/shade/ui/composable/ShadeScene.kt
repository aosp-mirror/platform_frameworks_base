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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.LowestZIndexScenePicker
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.TransitionState
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.animateSceneFloatAsState
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.systemui.battery.BatteryMeterViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.controls.ui.composable.MediaCarousel
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL
import com.android.systemui.notifications.ui.composable.NotificationScrollingStack
import com.android.systemui.qs.footer.ui.compose.FooterActionsWithAnimatedVisibility
import com.android.systemui.qs.ui.composable.BrightnessMirror
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.res.R
import com.android.systemui.scene.session.ui.composable.SaveableSession
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.ui.composable.ComposableScene
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.TintedIconManager
import javax.inject.Inject
import javax.inject.Named
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

object Shade {
    object Elements {
        val MediaCarousel = ElementKey("ShadeMediaCarousel")
        val BackgroundScrim =
            ElementKey("ShadeBackgroundScrim", scenePicker = LowestZIndexScenePicker)
    }

    object Dimensions {
        val ScrimCornerSize = 32.dp
        val HorizontalPadding = 16.dp
        const val ScrimOverscrollLimit = 100f
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
@SysUISingleton
class ShadeScene
@Inject
constructor(
    private val shadeSession: SaveableSession,
    private val viewModel: ShadeSceneViewModel,
    private val tintedIconManagerFactory: TintedIconManager.Factory,
    private val batteryMeterViewControllerFactory: BatteryMeterViewController.Factory,
    private val statusBarIconController: StatusBarIconController,
    private val mediaCarouselController: MediaCarouselController,
    @Named(QUICK_QS_PANEL) private val mediaHost: MediaHost,
) : ComposableScene {

    override val key = Scenes.Shade

    override val destinationScenes: StateFlow<Map<UserAction, UserActionResult>> =
        viewModel.destinationScenes

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) =
        ShadeScene(
            viewModel = viewModel,
            createTintedIconManager = tintedIconManagerFactory::create,
            createBatteryMeterViewController = batteryMeterViewControllerFactory::create,
            statusBarIconController = statusBarIconController,
            mediaCarouselController = mediaCarouselController,
            mediaHost = mediaHost,
            modifier = modifier,
            shadeSession = shadeSession,
        )

    init {
        mediaHost.expansion = MediaHostState.EXPANDED
        mediaHost.showsOnlyActiveMedia = true
        mediaHost.init(MediaHierarchyManager.LOCATION_QQS)
    }
}

@Composable
private fun SceneScope.ShadeScene(
    viewModel: ShadeSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val shadeMode by viewModel.shadeMode.collectAsState()
    when (shadeMode) {
        is ShadeMode.Single ->
            SingleShade(
                viewModel = viewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                mediaCarouselController = mediaCarouselController,
                mediaHost = mediaHost,
                modifier = modifier,
                shadeSession = shadeSession,
            )
        is ShadeMode.Split ->
            SplitShade(
                viewModel = viewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                mediaCarouselController = mediaCarouselController,
                mediaHost = mediaHost,
                modifier = modifier,
                shadeSession = shadeSession,
            )
        is ShadeMode.Dual -> error("Dual shade is not yet implemented!")
    }
}

@Composable
private fun SceneScope.SingleShade(
    viewModel: ShadeSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val maxNotifScrimTop = remember { mutableStateOf(0f) }
    val tileSquishiness by
        animateSceneFloatAsState(
            value = 1f,
            key = QuickSettings.SharedValues.TilesSquishiness,
            canOverflow = false
        )
    val isClickable by viewModel.isClickable.collectAsState()
    val isMediaVisible by viewModel.isMediaVisible.collectAsState()

    val shouldPunchHoleBehindScrim =
        layoutState.isTransitioningBetween(Scenes.Gone, Scenes.Shade) ||
            layoutState.isTransitioningBetween(Scenes.Lockscreen, Scenes.Shade)

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
                    .background(colorResource(R.color.shade_scrim_background_dark)),
        )
        Layout(
            contents =
                listOf(
                    {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier =
                                Modifier.fillMaxWidth().thenIf(isClickable) {
                                    Modifier.clickable(onClick = { viewModel.onContentClicked() })
                                }
                        ) {
                            CollapsedShadeHeader(
                                viewModel = viewModel.shadeHeaderViewModel,
                                createTintedIconManager = createTintedIconManager,
                                createBatteryMeterViewController = createBatteryMeterViewController,
                                statusBarIconController = statusBarIconController,
                                modifier =
                                    Modifier.padding(
                                        horizontal = Shade.Dimensions.HorizontalPadding
                                    )
                            )
                            Box(Modifier.element(QuickSettings.Elements.QuickQuickSettings)) {
                                QuickSettings(
                                    viewModel.qsSceneAdapter,
                                    { viewModel.qsSceneAdapter.qqsHeight },
                                    isSplitShade = false,
                                    squishiness = tileSquishiness,
                                )
                            }

                            MediaCarousel(
                                isVisible = isMediaVisible,
                                mediaHost = mediaHost,
                                modifier = Modifier.fillMaxWidth(),
                                carouselController = mediaCarouselController,
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    },
                    {
                        NotificationScrollingStack(
                            shadeSession = shadeSession,
                            viewModel = viewModel.notifications,
                            maxScrimTop = { maxNotifScrimTop.value },
                            shouldPunchHoleBehindScrim = shouldPunchHoleBehindScrim,
                        )
                    },
                )
        ) { measurables, constraints ->
            check(measurables.size == 2)
            check(measurables[0].size == 1)
            check(measurables[1].size == 1)

            val quickSettingsPlaceable = measurables[0][0].measure(constraints)
            val notificationsPlaceable = measurables[1][0].measure(constraints)

            maxNotifScrimTop.value = quickSettingsPlaceable.height.toFloat()

            layout(constraints.maxWidth, constraints.maxHeight) {
                quickSettingsPlaceable.placeRelative(x = 0, y = 0)
                notificationsPlaceable.placeRelative(x = 0, y = maxNotifScrimTop.value.roundToInt())
            }
        }
    }
}

@Composable
private fun SceneScope.SplitShade(
    viewModel: ShadeSceneViewModel,
    createTintedIconManager: (ViewGroup, StatusBarLocation) -> TintedIconManager,
    createBatteryMeterViewController: (ViewGroup, StatusBarLocation) -> BatteryMeterViewController,
    statusBarIconController: StatusBarIconController,
    mediaCarouselController: MediaCarouselController,
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    shadeSession: SaveableSession,
) {
    val isCustomizing by viewModel.qsSceneAdapter.isCustomizing.collectAsState()
    val isCustomizerShowing by viewModel.qsSceneAdapter.isCustomizerShowing.collectAsState()
    val customizingAnimationDuration by
        viewModel.qsSceneAdapter.customizerAnimationDuration.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val footerActionsViewModel =
        remember(lifecycleOwner, viewModel) { viewModel.getFooterActionsViewModel(lifecycleOwner) }
    val tileSquishiness by
        animateSceneFloatAsState(value = 1f, key = QuickSettings.SharedValues.TilesSquishiness)
    val unfoldTranslationXForStartSide by
        viewModel
            .unfoldTranslationX(
                isOnStartSide = true,
            )
            .collectAsState(0f)
    val unfoldTranslationXForEndSide by
        viewModel
            .unfoldTranslationX(
                isOnStartSide = false,
            )
            .collectAsState(0f)

    val navBarBottomHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding by
        animateDpAsState(
            targetValue = if (isCustomizing) 0.dp else navBarBottomHeight,
            animationSpec = tween(customizingAnimationDuration),
            label = "animateQSSceneBottomPaddingAsState"
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

    val brightnessMirrorShowing by viewModel.brightnessMirrorViewModel.isShowing.collectAsState()
    val contentAlpha by
        animateFloatAsState(
            targetValue = if (brightnessMirrorShowing) 0f else 1f,
            label = "alphaAnimationBrightnessMirrorContentHiding",
        )

    viewModel.notifications.setAlphaForBrightnessMirror(contentAlpha)
    DisposableEffect(Unit) { onDispose { viewModel.notifications.setAlphaForBrightnessMirror(1f) } }

    val isMediaVisible by viewModel.isMediaVisible.collectAsState()

    val brightnessMirrorShowingModifier = Modifier.graphicsLayer { alpha = contentAlpha }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .element(Shade.Elements.BackgroundScrim)
                // Cannot set the alpha of the whole element to 0, because the mirror should be
                // in the QS column.
                .background(
                    colorResource(R.color.shade_scrim_background_dark).copy(alpha = contentAlpha)
                )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            CollapsedShadeHeader(
                viewModel = viewModel.shadeHeaderViewModel,
                createTintedIconManager = createTintedIconManager,
                createBatteryMeterViewController = createBatteryMeterViewController,
                statusBarIconController = statusBarIconController,
                modifier =
                    Modifier.padding(horizontal = Shade.Dimensions.HorizontalPadding)
                        .then(brightnessMirrorShowingModifier)
                        .padding(
                            horizontal = { unfoldTranslationXForStartSide.roundToInt() },
                        )
            )

            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier =
                        Modifier.weight(1f).graphicsLayer {
                            translationX = unfoldTranslationXForStartSide
                        },
                ) {
                    BrightnessMirror(
                        viewModel = viewModel.brightnessMirrorViewModel,
                        qsSceneAdapter = viewModel.qsSceneAdapter,
                        // Need to remove the offset of the header height, as the mirror uses
                        // the position of the Brightness slider in the window
                        modifier = Modifier.offset(y = -ShadeHeader.Dimensions.CollapsedHeight)
                    )
                    Column(
                        verticalArrangement = Arrangement.Top,
                        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
                    ) {
                        Column(
                            modifier =
                                Modifier.fillMaxSize()
                                    .weight(1f)
                                    .thenIf(!isCustomizerShowing) {
                                        Modifier.verticalNestedScrollToScene()
                                            .verticalScroll(
                                                quickSettingsScrollState,
                                                enabled = isScrollable
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
                                    squishiness = tileSquishiness,
                                )
                            }

                            MediaCarousel(
                                isVisible = isMediaVisible,
                                mediaHost = mediaHost,
                                modifier = Modifier.fillMaxWidth(),
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
                                    .then(brightnessMirrorShowingModifier),
                        )
                    }
                }

                NotificationScrollingStack(
                    shadeSession = shadeSession,
                    viewModel = viewModel.notifications,
                    maxScrimTop = { 0f },
                    shouldPunchHoleBehindScrim = false,
                    modifier =
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .padding(bottom = navBarBottomHeight)
                            .then(brightnessMirrorShowingModifier)
                )
            }
        }
    }
}
