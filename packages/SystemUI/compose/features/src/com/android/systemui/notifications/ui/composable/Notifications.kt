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
 *
 */

package com.android.systemui.notifications.ui.composable

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.NestedScrollBehavior
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.height
import com.android.compose.ui.util.lerp
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.notifications.ui.composable.Notifications.Form
import com.android.systemui.notifications.ui.composable.Notifications.TransitionThresholds.EXPANSION_FOR_MAX_CORNER_RADIUS
import com.android.systemui.notifications.ui.composable.Notifications.TransitionThresholds.EXPANSION_FOR_MAX_SCRIM_ALPHA
import com.android.systemui.scene.ui.composable.Gone
import com.android.systemui.scene.ui.composable.Shade
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationStackAppearanceViewBinder.SCRIM_CORNER_RADIUS
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import kotlin.math.roundToInt

object Notifications {
    object Elements {
        val NotificationScrim = ElementKey("NotificationScrim")
        val NotificationPlaceholder = ElementKey("NotificationPlaceholder")
        val ShelfSpace = ElementKey("ShelfSpace")
    }

    // Expansion fraction thresholds (between 0-1f) at which the corresponding value should be
    // at its maximum, given they are at their minimum value at expansion = 0f.
    object TransitionThresholds {
        const val EXPANSION_FOR_MAX_CORNER_RADIUS = 0.1f
        const val EXPANSION_FOR_MAX_SCRIM_ALPHA = 0.3f
    }

    enum class Form {
        HunFromTop,
        Stack,
        HunFromBottom,
    }
}

/**
 * Adds the space where heads up notifications can appear in the scene. This should generally be the
 * entire size of the scene.
 */
@Composable
fun SceneScope.HeadsUpNotificationSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
    isPeekFromBottom: Boolean = false,
) {
    NotificationPlaceholder(
        viewModel = viewModel,
        form = if (isPeekFromBottom) Form.HunFromBottom else Form.HunFromTop,
        modifier = modifier,
    )
}

/** Adds the space where notification stack should appear in the scene. */
@Composable
fun SceneScope.NotificationStack(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    NotificationPlaceholder(
        viewModel = viewModel,
        form = Form.Stack,
        modifier = modifier,
    )
}

/**
 * Adds the space where notification stack should appear in the scene, with a scrim and nested
 * scrolling.
 */
@Composable
fun SceneScope.NotificationScrollingStack(
    viewModel: NotificationsPlaceholderViewModel,
    maxScrimTop: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val screenCornerRadius = LocalScreenCornerRadius.current
    val expansionFraction by viewModel.expandFraction.collectAsState(0f)

    val navBarHeight =
        with(density) { WindowInsets.systemBars.asPaddingValues().calculateBottomPadding().toPx() }
    val statusBarHeight = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val displayCutoutHeight = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val screenHeight =
        with(density) {
            (LocalConfiguration.current.screenHeightDp.dp +
                    maxOf(statusBarHeight, displayCutoutHeight))
                .toPx()
        } + navBarHeight

    val contentHeight = viewModel.intrinsicContentHeight.collectAsState()

    // the offset for the notifications scrim. Its upper bound is 0, and its lower bound is
    // calculated in minScrimOffset. The scrim is the same height as the screen minus the
    // height of the Shade Header, and at rest (scrimOffset = 0) its top bound is at maxScrimStartY.
    // When fully expanded (scrimOffset = minScrimOffset), its top bound is at minScrimStartY,
    // which is equal to the height of the Shade Header. Thus, when the scrim is fully expanded, the
    // entire height of the scrim is visible on screen.
    val scrimOffset = remember { mutableStateOf(0f) }

    val minScrimTop = with(density) { ShadeHeader.Dimensions.CollapsedHeight.toPx() }

    // The minimum offset for the scrim. The scrim is considered fully expanded when it
    // is at this offset.
    val minScrimOffset: () -> Float = { minScrimTop - maxScrimTop() }

    // The height of the scrim visible on screen when it is in its resting (collapsed) state.
    val minVisibleScrimHeight: () -> Float = { screenHeight - maxScrimTop() }

    // we are not scrolled to the top unless the scrim is at its maximum offset.
    LaunchedEffect(viewModel, scrimOffset) {
        snapshotFlow { scrimOffset.value >= 0f }
            .collect { isScrolledToTop -> viewModel.setScrolledToTop(isScrolledToTop) }
    }

    // if contentHeight drops below minimum visible scrim height while scrim is
    // expanded, reset scrim offset.
    LaunchedEffect(contentHeight, screenHeight, maxScrimTop, scrimOffset) {
        snapshotFlow { contentHeight.value < minVisibleScrimHeight() && scrimOffset.value < 0f }
            .collect { shouldCollapse -> if (shouldCollapse) scrimOffset.value = 0f }
    }

    Box(
        modifier =
            modifier
                .element(Notifications.Elements.NotificationScrim)
                .offset {
                    // if scrim is expanded while transitioning to Gone scene, increase the offset
                    // in step with the transition so that it is 0 when it completes.
                    if (
                        scrimOffset.value < 0 &&
                            layoutState.isTransitioning(from = Shade, to = Gone)
                    ) {
                        IntOffset(x = 0, y = (scrimOffset.value * expansionFraction).roundToInt())
                    } else {
                        IntOffset(x = 0, y = scrimOffset.value.roundToInt())
                    }
                }
                .graphicsLayer {
                    shape =
                        calculateCornerRadius(
                                screenCornerRadius,
                                { expansionFraction },
                                layoutState.isTransitioningBetween(Gone, Shade)
                            )
                            .let {
                                RoundedCornerShape(
                                    topStart = it,
                                    topEnd = it,
                                )
                            }
                    clip = true
                }
    ) {
        // Creates a cutout in the background scrim in the shape of the notifications scrim.
        // Only visible when notif scrim alpha < 1, during shade expansion.
        Spacer(
            modifier =
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Color.Black, blendMode = BlendMode.DstOut)
                }
        )
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha =
                            if (layoutState.isTransitioningBetween(Gone, Shade)) {
                                (expansionFraction / EXPANSION_FOR_MAX_SCRIM_ALPHA).coerceAtMost(1f)
                            } else 1f
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .debugBackground(viewModel, Color(0.5f, 0.5f, 0f, 0.2f))
        ) {
            NotificationPlaceholder(
                viewModel = viewModel,
                form = Form.Stack,
                modifier =
                    Modifier.verticalNestedScrollToScene(
                            topBehavior = NestedScrollBehavior.EdgeWithPreview,
                        )
                        .nestedScroll(
                            remember(
                                scrimOffset,
                                maxScrimTop,
                                minScrimTop,
                            ) {
                                NotificationScrimNestedScrollConnection(
                                    scrimOffset = { scrimOffset.value },
                                    onScrimOffsetChanged = { scrimOffset.value = it },
                                    minScrimOffset = minScrimOffset,
                                    maxScrimOffset = 0f,
                                    contentHeight = { contentHeight.value },
                                    minVisibleScrimHeight = minVisibleScrimHeight,
                                )
                            }
                        )
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .height { (contentHeight.value + navBarHeight).roundToInt() },
            )
        }
    }
}

/**
 * This may be added to the lockscreen to provide a space to the start of the lock icon where the
 * short shelf has room to flow vertically below the lock icon, but to its start, allowing more
 * notifications to fit in the stack itself. (see: b/213934746)
 *
 * NOTE: this is totally unused for now; it is here to clarify the future plan
 */
@Composable
fun SceneScope.NotificationShelfSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Shelf Space",
        modifier
            .element(key = Notifications.Elements.ShelfSpace)
            .fillMaxWidth()
            .onSizeChanged { size: IntSize ->
                debugLog(viewModel) { "SHELF onSizeChanged: size=$size" }
            }
            .onPlaced { coordinates: LayoutCoordinates ->
                debugLog(viewModel) {
                    ("SHELF onPlaced:" +
                        " size=${coordinates.size}" +
                        " position=${coordinates.positionInWindow()}" +
                        " bounds=${coordinates.boundsInWindow()}")
                }
            }
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun SceneScope.NotificationPlaceholder(
    viewModel: NotificationsPlaceholderViewModel,
    form: Form,
    modifier: Modifier = Modifier,
) {
    val elementKey = Notifications.Elements.NotificationPlaceholder
    Element(
        elementKey,
        modifier =
            modifier
                .debugBackground(viewModel)
                .onSizeChanged { size: IntSize ->
                    debugLog(viewModel) { "STACK onSizeChanged: size=$size" }
                }
                .onGloballyPositioned { coordinates: LayoutCoordinates ->
                    viewModel.onContentTopChanged(coordinates.positionInWindow().y)
                    debugLog(viewModel) {
                        "STACK onGloballyPositioned:" +
                            " size=${coordinates.size}" +
                            " position=${coordinates.positionInWindow()}" +
                            " bounds=${coordinates.boundsInWindow()}"
                    }
                    val boundsInWindow = coordinates.boundsInWindow()
                    viewModel.onBoundsChanged(
                        left = boundsInWindow.left,
                        top = boundsInWindow.top,
                        right = boundsInWindow.right,
                        bottom = boundsInWindow.bottom,
                    )
                }
    ) {
        content {
            if (viewModel.isPlaceholderTextVisible) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

private fun calculateCornerRadius(
    screenCornerRadius: Dp,
    expansionFraction: () -> Float,
    transitioning: Boolean,
): Dp {
    return if (transitioning) {
        lerp(
                start = screenCornerRadius.value,
                stop = SCRIM_CORNER_RADIUS,
                fraction = (expansionFraction() / EXPANSION_FOR_MAX_CORNER_RADIUS).coerceAtMost(1f),
            )
            .dp
    } else {
        SCRIM_CORNER_RADIUS.dp
    }
}

private inline fun debugLog(
    viewModel: NotificationsPlaceholderViewModel,
    msg: () -> Any,
) {
    if (viewModel.isDebugLoggingEnabled) {
        Log.d(TAG, msg().toString())
    }
}

private fun Modifier.debugBackground(
    viewModel: NotificationsPlaceholderViewModel,
    color: Color = DEBUG_COLOR,
): Modifier =
    if (viewModel.isVisualDebuggingEnabled) {
        background(color)
    } else {
        this
    }

private const val TAG = "FlexiNotifs"
private val DEBUG_COLOR = Color(1f, 0f, 0f, 0.2f)
