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

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.android.settingslib.spa.framework.common.NullPageProvider

/**
 * Add the [Composable] to the [NavGraphBuilder] with animation
 *
 * @param route route for the destination
 * @param arguments list of arguments to associate with destination
 * @param deepLinks list of deep links to associate with the destinations
 * @param content composable for the destination
 */
internal fun NavGraphBuilder.animatedComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route = route,
    arguments = arguments,
    deepLinks = deepLinks,
    enterTransition = {
        if (initialState.destination.route != NullPageProvider.name) {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = slideInEffect,
                initialOffset = offsetFunc,
            ) + fadeIn(animationSpec = fadeInEffect)
        } else null
    },
    exitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec = slideOutEffect,
            targetOffset = offsetFunc,
        ) + fadeOut(animationSpec = fadeOutEffect)
    },
    popEnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = slideInEffect,
            initialOffset = offsetFunc,
        ) + fadeIn(animationSpec = fadeInEffect)
    },
    popExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec = slideOutEffect,
            targetOffset = offsetFunc,
        ) + fadeOut(animationSpec = fadeOutEffect)
    },
    content = content,
)

private const val FADE_OUT_MILLIS = 75
private const val FADE_IN_MILLIS = 300

private val slideInEffect = tween<IntOffset>(
    durationMillis = FADE_IN_MILLIS,
    delayMillis = FADE_OUT_MILLIS,
    easing = LinearOutSlowInEasing,
)
private val slideOutEffect = tween<IntOffset>(durationMillis = FADE_IN_MILLIS)
private val fadeOutEffect = tween<Float>(
    durationMillis = FADE_OUT_MILLIS,
    easing = FastOutLinearInEasing,
)
private val fadeInEffect = tween<Float>(
    durationMillis = FADE_IN_MILLIS,
    delayMillis = FADE_OUT_MILLIS,
    easing = LinearOutSlowInEasing,
)
private val offsetFunc: (offsetForFullSlide: Int) -> Int = { it.div(5) }
