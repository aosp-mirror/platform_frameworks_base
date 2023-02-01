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

package com.android.settingslib.spa.framework.compose

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.navigation.compose.DialogHost
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.createGraph
import androidx.navigation.get
import kotlinx.coroutines.flow.map

/**
 * Provides in place in the Compose hierarchy for self contained navigation to occur.
 *
 * Once this is called, any Composable within the given [NavGraphBuilder] can be navigated to from
 * the provided [navController].
 *
 * The builder passed into this method is [remember]ed. This means that for this NavHost, the
 * contents of the builder cannot be changed.
 *
 * @param navController the navController for this host
 * @param startDestination the route for the start destination
 * @param modifier The modifier to be applied to the layout.
 * @param route the route for the graph
 * @param enterTransition callback to define enter transitions for destination in this host
 * @param exitTransition callback to define exit transitions for destination in this host
 * @param popEnterTransition callback to define popEnter transitions for destination in this host
 * @param popExitTransition callback to define popExit transitions for destination in this host
 * @param builder the builder used to construct the graph
 */
@Composable
@ExperimentalAnimationApi
public fun AnimatedNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    route: String? = null,
    enterTransition: (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition) =
        { fadeIn(animationSpec = tween(700)) },
    exitTransition: (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition) =
        { fadeOut(animationSpec = tween(700)) },
    popEnterTransition: (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition) =
        enterTransition,
    popExitTransition: (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition) =
        exitTransition,
    builder: NavGraphBuilder.() -> Unit
) {
    AnimatedNavHost(
        navController,
        remember(route, startDestination, builder) {
            navController.createGraph(startDestination, route, builder)
        },
        modifier,
        contentAlignment,
        enterTransition,
        exitTransition,
        popEnterTransition,
        popExitTransition
    )
}

/**
 * Provides in place in the Compose hierarchy for self contained navigation to occur.
 *
 * Once this is called, any Composable within the given [NavGraphBuilder] can be navigated to from
 * the provided [navController].
 *
 * @param navController the navController for this host
 * @param graph the graph for this host
 * @param modifier The modifier to be applied to the layout.
 * @param enterTransition callback to define enter transitions for destination in this host
 * @param exitTransition callback to define exit transitions for destination in this host
 * @param popEnterTransition callback to define popEnter transitions for destination in this host
 * @param popExitTransition callback to define popExit transitions for destination in this host
 */
@ExperimentalAnimationApi
@Composable
public fun AnimatedNavHost(
    navController: NavHostController,
    graph: NavGraph,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    enterTransition: (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition) =
        { fadeIn(animationSpec = tween(700)) },
    exitTransition: (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition) =
        { fadeOut(animationSpec = tween(700)) },
    popEnterTransition: (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition) =
        enterTransition,
    popExitTransition: (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition) =
        exitTransition,
) {

    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "NavHost requires a ViewModelStoreOwner to be provided via LocalViewModelStoreOwner"
    }
    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val onBackPressedDispatcher = onBackPressedDispatcherOwner?.onBackPressedDispatcher

    // on successful recompose we setup the navController with proper inputs
    // after the first time, this will only happen again if one of the inputs changes
    navController.setLifecycleOwner(lifecycleOwner)
    navController.setViewModelStore(viewModelStoreOwner.viewModelStore)
    if (onBackPressedDispatcher != null) {
        navController.setOnBackPressedDispatcher(onBackPressedDispatcher)
    }

    navController.graph = graph

    val saveableStateHolder = rememberSaveableStateHolder()

    // Find the ComposeNavigator, returning early if it isn't found
    // (such as is the case when using TestNavHostController)
    val composeNavigator = navController.navigatorProvider.get<Navigator<out NavDestination>>(
        AnimatedComposeNavigator.NAME
    ) as? AnimatedComposeNavigator ?: return
    val visibleEntries by remember(navController.visibleEntries) {
        navController.visibleEntries.map {
            it.filter { entry ->
                entry.destination.navigatorName == AnimatedComposeNavigator.NAME
            }
        }
    }.collectAsState(emptyList())

    val backStackEntry = visibleEntries.lastOrNull()

    if (backStackEntry != null) {
        val finalEnter: AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition = {
            val targetDestination = targetState.destination as AnimatedComposeNavigator.Destination

            if (composeNavigator.isPop.value) {
                targetDestination.hierarchy.firstNotNullOfOrNull { destination ->
                    popEnterTransitions[destination.route]?.invoke(this)
                } ?: popEnterTransition.invoke(this)
            } else {
                targetDestination.hierarchy.firstNotNullOfOrNull { destination ->
                    enterTransitions[destination.route]?.invoke(this)
                } ?: enterTransition.invoke(this)
            }
        }

        val finalExit: AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition = {
            val initialDestination =
                initialState.destination as AnimatedComposeNavigator.Destination

            if (composeNavigator.isPop.value) {
                initialDestination.hierarchy.firstNotNullOfOrNull { destination ->
                    popExitTransitions[destination.route]?.invoke(this)
                } ?: popExitTransition.invoke(this)
            } else {
                initialDestination.hierarchy.firstNotNullOfOrNull { destination ->
                    exitTransitions[destination.route]?.invoke(this)
                } ?: exitTransition.invoke(this)
            }
        }

        val transition = updateTransition(backStackEntry, label = "entry")
        transition.AnimatedContent(
            modifier,
            transitionSpec = {
                val zIndex = if (composeNavigator.isPop.value) {
                    visibleEntries.indexOf(initialState).toFloat()
                } else {
                    visibleEntries.indexOf(targetState).toFloat()
                }
                // If the initialState of the AnimatedContent is not in visibleEntries, we are in
                // a case where visible has cleared the old state for some reason, so instead of
                // attempting to animate away from the initialState, we skip the animation.
                if (initialState in visibleEntries) {
                    ContentTransform(finalEnter(this), finalExit(this), zIndex)
                } else {
                    EnterTransition.None with ExitTransition.None
                }
            },
            contentAlignment,
            contentKey = { it.id }
        ) {
            // In some specific cases, such as clearing your back stack by changing your
            // start destination, AnimatedContent can contain an entry that is no longer
            // part of visible entries since it was cleared from the back stack and is not
            // animating. In these cases the currentEntry will be null, and in those cases,
            // AnimatedContent will just skip attempting to transition the old entry.
            // See https://issuetracker.google.com/238686802
            val currentEntry = visibleEntries.lastOrNull { entry ->
                it == entry
            }
            // while in the scope of the composable, we provide the navBackStackEntry as the
            // ViewModelStoreOwner and LifecycleOwner
            currentEntry?.LocalOwnersProvider(saveableStateHolder) {
                (currentEntry.destination as AnimatedComposeNavigator.Destination)
                    .content(this, currentEntry)
            }
        }
        if (transition.currentState == transition.targetState) {
            visibleEntries.forEach { entry ->
                composeNavigator.markTransitionComplete(entry)
            }
        }
    }

    val dialogNavigator = navController.navigatorProvider.get<Navigator<out NavDestination>>(
        "dialog"
    ) as? DialogNavigator ?: return

    // Show any dialog destinations
    DialogHost(dialogNavigator)
}

@ExperimentalAnimationApi
internal val enterTransitions =
    mutableMapOf<String?,
        (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition?)?>()

@ExperimentalAnimationApi
internal val exitTransitions =
    mutableMapOf<String?, (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition?)?>()

@ExperimentalAnimationApi
internal val popEnterTransitions =
    mutableMapOf<String?, (AnimatedContentScope<NavBackStackEntry>.() -> EnterTransition?)?>()

@ExperimentalAnimationApi
internal val popExitTransitions =
    mutableMapOf<String?, (AnimatedContentScope<NavBackStackEntry>.() -> ExitTransition?)?>()
