/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.settingsprovider

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.android.settingslib.spaprivileged.database.contentChangeFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

fun Context.settingsSecureString(
    name: String,
    defaultValue: String = ""
): ReadWriteProperty<Any?, String> = SettingsSecureStringDelegate(this, name, defaultValue)

fun Context.settingsSecureStringFlow(name: String, defaultValue: String = ""): Flow<String> {
    val value by settingsSecureString(name, defaultValue)
    return contentChangeFlow(Settings.Secure.getUriFor(name))
        .map { value }
        .distinctUntilChanged()
        .conflate()
        .flowOn(Dispatchers.Default)
}

private class SettingsSecureStringDelegate(
    context: Context,
    private val name: String,
    private val defaultValue: String = "",
) : ReadWriteProperty<Any?, String> {

    private val contentResolver: ContentResolver = context.contentResolver

    override fun getValue(thisRef: Any?, property: KProperty<*>): String =
        Settings.Secure.getString(contentResolver, name) ?: defaultValue

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        Settings.Secure.putString(contentResolver, name, value)
    }
}
