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

package com.android.settingslib.spa.framework.util

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.settingslib.spa.framework.common.LOG_DATA_DISPLAY_NAME
import com.android.settingslib.spa.framework.common.LOG_DATA_SESSION_NAME
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.LocalNavController

@Composable
internal fun SettingsPageProvider.PageEvent(arguments: Bundle? = null) {
    val page = remember(arguments) { createSettingsPage(arguments) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = LocalNavController.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val logPageEvent: (event: LogEvent) -> Unit = {
                SpaEnvironmentFactory.instance.logger.event(
                    id = page.id,
                    event = it,
                    category = LogCategory.FRAMEWORK,
                    extraData = bundleOf(
                        LOG_DATA_DISPLAY_NAME to page.displayName,
                        LOG_DATA_SESSION_NAME to navController.sessionSourceName,
                    ).apply {
                        val normArguments = parameter.normalize(arguments)
                        if (normArguments != null) putAll(normArguments)
                    }
                )
            }
            if (event == Lifecycle.Event.ON_START) {
                logPageEvent(LogEvent.PAGE_ENTER)
            } else if (event == Lifecycle.Event.ON_STOP) {
                logPageEvent(LogEvent.PAGE_LEAVE)
            }
        }

        // Add the observer to the lifecycle
        lifecycleOwner.lifecycle.addObserver(observer)

        // When the effect leaves the Composition, remove the observer
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
