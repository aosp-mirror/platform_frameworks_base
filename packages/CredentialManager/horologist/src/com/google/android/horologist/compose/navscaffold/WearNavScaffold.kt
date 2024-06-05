/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalWearFoundationApi::class)

package com.google.android.horologist.compose.navscaffold

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.SwipeDismissableNavHostState
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.google.android.horologist.compose.layout.ScalingLazyColumnDefaults
import com.google.android.horologist.compose.layout.ScalingLazyColumnState
import com.google.android.horologist.compose.layout.scrollAway

/**
 * A Navigation and Scroll aware [Scaffold].
 *
 * In addition to [NavGraphBuilder.scrollable], 3 additional extensions are supported
 * [scalingLazyColumnComposable], [scrollStateComposable] and
 * [lazyListComposable].
 *
 * These should be used to build the [ScrollableState] or [FocusRequester] as well as
 * configure the behaviour of [TimeText], [PositionIndicator] or [Vignette].
 */
@Composable
public fun WearNavScaffold(
        startDestination: String,
        navController: NavHostController,
        modifier: Modifier = Modifier,
        snackbar: @Composable () -> Unit = {},
        timeText: @Composable (Modifier) -> Unit = {
            TimeText(
                    modifier = it,
            )
        },
        state: SwipeDismissableNavHostState = rememberSwipeDismissableNavHostState(),
        builder: NavGraphBuilder.() -> Unit,
) {
    val currentBackStackEntry: NavBackStackEntry? by navController.currentBackStackEntryAsState()

    val viewModel: NavScaffoldViewModel? = currentBackStackEntry?.let {
        viewModel(viewModelStoreOwner = it)
    }

    val scrollState: State<ScrollableState?> = remember(viewModel) {
        derivedStateOf {
            viewModel?.timeTextScrollableState()
        }
    }

    Scaffold(
            modifier = modifier.fillMaxSize(),
            timeText = {
                timeText(Modifier.scrollAway(scrollState))
            },
            positionIndicator = {
                key(currentBackStackEntry?.destination?.route) {
                    val mode = viewModel?.positionIndicatorMode

                    if (mode == NavScaffoldViewModel.PositionIndicatorMode.On) {
                        NavPositionIndicator(viewModel)
                    }
                }
            },
            vignette = {
                key(currentBackStackEntry?.destination?.route) {
                    val vignettePosition = viewModel?.vignettePosition
                    if (vignettePosition is NavScaffoldViewModel.VignetteMode.On) {
                        Vignette(vignettePosition = vignettePosition.position)
                    }
                }
            },
    ) {
        Box {
            SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    state = state,
            ) {
                builder()
            }

            snackbar()
        }
    }
}

@Composable
private fun NavPositionIndicator(viewModel: NavScaffoldViewModel) {
    when (viewModel.scrollType) {
        NavScaffoldViewModel.ScrollType.ScrollState ->
            PositionIndicator(
                    scrollState = viewModel.scrollableState as ScrollState,
            )

        NavScaffoldViewModel.ScrollType.ScalingLazyColumn -> {
            PositionIndicator(
                    scalingLazyListState = viewModel.scrollableState as ScalingLazyListState,
            )
        }

        NavScaffoldViewModel.ScrollType.LazyList ->
            PositionIndicator(
                    lazyListState = viewModel.scrollableState as LazyListState,
            )

        else -> {}
    }
}

/**
 * Add a screen to the navigation graph featuring a ScalingLazyColumn.
 *
 * The scalingLazyListState must be taken from the [ScaffoldContext].
 */
@Deprecated(
        "Use listComposable",
)
public fun NavGraphBuilder.scalingLazyColumnComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        scrollStateBuilder: () -> ScalingLazyListState,
        content: @Composable (ScaffoldContext<ScalingLazyListState>) -> Unit,
) {
    composable(route, arguments, deepLinks) {
        FocusedDestination {
            val viewModel: NavScaffoldViewModel = viewModel(it)

            val scrollState = viewModel.initializeScalingLazyListState(scrollStateBuilder)

            content(ScaffoldContext(it, scrollState, viewModel))
        }
    }
}

/**
 * Add a screen to the navigation graph featuring a ScalingLazyColumn.
 *
 * The [ScalingLazyColumnState] must be taken from the [ScrollableScaffoldContext].
 */
@ExperimentalHorologistApi
public fun NavGraphBuilder.scrollable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        columnStateFactory: ScalingLazyColumnState.Factory =
                ScalingLazyColumnDefaults.belowTimeText(),
        content: @Composable (ScrollableScaffoldContext) -> Unit,
) {
    this@scrollable.composable(route, arguments, deepLinks) {
        FocusedDestination {
            val columnState = columnStateFactory.create()

            val viewModel: NavScaffoldViewModel = viewModel(it)

            viewModel.initializeScalingLazyListState(columnState)

            content(ScrollableScaffoldContext(it, columnState, viewModel))
        }
    }
}

/**
 * Add a screen to the navigation graph featuring a Scrollable item.
 *
 * The scrollState must be taken from the [ScaffoldContext].
 */
public fun NavGraphBuilder.scrollStateComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        scrollStateBuilder: () -> ScrollState = { ScrollState(0) },
        content: @Composable (ScaffoldContext<ScrollState>) -> Unit,
) {
    composable(route, arguments, deepLinks) {
        FocusedDestination {
            val viewModel: NavScaffoldViewModel = viewModel(it)

            val scrollState = viewModel.initializeScrollState(scrollStateBuilder)

            content(ScaffoldContext(it, scrollState, viewModel))
        }
    }
}

/**
 * Add a screen to the navigation graph featuring a Lazy list such as LazyColumn.
 *
 * The scrollState must be taken from the [ScaffoldContext].
 */
public fun NavGraphBuilder.lazyListComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        lazyListStateBuilder: () -> LazyListState = { LazyListState() },
        content: @Composable (ScaffoldContext<LazyListState>) -> Unit,
) {
    composable(route, arguments, deepLinks) {
        FocusedDestination {
            val viewModel: NavScaffoldViewModel = viewModel(it)

            val scrollState = viewModel.initializeLazyList(lazyListStateBuilder)

            content(ScaffoldContext(it, scrollState, viewModel))
        }
    }
}

/**
 * Add non scrolling screen to the navigation graph. The [NavBackStackEntry] and
 * [NavScaffoldViewModel] are passed into the [content] block so that
 * the Scaffold may be customised, such as disabling TimeText.
 */
@Deprecated(
        "Use composable",
        ReplaceWith("composable(route, arguments, deepLinks, lazyListStateBuilder, content)"),
)
public fun NavGraphBuilder.wearNavComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        content: @Composable (NavBackStackEntry, NavScaffoldViewModel) -> Unit,
) {
    composable(route, arguments, deepLinks) {
        FocusedDestination {
            val viewModel: NavScaffoldViewModel = viewModel()

            content(it, viewModel)
        }
    }
}

/**
 * Add non scrolling screen to the navigation graph. The [NavBackStackEntry] and
 * [NavScaffoldViewModel] are passed into the [content] block so that
 * the Scaffold may be customised, such as disabling TimeText.
 */
@ExperimentalHorologistApi
public fun NavGraphBuilder.composable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        deepLinks: List<NavDeepLink> = emptyList(),
        content: @Composable (NonScrollableScaffoldContext) -> Unit,
) {
    this@composable.composable(route, arguments, deepLinks) {
        FocusedDestination {
            val viewModel: NavScaffoldViewModel = viewModel()

            content(NonScrollableScaffoldContext(it, viewModel))
        }
    }
}

@Composable
internal fun FocusedDestination(content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focused =
            remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    DisposableEffect(lifecycle) {
        val listener = LifecycleEventObserver { _, _ ->
            focused.value = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(listener)
        onDispose {
            lifecycle.removeObserver(listener)
        }
    }

    HierarchicalFocusCoordinator(requiresFocus = { focused.value }) {
        content()
    }
}
