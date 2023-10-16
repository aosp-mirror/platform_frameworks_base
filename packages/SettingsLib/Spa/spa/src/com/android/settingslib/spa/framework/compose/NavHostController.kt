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

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.Navigator
import androidx.navigation.compose.rememberNavController

/**
 * Creates a NavHostController that handles the adding of the [ComposeNavigator], [DialogNavigator]
 * and [AnimatedComposeNavigator]. Additional [androidx.navigation.Navigator] instances should be
 * added in a [androidx.compose.runtime.SideEffect] block.
 *
 * @see AnimatedNavHost
 */
@ExperimentalAnimationApi
@Composable
fun rememberAnimatedNavController(
    vararg navigators: Navigator<out NavDestination>
): NavHostController {
    val animatedNavigator = remember { AnimatedComposeNavigator() }
    return rememberNavController(animatedNavigator, *navigators)
}
