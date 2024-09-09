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
import android.provider.Settings.SettingNotFoundException
import android.provider.Settings.System

/**
 * [KeyValueStore] for [System] settings.
 *
 * By default, a boolean type `true` value is stored as `1` and `false` value is stored as `0`.
 */
class SettingsSystemStore private constructor(contentResolver: ContentResolver) :
    SettingsStore(contentResolver) {

    override val tag: String
        get() = "SettingsSystemStore"

    override fun contains(key: String): Boolean = System.getString(contentResolver, key) != null

    override fun <T : Any> getValue(key: String, valueType: Class<T>): T? =
        try {
            when (valueType) {
                Boolean::class.javaObjectType -> System.getInt(contentResolver, key) != 0
                Float::class.javaObjectType -> System.getFloat(contentResolver, key)
                Int::class.javaObjectType -> System.getInt(contentResolver, key)
                Long::class.javaObjectType -> System.getLong(contentResolver, key)
                String::class.javaObjectType -> System.getString(contentResolver, key)
                else -> throw UnsupportedOperationException("Get $key $valueType")
            }
                as T?
        } catch (e: SettingNotFoundException) {
            null
        }

    override fun <T : Any> setValue(key: String, valueType: Class<T>, value: T?) {
        if (value == null) {
            System.putString(contentResolver, key, null)
            return
        }
        when (valueType) {
            Boolean::class.javaObjectType ->
                System.putInt(contentResolver, key, if (value == true) 1 else 0)
            Float::class.javaObjectType -> System.putFloat(contentResolver, key, value as Float)
            Int::class.javaObjectType -> System.putInt(contentResolver, key, value as Int)
            Long::class.javaObjectType -> System.putLong(contentResolver, key, value as Long)
            String::class.javaObjectType -> System.putString(contentResolver, key, value as String)
            else -> throw UnsupportedOperationException("Set $key $valueType")
        }
    }

    companion object {
        @Volatile private var instance: SettingsSystemStore? = null

        @JvmStatic
        fun get(context: Context): SettingsSystemStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: SettingsSystemStore(context.applicationContext.contentResolver).also {
                            instance = it
                        }
                }
    }
}
