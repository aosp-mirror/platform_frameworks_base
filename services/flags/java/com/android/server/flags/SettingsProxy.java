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

package com.android.server.flags;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

/**
 * Wrapper class meant to enable hermetic testing of {@link Settings}.
 *
 * Implementations of this class are expected to be constructed with a {@link ContentResolver} or,
 * otherwise have access to an implicit one. All the proxy methods in this class exclude
 * {@link ContentResolver} from their signature and rely on an internally defined one instead.
 *
 * Most methods in the {@link Settings} classes have default implementations defined.
 * Implementations of this interfac need only concern themselves with getting and putting Strings.
 * They should also override any methods for a class they are proxying that _are not_ defined, and
 * throw an appropriate {@link UnsupportedOperationException}. For instance, {@link Settings.Global}
 * does not define {@link #putString(String, String, boolean)}, so an implementation of this
 * interface that proxies through to it should throw an exception when that method is called.
 *
 * This class adds in the following helpers as well:
 *  - {@link #getBool(String)}
 *  - {@link #putBool(String, boolean)}
 *  - {@link #registerContentObserver(Uri, ContentObserver)}
 *
 * ... and similar variations for all of those.
 */
public interface SettingsProxy {

    /**
     * Returns the {@link ContentResolver} this instance uses.
     */
    ContentResolver getContentResolver();

    /**
     * Construct the content URI for a particular name/value pair,
     * useful for monitoring changes with a ContentObserver.
     * @param name to look up in the table
     * @return the corresponding content URI, or null if not present
     */
    Uri getUriFor(String name);

    /**See {@link Settings.Secure#getString(ContentResolver, String)} */
    String getStringForUser(String name, int userHandle);

    /**See {@link Settings.Secure#putString(ContentResolver, String, String, boolean)} */
    boolean putString(String name, String value, boolean overrideableByRestore);

    /** See {@link Settings.Secure#putStringForUser(ContentResolver, String, String, int)} */
    boolean putStringForUser(String name, String value, int userHandle);

    /**
     * See {@link Settings.Secure#putStringForUser(ContentResolver, String, String, String, boolean,
     * int, boolean)}
     */
    boolean putStringForUser(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault, @UserIdInt int userHandle, boolean overrideableByRestore);

    /** See {@link Settings.Secure#putString(ContentResolver, String, String, String, boolean)} */
    boolean putString(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault);

    /**
     * Returns the user id for the associated {@link ContentResolver}.
     */
    default int getUserId() {
        return getContentResolver().getUserId();
    }

    /** See {@link Settings.Secure#getString(ContentResolver, String)} */
    default String getString(String name) {
        return getStringForUser(name, getUserId());
    }

    /** See {@link Settings.Secure#putString(ContentResolver, String, String)} */
    default boolean putString(String name, String value) {
        return putStringForUser(name, value, getUserId());
    }
    /** See {@link Settings.Secure#getIntForUser(ContentResolver, String, int, int)} */
    default int getIntForUser(String name, int def, int userHandle) {
        String v = getStringForUser(name, userHandle);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** See {@link Settings.Secure#getInt(ContentResolver, String)}  */
    default int getInt(String name) throws Settings.SettingNotFoundException {
        return getIntForUser(name, getUserId());
    }

    /** See {@link Settings.Secure#getIntForUser(ContentResolver, String, int)} */
    default int getIntForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String v = getStringForUser(name, userHandle);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    /** See {@link Settings.Secure#putInt(ContentResolver, String, int)} */
    default boolean putInt(String name, int value) {
        return putIntForUser(name, value, getUserId());
    }

    /** See {@link Settings.Secure#putIntForUser(ContentResolver, String, int, int)} */
    default boolean putIntForUser(String name, int value, int userHandle) {
        return putStringForUser(name, Integer.toString(value), userHandle);
    }

    /**
     * Convenience function for retrieving a single settings value
     * as a boolean.  Note that internally setting values are always
     * stored as strings; this function converts the string to a boolean
     * for you. The default value will be returned if the setting is
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
     * Convenience function for retrieving a single settings value
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

    /** See {@link Settings.Secure#getLong(ContentResolver, String, long)}  */
    default long getLong(String name, long def) {
        return getLongForUser(name, def, getUserId());
    }

    /** See {@link Settings.Secure#getLongForUser(ContentResolver, String, long, int)}  */
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

    /** See {@link Settings.Secure#getLong(ContentResolver, String)}  */
    default long getLong(String name) throws Settings.SettingNotFoundException {
        return getLongForUser(name, getUserId());
    }

    /** See {@link Settings.Secure#getLongForUser(ContentResolver, String, int)}  */
    default long getLongForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String valString = getStringForUser(name, userHandle);
        try {
            return Long.parseLong(valString);
        } catch (NumberFormatException e) {
            throw new Settings.SettingNotFoundException(name);
        }
    }

    /** See {@link Settings.Secure#putLong(ContentResolver, String, long)} */
    default boolean putLong(String name, long value) {
        return putLongForUser(name, value, getUserId());
    }

    /** See {@link Settings.Secure#putLongForUser(ContentResolver, String, long, int)}  */
    default boolean putLongForUser(String name, long value, int userHandle) {
        return putStringForUser(name, Long.toString(value), userHandle);
    }

    /** See {@link Settings.Secure#getFloat(ContentResolver, String, float)} */
    default float getFloat(String name, float def) {
        return getFloatForUser(name, def, getUserId());
    }

    /** See {@link Settings.Secure#getFloatForUser(ContentResolver, String, int)} */
    default float getFloatForUser(String name, float def, int userHandle) {
        String v = getStringForUser(name, userHandle);
        try {
            return v != null ? Float.parseFloat(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }


    /** See {@link Settings.Secure#getFloat(ContentResolver, String)}  */
    default float getFloat(String name) throws Settings.SettingNotFoundException {
        return getFloatForUser(name, getUserId());
    }

    /** See {@link Settings.Secure#getFloatForUser(ContentResolver, String, int)}   */
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

    /** See {@link Settings.Secure#putFloat(ContentResolver, String, float)} */
    default boolean putFloat(String name, float value) {
        return putFloatForUser(name, value, getUserId());
    }

    /** See {@link Settings.Secure#putFloatForUser(ContentResolver, String, float, int)} */
    default boolean putFloatForUser(String name, float value, int userHandle) {
        return putStringForUser(name, Float.toString(value), userHandle);
    }

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
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.
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
                uri, notifyForDescendants, settingsObserver, userHandle);
    }

    /** See {@link ContentResolver#unregisterContentObserver(ContentObserver)}. */
    default void unregisterContentObserver(ContentObserver settingsObserver) {
        getContentResolver().unregisterContentObserver(settingsObserver);
    }
}
