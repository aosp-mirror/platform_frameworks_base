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

package com.android.systemui.util.settings;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

/**
 * Used to interact with mainly with Settings.Global, but can also be used for Settings.System
 * and Settings.Secure. To use the per-user System and Secure settings, {@link UserSettingsProxy}
 * must be used instead.
 * <p>
 * This interface can be implemented to give instance method (instead of static method) versions
 * of Settings.Global. It can be injected into class constructors and then faked or mocked as needed
 * in tests.
 * <p>
 * You can ask for {@link GlobalSettings} to be injected as needed.
 * <p>
 * This class also provides {@link #registerContentObserver(String, ContentObserver)} methods,
 * normally found on {@link ContentResolver} instances, unifying setting related actions in one
 * place.
 */
public interface SettingsProxy {

    /**
     * Returns the {@link ContentResolver} this instance was constructed with.
     */
    ContentResolver getContentResolver();

    /**
     * Construct the content URI for a particular name/value pair,
     * useful for monitoring changes with a ContentObserver.
     * @param name to look up in the table
     * @return the corresponding content URI, or null if not present
     */
    Uri getUriFor(String name);

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     * <p>
     * Implicitly calls {@link #getUriFor(String)} on the passed in name.
     */
    default void registerContentObserver(String name, ContentObserver settingsObserver) {
        registerContentObserver(getUriFor(name), settingsObserver);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     */
    default void registerContentObserver(Uri uri, ContentObserver settingsObserver) {
        registerContentObserver(uri, false, settingsObserver);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     * <p>
     * Implicitly calls {@link #getUriFor(String)} on the passed in name.
     */
    default void registerContentObserver(String name, boolean notifyForDescendants,
            ContentObserver settingsObserver) {
        registerContentObserver(getUriFor(name), notifyForDescendants, settingsObserver);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     */
    default void registerContentObserver(Uri uri, boolean notifyForDescendants,
            ContentObserver settingsObserver) {
        getContentResolver().registerContentObserver(
                uri, notifyForDescendants, settingsObserver);
    }

    /** See {@link ContentResolver#unregisterContentObserver(ContentObserver)}. */
    default void unregisterContentObserver(ContentObserver settingsObserver) {
        getContentResolver().unregisterContentObserver(settingsObserver);
    }

    /**
     * Look up a name in the database.
     * @param name to look up in the table
     * @return the corresponding value, or null if not present
     */
    @Nullable
    String getString(String name);

    /**
     * Store a name/value pair into the database.
     * @param name to store
     * @param value to associate with the name
     * @return true if the value was set, false on database errors
     */
    boolean putString(String name, String value);

    /**
     * Store a name/value pair into the database.
     * <p>
     * The method takes an optional tag to associate with the setting
     * which can be used to clear only settings made by your package and
     * associated with this tag by passing the tag to {@link
     * #resetToDefaults(String)}. Anyone can override
     * the current tag. Also if another package changes the setting
     * then the tag will be set to the one specified in the set call
     * which can be null. Also any of the settings setters that do not
     * take a tag as an argument effectively clears the tag.
     * </p><p>
     * For example, if you set settings A and B with tags T1 and T2 and
     * another app changes setting A (potentially to the same value), it
     * can assign to it a tag T3 (note that now the package that changed
     * the setting is not yours). Now if you reset your changes for T1 and
     * T2 only setting B will be reset and A not (as it was changed by
     * another package) but since A did not change you are in the desired
     * initial state. Now if the other app changes the value of A (assuming
     * you registered an observer in the beginning) you would detect that
     * the setting was changed by another app and handle this appropriately
     * (ignore, set back to some value, etc).
     * </p><p>
     * Also the method takes an argument whether to make the value the
     * default for this setting. If the system already specified a default
     * value, then the one passed in here will <strong>not</strong>
     * be set as the default.
     * </p>
     *
     * @param name to store.
     * @param value to associate with the name.
     * @param tag to associate with the setting.
     * @param makeDefault whether to make the value the default one.
     * @return true if the value was set, false on database errors.
     *
     * @see #resetToDefaults(String)
     *
     */
    boolean putString(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault);

    /**
     * Convenience function for retrieving a single secure settings value
     * as an integer.  Note that internally setting values are always
     * stored as strings; this function converts the string to an integer
     * for you.  The default value will be returned if the setting is
     * not defined or not an integer.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     * or not a valid integer.
     */
    default int getInt(String name, int def) {
        String v = getString(name);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as an integer.  Note that internally setting values are always
     * stored as strings; this function converts the string to an integer
     * for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link Settings.SettingNotFoundException}.
     *
     * @param name The name of the setting to retrieve.
     *
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not an integer.
     *
     * @return The setting's current value.
     */
    default int getInt(String name)
            throws Settings.SettingNotFoundException {
        String v = getString(name);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    /**
     * Convenience function for updating a single settings value as an
     * integer. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    default boolean putInt(String name, int value) {
        return putString(name, Integer.toString(value));
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a boolean.  Note that internally setting values are always
     * stored as strings; this function converts the string to a boolean
     * for you.  The default value will be returned if the setting is
     * not defined or not a boolean.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     * or not a valid boolean.
     */
    default boolean getBool(String name, boolean def) {
        return getInt(name, def ? 1 : 0) != 0;
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a boolean.  Note that internally setting values are always
     * stored as strings; this function converts the string to a boolean
     * for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link Settings.SettingNotFoundException}.
     *
     * @param name The name of the setting to retrieve.
     *
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not a boolean.
     *
     * @return The setting's current value.
     */
    default boolean getBool(String name)
            throws Settings.SettingNotFoundException {
        return getInt(name) != 0;
    }

    /**
     * Convenience function for updating a single settings value as a
     * boolean. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    default boolean putBool(String name, boolean value) {
        return putInt(name, value ? 1 : 0);
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a {@code long}.  Note that internally setting values are always
     * stored as strings; this function converts the string to a {@code long}
     * for you.  The default value will be returned if the setting is
     * not defined or not a {@code long}.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     * or not a valid {@code long}.
     */
    default long getLong(String name, long def) {
        String valString = getString(name);
        return parseLongOrUseDefault(valString, def);
    }

    /** Convert a string to a long, or uses a default if the string is malformed or null */
    static long parseLongOrUseDefault(String valString, long def) {
        long value;
        try {
            value = valString != null ? Long.parseLong(valString) : def;
        } catch (NumberFormatException e) {
            value = def;
        }
        return value;
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a {@code long}.  Note that internally setting values are always
     * stored as strings; this function converts the string to a {@code long}
     * for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link Settings.SettingNotFoundException}.
     *
     * @param name The name of the setting to retrieve.
     *
     * @return The setting's current value.
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not an integer.
     */
    default long getLong(String name)
            throws Settings.SettingNotFoundException {
        String valString = getString(name);
        return parseLongOrThrow(name, valString);
    }

    /** Convert a string to a long, or throws an exception if the string is malformed or null */
    static long parseLongOrThrow(String name, String valString)
            throws Settings.SettingNotFoundException {
        try {
            return Long.parseLong(valString);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    /**
     * Convenience function for updating a secure settings value as a long
     * integer. This will either create a new entry in the table if the
     * given name does not exist, or modify the value of the existing row
     * with that name.  Note that internally setting values are always
     * stored as strings, so this function converts the given value to a
     * string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    default boolean putLong(String name, long value) {
        return putString(name, Long.toString(value));
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a floating point number.  Note that internally setting values are
     * always stored as strings; this function converts the string to an
     * float for you. The default value will be returned if the setting
     * is not defined or not a valid float.
     *
     * @param name The name of the setting to retrieve.
     * @param def Value to return if the setting is not defined.
     *
     * @return The setting's current value, or 'def' if it is not defined
     * or not a valid float.
     */
    default float getFloat(String name, float def) {
        String v = getString(name);
        return parseFloat(v, def);
    }

    /** Convert a string to a float, or uses a default if the string is malformed or null */
    static float parseFloat(String v, float def) {
        try {
            return v != null ? Float.parseFloat(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Convenience function for retrieving a single secure settings value
     * as a float.  Note that internally setting values are always
     * stored as strings; this function converts the string to a float
     * for you.
     * <p>
     * This version does not take a default value.  If the setting has not
     * been set, or the string value is not a number,
     * it throws {@link Settings.SettingNotFoundException}.
     *
     * @param name The name of the setting to retrieve.
     *
     * @throws Settings.SettingNotFoundException Thrown if a setting by the given
     * name can't be found or the setting value is not a float.
     *
     * @return The setting's current value.
     */
    default float getFloat(String name)
            throws Settings.SettingNotFoundException {
        String v = getString(name);
        return parseFloatOrThrow(name, v);
    }

    /** Convert a string to a float, or throws an exception if the string is malformed or null */
    static float parseFloatOrThrow(String name, String v)
            throws Settings.SettingNotFoundException {
        if (v == null) {
            throw new Settings.SettingNotFoundException(name);
        }
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    /**
     * Convenience function for updating a single settings value as a
     * floating point number. This will either create a new entry in the
     * table if the given name does not exist, or modify the value of the
     * existing row with that name.  Note that internally setting values
     * are always stored as strings, so this function converts the given
     * value to a string before storing it.
     *
     * @param name The name of the setting to modify.
     * @param value The new value for the setting.
     * @return true if the value was set, false on database errors
     */
    default boolean putFloat(String name, float value) {
        return putString(name, Float.toString(value));
    }
}
