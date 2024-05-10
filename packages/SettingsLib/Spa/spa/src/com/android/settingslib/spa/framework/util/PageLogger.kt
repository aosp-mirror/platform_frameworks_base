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

import androidx.compose.runtime.Composable
import androidx.core.os.bundleOf
import com.android.settingslib.spa.framework.common.LOG_DATA_DISPLAY_NAME
import com.android.settingslib.spa.framework.common.LOG_DATA_SESSION_NAME
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.LogEvent
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.compose.LifecycleEffect
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper

@Composable
internal fun SettingsPage.PageLogger() {
    val navController = LocalNavController.current
    LifecycleEffect(
        onStart = { logPageEvent(LogEvent.PAGE_ENTER, navController) },
        onStop = { logPageEvent(LogEvent.PAGE_LEAVE, navController) },
    )
}

private fun SettingsPage.logPageEvent(event: LogEvent, navController: NavControllerWrapper) {
    SpaEnvironmentFactory.instance.logger.event(
        id = id,
        event = event,
        category = LogCategory.FRAMEWORK,
        extraData = bundleOf(
            LOG_DATA_DISPLAY_NAME to displayName,
            LOG_DATA_SESSION_NAME to navController.sessionSourceName,
        ).apply {
            val normArguments = parameter.normalize(arguments)
            if (normArguments != null) putAll(normArguments)
        }
    )
}