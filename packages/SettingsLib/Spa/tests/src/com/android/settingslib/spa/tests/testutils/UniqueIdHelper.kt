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

package com.android.settingslib.spa.tests.testutils

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.toHashId
import com.android.settingslib.spa.framework.util.normalize

fun getUniquePageId(
    name: String,
    parameter: List<NamedNavArgument> = emptyList(),
    arguments: Bundle? = null
): String {
    val normArguments = parameter.normalize(arguments, eraseRuntimeValues = true)
    return "$name:${normArguments?.toString()}".toHashId()
}

fun getUniquePageId(page: SettingsPage): String {
    return getUniquePageId(page.sppName, page.parameter, page.arguments)
}

fun getUniqueEntryId(
    name: String,
    owner: SettingsPage,
    fromPage: SettingsPage? = null,
    toPage: SettingsPage? = null
): String {
    val ownerId = getUniquePageId(owner)
    val fromId = if (fromPage == null) "null" else getUniquePageId(fromPage)
    val toId = if (toPage == null) "null" else getUniquePageId(toPage)
    return "$name:$ownerId($fromId-$toId)".toHashId()
}
