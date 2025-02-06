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

package com.android.settingslib.metadata

import android.content.Context
import androidx.annotation.ArrayRes
import androidx.annotation.IntDef
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.datastore.Permissions

/** Permit of read and write request. */
@IntDef(
    ReadWritePermit.ALLOW,
    ReadWritePermit.DISALLOW,
    ReadWritePermit.REQUIRE_APP_PERMISSION,
    ReadWritePermit.REQUIRE_USER_AGREEMENT,
)
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE)
annotation class ReadWritePermit {
    companion object {
        /** Allow to read/write value. */
        const val ALLOW = 0
        /** Disallow to read/write value (e.g. uid not allowed). */
        const val DISALLOW = 1
        /** Require (runtime/special) app permission from user explicitly. */
        const val REQUIRE_APP_PERMISSION = 2
        /** Require explicit user agreement (e.g. terms of service). */
        const val REQUIRE_USER_AGREEMENT = 3
    }
}

/** The reason of preference change. */
@IntDef(
    PreferenceChangeReason.VALUE,
    PreferenceChangeReason.STATE,
    PreferenceChangeReason.DEPENDENT,
)
@Retention(AnnotationRetention.SOURCE)
annotation class PreferenceChangeReason {
    companion object {
        /** Preference value is changed. */
        const val VALUE = 1000
        /** Preference state (title/summary, enable state, etc.) is changed. */
        const val STATE = 1001
        /** Dependent preference state is changed. */
        const val DEPENDENT = 1002
    }
}

/** Indicates how sensitive of the data. */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE)
annotation class SensitivityLevel {
    companion object {
        const val UNKNOWN_SENSITIVITY = 0
        const val NO_SENSITIVITY = 1
        const val LOW_SENSITIVITY = 2
        const val MEDIUM_SENSITIVITY = 3
        const val HIGH_SENSITIVITY = 4
    }
}

/** Preference metadata that has a value persisted in datastore. */
interface PersistentPreference<T> : PreferenceMetadata {

    /** The value type the preference is associated with. */
    val valueType: Class<T>

    /**
     * Returns the key-value storage of the preference.
     *
     * The default implementation returns the storage provided by
     * [PreferenceScreenRegistry.getKeyValueStore].
     */
    fun storage(context: Context): KeyValueStore =
        PreferenceScreenRegistry.getKeyValueStore(context, this)!!

    /** Returns the required permissions to read preference value. */
    fun getReadPermissions(context: Context): Permissions? = null

    /**
     * Returns if the external application (identified by [callingPid] and [callingUid]) is
     * permitted to read preference value.
     *
     * The underlying implementation does NOT need to check common states like isEnabled,
     * isRestricted, isAvailable or permissions in [getReadPermissions]. The framework will do it
     * behind the scene.
     */
    fun getReadPermit(context: Context, callingPid: Int, callingUid: Int): @ReadWritePermit Int =
        PreferenceScreenRegistry.getReadPermit(
            context,
            callingPid,
            callingUid,
            this,
        )

    /** Returns the required permissions to write preference value. */
    fun getWritePermissions(context: Context): Permissions? = null

    /**
     * Returns if the external application (identified by [callingPid] and [callingUid]) is
     * permitted to write preference with given [value].
     *
     * The underlying implementation does NOT need to check common states like isEnabled,
     * isRestricted, isAvailable or permissions in [getWritePermissions]. The framework will do it
     * behind the scene.
     */
    fun getWritePermit(
        context: Context,
        value: T?,
        callingPid: Int,
        callingUid: Int,
    ): @ReadWritePermit Int =
        PreferenceScreenRegistry.getWritePermit(
            context,
            value,
            callingPid,
            callingUid,
            this,
        )

    /** The sensitivity level of the preference. */
    val sensitivityLevel: @SensitivityLevel Int
        get() = SensitivityLevel.UNKNOWN_SENSITIVITY
}

/** Descriptor of values. */
sealed interface ValueDescriptor {

    /** Returns if given value (represented by index) is valid. */
    fun isValidValue(context: Context, index: Int): Boolean
}

/** Value falls into a given array. */
interface DiscreteValue<T> : ValueDescriptor {
    @get:ArrayRes val values: Int

    @get:ArrayRes val valuesDescription: Int

    fun getValue(context: Context, index: Int): T
}

/**
 * Value falls into a text array, whose element is [CharSequence] type.
 *
 * [values] resource is `<string-array>`.
 */
interface DiscreteTextValue : DiscreteValue<CharSequence> {
    override fun isValidValue(context: Context, index: Int): Boolean {
        if (index < 0) return false
        return index < context.resources.getTextArray(values).size
    }

    override fun getValue(context: Context, index: Int): CharSequence =
        context.resources.getTextArray(values)[index]
}

/**
 * Value falls into a string array, whose element is [String] type.
 *
 * [values] resource is `<string-array>`.
 */
interface DiscreteStringValue : DiscreteValue<String> {
    override fun isValidValue(context: Context, index: Int): Boolean {
        if (index < 0) return false
        return index < context.resources.getStringArray(values).size
    }

    override fun getValue(context: Context, index: Int): String =
        context.resources.getStringArray(values)[index]
}

/**
 * Value falls into an integer array.
 *
 * [values] resource is `<integer-array>`.
 */
interface DiscreteIntValue : DiscreteValue<Int> {
    override fun isValidValue(context: Context, index: Int): Boolean {
        if (index < 0) return false
        return index < context.resources.getIntArray(values).size
    }

    override fun getValue(context: Context, index: Int): Int =
        context.resources.getIntArray(values)[index]
}
