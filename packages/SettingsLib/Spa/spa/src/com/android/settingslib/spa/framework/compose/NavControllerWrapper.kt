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

package com.android.settingslib.spa.framework.compose

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController

interface NavControllerWrapper {
    fun navigate(route: String)
    fun navigateBack()

    val highlightEntryId: String?
        get() = null

    val sessionSourceName: String?
        get() = null
}

@Composable
fun NavHostController.localNavController(): ProvidedValue<NavControllerWrapper> {
    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    return LocalNavController provides remember {
        NavControllerWrapperImpl(
            navController = this,
            onBackPressedDispatcher = onBackPressedDispatcherOwner?.onBackPressedDispatcher,
        )
    }
}

val LocalNavController = compositionLocalOf<NavControllerWrapper> {
    object : NavControllerWrapper {
        override fun navigate(route: String) {}

        override fun navigateBack() {}
    }
}

@Composable
fun navigator(route: String?): () -> Unit {
    if (route == null) return {}
    val navController = LocalNavController.current
    return { navController.navigate(route) }
}

internal class NavControllerWrapperImpl(
    val navController: NavHostController,
    private val onBackPressedDispatcher: OnBackPressedDispatcher?,
) : NavControllerWrapper {
    var highlightId: String? = null
    var sessionName: String? = null

    override fun navigate(route: String) {
        navController.navigate(route)
    }

    override fun navigateBack() {
        onBackPressedDispatcher?.onBackPressed()
    }

    override val highlightEntryId: String?
        get() = highlightId

    override val sessionSourceName: String?
        get() = sessionName
}
