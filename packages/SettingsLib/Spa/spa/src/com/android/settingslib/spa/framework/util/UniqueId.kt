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

package com.android.settingslib.spa.framework.util

import android.os.Bundle
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.common.SettingsPage

fun genPageId(
    sppName: String,
    parameter: List<NamedNavArgument> = emptyList(),
    arguments: Bundle? = null
): String {
    val normArguments = parameter.normalize(arguments, eraseRuntimeValues = true)
    return "$sppName:${normArguments?.toString()}".toHashId()
}

fun genEntryId(
    name: String,
    owner: SettingsPage,
    fromPage: SettingsPage? = null,
    toPage: SettingsPage? = null
): String {
    return "$name:${owner.id}(${fromPage?.id}-${toPage?.id})".toHashId()
}

// TODO: implement a better hash function
private fun String.toHashId(): String {
    return this.hashCode().toUInt().toString(36)
}
