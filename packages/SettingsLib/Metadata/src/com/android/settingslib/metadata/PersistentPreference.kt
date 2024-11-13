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

/** Permit of read and write request. */
@IntDef(
    ReadWritePermit.ALLOW,
    ReadWritePermit.DISALLOW,
    ReadWritePermit.REQUIRE_APP_PERMISSION,
    ReadWritePermit.REQUIRE_USER_AGREEMENT,
)
@Retention(AnnotationRetention.SOURCE)
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

/** Preference interface that has a value persisted in datastore. */
interface PersistentPreference<T> {

    /**
     * Returns the key-value storage of the preference.
     *
     * The default implementation returns the storage provided by
     * [PreferenceScreenRegistry.getKeyValueStore].
     */
    fun storage(context: Context): KeyValueStore =
        PreferenceScreenRegistry.getKeyValueStore(context, this as PreferenceMetadata)!!

    /**
     * Returns if the external application (identified by [callingUid]) has permission to read
     * preference value.
     *
     * The underlying implementation does NOT need to check common states like isEnabled,
     * isRestricted or isAvailable.
     */
    @ReadWritePermit
    fun getReadPermit(context: Context, myUid: Int, callingUid: Int): Int =
        PreferenceScreenRegistry.getReadPermit(
            context,
            myUid,
            callingUid,
            this as PreferenceMetadata,
        )

    /**
     * Returns if the external application (identified by [callingUid]) has permission to write
     * preference with given [value].
     *
     * The underlying implementation does NOT need to check common states like isEnabled,
     * isRestricted or isAvailable.
     */
    @ReadWritePermit
    fun getWritePermit(context: Context, value: T?, myUid: Int, callingUid: Int): Int =
        PreferenceScreenRegistry.getWritePermit(
            context,
            value,
            myUid,
            callingUid,
            this as PreferenceMetadata,
        )
}

/** Descriptor of values. */
sealed interface ValueDescriptor {

    /** Returns if given value (represented by index) is valid. */
    fun isValidValue(context: Context, index: Int): Boolean
}

/**
 * A boolean type value.
 *
 * A zero value means `False`, otherwise it is `True`.
 */
interface BooleanValue : ValueDescriptor {
    override fun isValidValue(context: Context, index: Int) = true
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

/** Value is between a range. */
interface RangeValue : ValueDescriptor {
    /** The lower bound (inclusive) of the range. */
    val minValue: Int

    /** The upper bound (inclusive) of the range. */
    val maxValue: Int

    /** The increment step within the range. 0 means unset, which implies step size is 1. */
    val incrementStep: Int
        get() = 0

    override fun isValidValue(context: Context, index: Int) = index in minValue..maxValue
}
