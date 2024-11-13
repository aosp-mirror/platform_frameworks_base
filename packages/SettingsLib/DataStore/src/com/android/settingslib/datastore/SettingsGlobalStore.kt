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

package com.android.settingslib.datastore

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings.Global
import android.provider.Settings.SettingNotFoundException

/**
 * [KeyValueStore] for [Global] settings.
 *
 * By default, a boolean type `true` value is stored as `1` and `false` value is stored as `0`.
 */
class SettingsGlobalStore private constructor(contentResolver: ContentResolver) :
    SettingsStore(contentResolver) {

    override val tag: String
        get() = "SettingsGlobalStore"

    override fun contains(key: String): Boolean = Global.getString(contentResolver, key) != null

    override fun <T : Any> getValue(key: String, valueType: Class<T>): T? =
        try {
            when (valueType) {
                Boolean::class.javaObjectType -> Global.getInt(contentResolver, key) != 0
                Float::class.javaObjectType -> Global.getFloat(contentResolver, key)
                Int::class.javaObjectType -> Global.getInt(contentResolver, key)
                Long::class.javaObjectType -> Global.getLong(contentResolver, key)
                String::class.javaObjectType -> Global.getString(contentResolver, key)
                else -> throw UnsupportedOperationException("Get $key $valueType")
            }
                as T?
        } catch (e: SettingNotFoundException) {
            null
        }

    override fun <T : Any> setValue(key: String, valueType: Class<T>, value: T?) {
        if (value == null) {
            Global.putString(contentResolver, key, null)
            return
        }
        when (valueType) {
            Boolean::class.javaObjectType ->
                Global.putInt(contentResolver, key, if (value == true) 1 else 0)
            Float::class.javaObjectType -> Global.putFloat(contentResolver, key, value as Float)
            Int::class.javaObjectType -> Global.putInt(contentResolver, key, value as Int)
            Long::class.javaObjectType -> Global.putLong(contentResolver, key, value as Long)
            String::class.javaObjectType -> Global.putString(contentResolver, key, value as String)
            else -> throw UnsupportedOperationException("Set $key $valueType")
        }
    }

    companion object {
        @Volatile private var instance: SettingsGlobalStore? = null

        @JvmStatic
        fun get(context: Context): SettingsGlobalStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: SettingsGlobalStore(context.applicationContext.contentResolver).also {
                            instance = it
                        }
                }
    }
}
