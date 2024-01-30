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

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.waitUntilExists
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnBackEffectTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var onBackEffectCalled = false

    @Test
    fun onBackEffect() {
        composeTestRule.setContent {
            TestNavHost {
                val navController = LocalNavController.current
                LaunchedEffect(Unit) {
                    navController.navigate(ROUTE_B)
                    delay(100)
                    navController.navigateBack()
                }
            }
        }

        composeTestRule.waitUntilExists(hasText(ROUTE_A))
        assertThat(onBackEffectCalled).isTrue()
    }

    @Composable
    private fun TestNavHost(content: @Composable () -> Unit) {
        val navController = rememberNavController()
        CompositionLocalProvider(navController.localNavController()) {
            NavHost(navController, ROUTE_A) {
                composable(route = ROUTE_A) { Text(ROUTE_A) }
                composable(route = ROUTE_B) {
                    Text(ROUTE_B)

                    OnBackEffect {
                        onBackEffectCalled = true
                    }
                }
            }
            content()
        }
    }

    private companion object {
        const val ROUTE_A = "RouteA"
        const val ROUTE_B = "RouteB"
    }
}
