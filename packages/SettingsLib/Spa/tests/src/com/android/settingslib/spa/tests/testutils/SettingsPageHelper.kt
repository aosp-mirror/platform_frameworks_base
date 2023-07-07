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

package com.android.settingslib.spa.tests.testutils

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.util.genPageId

fun createSettingsPage(
    sppName: String,
    displayName: String? = null,
    parameter: List<NamedNavArgument> = emptyList(),
    arguments: Bundle? = null
): SettingsPage {
    return SettingsPage(
        id = genPageId(sppName, parameter, arguments),
        sppName = sppName,
        displayName = displayName ?: sppName,
        parameter = parameter,
        arguments = arguments
    )
}
