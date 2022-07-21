/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.compose.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation

/**
 * A screen in an app. It is either an [ParentScreen] which lists its child screens to navigate to
 * them or a [ChildScreen] which shows some content.
 */
sealed class Screen(val identifier: String)

class ParentScreen(
    identifier: String,
    val children: Map<String, Screen>,
) : Screen(identifier)

class ChildScreen(
    identifier: String,
    val content: @Composable (NavController) -> Unit,
) : Screen(identifier)

/** Create the navigation graph for [screen]. */
fun NavGraphBuilder.screen(
    screen: Screen,
    navController: NavController,
    onControlToggleRequested: () -> Unit,
) {
    when (screen) {
        is ChildScreen -> composable(screen.identifier) { screen.content(navController) }
        is ParentScreen -> {
            val menuRoute = "${screen.identifier}_menu"
            navigation(startDestination = menuRoute, route = screen.identifier) {
                // The menu to navigate to one of the children screens.
                composable(menuRoute) {
                    ScreenMenu(screen, navController, onControlToggleRequested)
                }

                // The content of the child screens.
                screen.children.forEach { (_, child) ->
                    screen(
                        child,
                        navController,
                        onControlToggleRequested,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenMenu(
    screen: ParentScreen,
    navController: NavController,
    onControlToggleRequested: () -> Unit,
) {
    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = CircleShape,
            ) {
                Column(
                    Modifier.clickable(onClick = onControlToggleRequested).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Toggle controls")
                }
            }
        }

        screen.children.forEach { (name, child) ->
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                ) {
                    Column(
                        Modifier.clickable { navController.navigate(child.identifier) }
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(name)
                    }
                }
            }
        }
    }
}
