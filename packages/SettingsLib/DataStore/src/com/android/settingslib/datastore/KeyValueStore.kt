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

import android.content.SharedPreferences

/** Interface of key-value store. */
interface KeyValueStore : KeyedObservable<String> {

    /** Returns if the storage contains persistent value of given key. */
    fun contains(key: String): Boolean

    /** Gets default value of given key. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getDefaultValue(key: String, valueType: Class<T>): T? =
        when (valueType) {
            Boolean::class.javaObjectType -> false
            Float::class.javaObjectType -> 0f
            Int::class.javaObjectType -> 0
            Long::class.javaObjectType -> 0
            else -> null
        }
            as T?

    /** Gets value of given key. */
    fun <T : Any> getValue(key: String, valueType: Class<T>): T?

    /**
     * Sets value for given key.
     *
     * @param key key
     * @param valueType value type
     * @param value value to set, null means remove
     */
    fun <T : Any> setValue(key: String, valueType: Class<T>, value: T?)

    /** Gets the boolean value of given key. */
    fun getBoolean(key: String): Boolean? = getValue(key, Boolean::class.javaObjectType)

    /** Sets boolean value for given key, null value means delete the key from data store. */
    fun setBoolean(key: String, value: Boolean?) =
        setValue(key, Boolean::class.javaObjectType, value)

    /** Gets the float value of given key. */
    fun getFloat(key: String): Float? = getValue(key, Float::class.javaObjectType)

    /** Sets float value for given key, null value means delete the key from data store. */
    fun setFloat(key: String, value: Float?) = setValue(key, Float::class.javaObjectType, value)

    /** Gets the int value of given key. */
    fun getInt(key: String): Int? = getValue(key, Int::class.javaObjectType)

    /** Sets int value for given key, null value means delete the key from data store. */
    fun setInt(key: String, value: Int?) = setValue(key, Int::class.javaObjectType, value)

    /** Gets the long value of given key. */
    fun getLong(key: String): Long? = getValue(key, Long::class.javaObjectType)

    /** Sets long value for given key, null value means delete the key from data store. */
    fun setLong(key: String, value: Long?) = setValue(key, Long::class.javaObjectType, value)

    /** Gets the string value of given key. */
    fun getString(key: String): String? = getValue(key, String::class.javaObjectType)

    /** Sets string value for given key, null value means delete the key from data store. */
    fun setString(key: String, value: String?) = setValue(key, String::class.javaObjectType, value)
}

/** [SharedPreferences] based [KeyValueStore]. */
interface SharedPreferencesKeyValueStore : KeyValueStore {

    /** [SharedPreferences] of the key-value store. */
    val sharedPreferences: SharedPreferences

    override fun contains(key: String): Boolean = sharedPreferences.contains(key)

    @Suppress("IMPLICIT_CAST_TO_ANY", "UNCHECKED_CAST")
    override fun <T : Any> getValue(key: String, valueType: Class<T>): T? =
        when (valueType) {
            Boolean::class.javaObjectType -> sharedPreferences.getBoolean(key, false)
            Float::class.javaObjectType -> sharedPreferences.getFloat(key, 0f)
            Int::class.javaObjectType -> sharedPreferences.getInt(key, 0)
            Long::class.javaObjectType -> sharedPreferences.getLong(key, 0)
            String::class.javaObjectType -> sharedPreferences.getString(key, null)
            Set::class.javaObjectType -> sharedPreferences.getStringSet(key, null)
            else -> null
        }
            as T?

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> setValue(key: String, valueType: Class<T>, value: T?) {
        if (value == null) {
            sharedPreferences.edit().remove(key).apply()
            return
        }
        val edit = sharedPreferences.edit()
        when (valueType) {
            Boolean::class.javaObjectType -> edit.putBoolean(key, value as Boolean)
            Float::class.javaObjectType -> edit.putFloat(key, value as Float)
            Int::class.javaObjectType -> edit.putInt(key, value as Int)
            Long::class.javaObjectType -> edit.putLong(key, value as Long)
            String::class.javaObjectType -> edit.putString(key, value as String)
            Set::class.javaObjectType -> edit.putStringSet(key, value as Set<String>)
            else -> {}
        }
        edit.apply()
    }
}
