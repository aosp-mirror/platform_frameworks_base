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

package com.android.settingslib.spaprivileged.settingsprovider

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.Flow

fun Context.settingsGlobalBoolean(name: String, defaultValue: Boolean = false):
    ReadWriteProperty<Any?, Boolean> = SettingsGlobalBooleanDelegate(this, name, defaultValue)

fun Context.settingsGlobalBooleanFlow(name: String, defaultValue: Boolean = false): Flow<Boolean> {
    val value by settingsGlobalBoolean(name, defaultValue)
    return settingsGlobalFlow(name) { value }
}

private class SettingsGlobalBooleanDelegate(
    context: Context,
    private val name: String,
    private val defaultValue: Boolean = false,
) : ReadWriteProperty<Any?, Boolean> {

    private val contentResolver: ContentResolver = context.contentResolver

    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
        Settings.Global.getInt(contentResolver, name, if (defaultValue) 1 else 0) != 0

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        Settings.Global.putInt(contentResolver, name, if (value) 1 else 0)
    }
}
