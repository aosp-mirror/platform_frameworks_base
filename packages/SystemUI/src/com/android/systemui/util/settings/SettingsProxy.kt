/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.util.settings

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.provider.Settings.SettingNotFoundException
import com.android.app.tracing.TraceUtils.trace

/**
 * Used to interact with mainly with Settings.Global, but can also be used for Settings.System and
 * Settings.Secure. To use the per-user System and Secure settings, [UserSettingsProxy] must be used
 * instead.
 *
 * This interface can be implemented to give instance method (instead of static method) versions of
 * Settings.Global. It can be injected into class constructors and then faked or mocked as needed in
 * tests.
 *
 * You can ask for [GlobalSettings] to be injected as needed.
 *
 * This class also provides [.registerContentObserver] methods, normally found on [ContentResolver]
 * instances, unifying setting related actions in one place.
 */
interface SettingsProxy {
    /** Returns the [ContentResolver] this instance was constructed with. */
    fun getContentResolver(): ContentResolver

    /**
     * Construct the content URI for a particular name/value pair, useful for monitoring changes
     * with a ContentObserver.
     *
     * @param name to look up in the table
     * @return the corresponding content URI, or null if not present
     */
    fun getUriFor(name: String): Uri

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * Implicitly calls [getUriFor] on the passed in name.
     */
    fun registerContentObserverSync(name: String, settingsObserver: ContentObserver) {
        registerContentObserverSync(getUriFor(name), settingsObserver)
    }

    /** Convenience wrapper around [ContentResolver.registerContentObserver].' */
    fun registerContentObserverSync(uri: Uri, settingsObserver: ContentObserver) =
        registerContentObserverSync(uri, false, settingsObserver)

    /**
     * Convenience wrapper around [ContentResolver.registerContentObserver].'
     *
     * Implicitly calls [getUriFor] on the passed in name.
     */
    fun registerContentObserverSync(
        name: String,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) = registerContentObserverSync(getUriFor(name), notifyForDescendants, settingsObserver)

    /** Convenience wrapper around [ContentResolver.registerContentObserver].' */
    fun registerContentObserverSync(
        uri: Uri,
        notifyForDescendants: Boolean,
        settingsObserver: ContentObserver
    ) {
        trace({ "SP#registerObserver#[$uri]" }) {
            getContentResolver()
                .registerContentObserver(uri, notifyForDescendants, settingsObserver)
        }
    }

    /** See [ContentResolver.unregisterContentObserver]. */
    fun unregisterContentObserverSync(settingsObserver: ContentObserver) {
        trace({ "SP#unregisterObserver" }) {
            getContentResolver().unregisterContentObserver(settingsObserver)
        }
    }

    /**
     * Look up a name in the database.
     *
     * @param name to look up in the table
     * @return the corresponding value, or null if not present
     */
    fun getString(name: String): String

    /**
     * Store a name/value pair into the database.
     *
     * @param name to store
     * @param value to associate with the name
     * @return true if the value was set, false on database errors
     */
    fun putString(name: String, value: String): Boolean

    /**
     * Store a name/value pair into the database.
     *
     * The method takes an optional tag to associate with the setting which can be used to clear
     * only settings made by your package and associated with this tag by passing the tag to
     * [ ][.resetToDefaults]. Anyone can override the current tag. Also if another package changes
     * the setting then the tag will be set to the one specified in the set call which can be null.
     * Also any of the settings setters that do not take a tag as an argument effectively clears the
     * tag.
     *
     * For example, if you set settings A and B with tags T1 and T2 and another app changes setting
     * A (potentially to the same value), it can assign to it a tag T3 (note that now the package
     * that changed the setting is not yours). Now if you reset your changes for T1 and T2 only
     * setting B will be reset and A not (as it was changed by another package) but since A did not
     * change you are in the desired initial state. Now if the other app changes the value of A
     * (assuming you registered an observer in the beginning) you would detect that the setting was
     * changed by another app and handle this appropriately (ignore, set back to some value, etc).
     *
     * Also the method takes an argument whether to make the value the default for this setting. If
     * the system already specified a default value, then the one passed in here will **not** be set
     * as the default.
     *
     * @param name to store.
     * @param value to associate with the name.
     * @param tag to associate with the setting.
     * @param makeDefault whether to make the value the default one.
     * @return true if the value was set, false on database errors.
     * @see .resetToDefaults
     */
    fun putString(name: String, value: String, tag: String, makeDefault: Boolean): Boolean

    /**
     * Convenience function for retrieving a single secure settings value as an integer. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * an integer for you. The default value will be returned if the setting is not defined or not
     * an integer.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     * @return The setting's current value, or 'def' if it is not defined or not a valid integer.
     */
    fun getInt(name: String, def: Int): Int {
        val v = getString(name)
        return try {
            v.toInt()
        } catch (e: NumberFormatException) {
            def
        }
    }

    /**
     * Convenience function for retrieving a single secure settings value as an integer. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * an integer for you.
     *
     * This version does not take a default value. If the setting has not been set, or the string
     * value is not a number, it throws [Settings.SettingNotFoundException].
     *
     * @param name The name of the setting to retrieve.
     * @return The setting's current value.
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given name can't be
     *   found or the setting value is not an integer.
     */
    @Throws(SettingNotFoundException::class)
    fun getInt(name: String): Int {
        val v = getString(name)
        return try {
            v.toInt()
        } catch (e: NumberFormatException) {
            throw SettingNotFoundException(name)
        }
    }

    /**
     * Convenience function for updating a single settings value as an integer. This will either
     * create a new entry in the table if the given name does not exist, or modify the value of the
     * existing row with that name. Note that internally setting values are always stored as
     * strings, so this function converts the given value to a string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    fun putInt(name: String, value: Int): Boolean {
        return putString(name, value.toString())
    }

    /**
     * Convenience function for retrieving a single secure settings value as a boolean. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * a boolean for you. The default value will be returned if the setting is not defined or not a
     * boolean.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     * @return The setting's current value, or 'def' if it is not defined or not a valid boolean.
     */
    fun getBool(name: String, def: Boolean): Boolean {
        return getInt(name, if (def) 1 else 0) != 0
    }

    /**
     * Convenience function for retrieving a single secure settings value as a boolean. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * a boolean for you.
     *
     * This version does not take a default value. If the setting has not been set, or the string
     * value is not a number, it throws [Settings.SettingNotFoundException].
     *
     * @param name The name of the setting to retrieve.
     * @return The setting's current value.
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given name can't be
     *   found or the setting value is not a boolean.
     */
    @Throws(SettingNotFoundException::class)
    fun getBool(name: String): Boolean {
        return getInt(name) != 0
    }

    /**
     * Convenience function for updating a single settings value as a boolean. This will either
     * create a new entry in the table if the given name does not exist, or modify the value of the
     * existing row with that name. Note that internally setting values are always stored as
     * strings, so this function converts the given value to a string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    fun putBool(name: String, value: Boolean): Boolean {
        return putInt(name, if (value) 1 else 0)
    }

    /**
     * Convenience function for retrieving a single secure settings value as a `long`. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * a `long` for you. The default value will be returned if the setting is not defined or not a
     * `long`.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     * @return The setting's current value, or 'def' if it is not defined or not a valid `long`.
     */
    fun getLong(name: String, def: Long): Long {
        val valString = getString(name)
        return parseLongOrUseDefault(valString, def)
    }

    /**
     * Convenience function for retrieving a single secure settings value as a `long`. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * a `long` for you.
     *
     * This version does not take a default value. If the setting has not been set, or the string
     * value is not a number, it throws [Settings.SettingNotFoundException].
     *
     * @param name The name of the setting to retrieve.
     * @return The setting's current value.
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given name can't be
     *   found or the setting value is not an integer.
     */
    @Throws(SettingNotFoundException::class)
    fun getLong(name: String): Long {
        val valString = getString(name)
        return parseLongOrThrow(name, valString)
    }

    /**
     * Convenience function for updating a secure settings value as a long integer. This will either
     * create a new entry in the table if the given name does not exist, or modify the value of the
     * existing row with that name. Note that internally setting values are always stored as
     * strings, so this function converts the given value to a string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    fun putLong(name: String, value: Long): Boolean {
        return putString(name, value.toString())
    }

    /**
     * Convenience function for retrieving a single secure settings value as a floating point
     * number. Note that internally setting values are always stored as strings; this function
     * converts the string to an float for you. The default value will be returned if the setting is
     * not defined or not a valid float.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     * @return The setting's current value, or 'def' if it is not defined or not a valid float.
     */
    fun getFloat(name: String, def: Float): Float {
        val v = getString(name)
        return parseFloat(v, def)
    }

    /**
     * Convenience function for retrieving a single secure settings value as a float. Note that
     * internally setting values are always stored as strings; this function converts the string to
     * a float for you.
     *
     * This version does not take a default value. If the setting has not been set, or the string
     * value is not a number, it throws [Settings.SettingNotFoundException].
     *
     * @param name The name of the setting to retrieve.
     * @return The setting's current value.
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given name can't be
     *   found or the setting value is not a float.
     */
    @Throws(SettingNotFoundException::class)
    fun getFloat(name: String): Float {
        val v = getString(name)
        return parseFloatOrThrow(name, v)
    }

    /**
     * Convenience function for updating a single settings value as a floating point number. This
     * will either create a new entry in the table if the given name does not exist, or modify the
     * value of the existing row with that name. Note that internally setting values are always
     * stored as strings, so this function converts the given value to a string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    fun putFloat(name: String, value: Float): Boolean {
        return putString(name, value.toString())
    }

    companion object {
        /** Convert a string to a long, or uses a default if the string is malformed or null */
        @JvmStatic
        fun parseLongOrUseDefault(valString: String, def: Long): Long {
            val value: Long
            value =
                try {
                    valString.toLong()
                } catch (e: NumberFormatException) {
                    def
                }
            return value
        }

        /** Convert a string to a long, or throws an exception if the string is malformed or null */
        @JvmStatic
        @Throws(SettingNotFoundException::class)
        fun parseLongOrThrow(name: String, valString: String?): Long {
            if (valString == null) {
                throw SettingNotFoundException(name)
            }
            return try {
                valString.toLong()
            } catch (e: NumberFormatException) {
                throw SettingNotFoundException(name)
            }
        }

        /** Convert a string to a float, or uses a default if the string is malformed or null */
        @JvmStatic
        fun parseFloat(v: String?, def: Float): Float {
            return try {
                v?.toFloat() ?: def
            } catch (e: NumberFormatException) {
                def
            }
        }

        /**
         * Convert a string to a float, or throws an exception if the string is malformed or null
         */
        @JvmStatic
        @Throws(SettingNotFoundException::class)
        fun parseFloatOrThrow(name: String, v: String?): Float {
            if (v == null) {
                throw SettingNotFoundException(name)
            }
            return try {
                v.toFloat()
            } catch (e: NumberFormatException) {
                throw SettingNotFoundException(name)
            }
        }
    }
}
