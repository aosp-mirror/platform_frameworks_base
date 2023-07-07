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
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.settings.UserTracker;

/**
 * Used to interact with Settings.Secure, Settings.Global, and Settings.System.
 *
 * This interface can be implemented to give instance method (instead of static method) versions
 * of Settings.Secure, Settings.Global, and Settings.System. It can be injected into class
 * constructors and then faked or mocked as needed in tests.
 *
 * You can ask for {@link SecureSettings}, {@link GlobalSettings}, or {@link SystemSettings} to be
 * injected as needed.
 *
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
     * Returns that {@link UserTracker} this instance was constructed with.
     */
    UserTracker getUserTracker();

    /**
     * Returns the user id for the associated {@link ContentResolver}.
     */
    default int getUserId() {
        return getContentResolver().getUserId();
    }

    /**
     * Returns the actual current user handle when querying with the current user. Otherwise,
     * returns the passed in user id.
     */
    default int getRealUserHandle(int userHandle) {
        if (userHandle != UserHandle.USER_CURRENT) {
            return userHandle;
        }
        return getUserTracker().getUserId();
    }

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
     *
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
        registerContentObserverForUser(uri, settingsObserver, getUserId());
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     *
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
        registerContentObserverForUser(uri, notifyForDescendants, settingsObserver, getUserId());
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver, int)}
     *
     * Implicitly calls {@link #getUriFor(String)} on the passed in name.
     */
    default void registerContentObserverForUser(
            String name, ContentObserver settingsObserver, int userHandle) {
        registerContentObserverForUser(
                getUriFor(name), settingsObserver, userHandle);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver, int)}
     */
    default void registerContentObserverForUser(
            Uri uri, ContentObserver settingsObserver, int userHandle) {
        registerContentObserverForUser(
                uri, false, settingsObserver, userHandle);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver, int)}
     *
     * Implicitly calls {@link #getUriFor(String)} on the passed in name.
     */
    default void registerContentObserverForUser(
            String name, boolean notifyForDescendants, ContentObserver settingsObserver,
            int userHandle) {
        registerContentObserverForUser(
                getUriFor(name), notifyForDescendants, settingsObserver, userHandle);
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver, int)}
     */
    default void registerContentObserverForUser(
            Uri uri, boolean notifyForDescendants, ContentObserver settingsObserver,
            int userHandle) {
        getContentResolver().registerContentObserver(
                uri, notifyForDescendants, settingsObserver, getRealUserHandle(userHandle));
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
    default String getString(String name) {
        return getStringForUser(name, getUserId());
    }

    /**See {@link #getString(String)}. */
    String getStringForUser(String name, int userHandle);

    /**
     * Store a name/value pair into the database. Values written by this method will be
     * overridden if a restore happens in the future.
     *
     * @param name to store
     * @param value to associate with the name
     * @return true if the value was set, false on database errors
     */
    boolean putString(String name, String value, boolean overrideableByRestore);

    /**
     * Store a name/value pair into the database.
     * @param name to store
     * @param value to associate with the name
     * @return true if the value was set, false on database errors
     */
    default boolean putString(String name, String value) {
        return putStringForUser(name, value, getUserId());
    }

    /** See {@link #putString(String, String)}. */
    boolean putStringForUser(String name, String value, int userHandle);

    /** See {@link #putString(String, String)}. */
    boolean putStringForUser(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault, @UserIdInt int userHandle, boolean overrideableByRestore);

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
        return getIntForUser(name, def, getUserId());
    }

    /** See {@link #getInt(String, int)}. */
    default int getIntForUser(String name, int def, int userHandle) {
        String v = getStringForUser(name, userHandle);
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
    default int getInt(String name) throws Settings.SettingNotFoundException {
        return getIntForUser(name, getUserId());
    }

    /** See {@link #getInt(String)}. */
    default int getIntForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String v = getStringForUser(name, userHandle);
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
        return putIntForUser(name, value, getUserId());
    }

    /** See {@link #putInt(String, int)}. */
    default boolean putIntForUser(String name, int value, int userHandle) {
        return putStringForUser(name, Integer.toString(value), userHandle);
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
        return getBoolForUser(name, def, getUserId());
    }

    /** See {@link #getBool(String, boolean)}. */
    default boolean getBoolForUser(String name, boolean def, int userHandle) {
        return getIntForUser(name, def ? 1 : 0, userHandle) != 0;
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
    default boolean getBool(String name) throws Settings.SettingNotFoundException {
        return getBoolForUser(name, getUserId());
    }

    /** See {@link #getBool(String)}. */
    default boolean getBoolForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        return getIntForUser(name, userHandle) != 0;
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
        return putBoolForUser(name, value, getUserId());
    }

    /** See {@link #putBool(String, boolean)}. */
    default boolean putBoolForUser(String name, boolean value, int userHandle) {
        return putIntForUser(name, value ? 1 : 0, userHandle);
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
        return getLongForUser(name, def, getUserId());
    }

    /** See {@link #getLong(String, long)}. */
    default long getLongForUser(String name, long def, int userHandle) {
        String valString = getStringForUser(name, userHandle);
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
    default long getLong(String name) throws Settings.SettingNotFoundException {
        return getLongForUser(name, getUserId());
    }

    /** See {@link #getLong(String)}. */
    default long getLongForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String valString = getStringForUser(name, userHandle);
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
        return putLongForUser(name, value, getUserId());
    }

    /** See {@link #putLong(String, long)}. */
    default boolean putLongForUser(String name, long value, int userHandle) {
        return putStringForUser(name, Long.toString(value), userHandle);
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
        return getFloatForUser(name, def, getUserId());
    }

    /** See {@link #getFloat(String)}. */
    default float getFloatForUser(String name, float def, int userHandle) {
        String v = getStringForUser(name, userHandle);
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
    default float getFloat(String name) throws Settings.SettingNotFoundException {
        return getFloatForUser(name, getUserId());
    }

    /** See {@link #getFloat(String, float)}. */
    default float getFloatForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String v = getStringForUser(name, userHandle);
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
        return putFloatForUser(name, value, getUserId());
    }

    /** See {@link #putFloat(String, float)} */
    default boolean putFloatForUser(String name, float value, int userHandle) {
        return putStringForUser(name, Float.toString(value), userHandle);
    }
}
