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
 * Used to interact with per-user Settings.Secure and Settings.System settings (but not
 * Settings.Global, since those do not vary per-user)
 * <p>
 * This interface can be implemented to give instance method (instead of static method) versions
 * of Settings.Secure and Settings.System. It can be injected into class constructors and then
 * faked or mocked as needed in tests.
 * <p>
 * You can ask for {@link SecureSettings} or {@link SystemSettings} to be injected as needed.
 * <p>
 * This class also provides {@link #registerContentObserver(String, ContentObserver)} methods,
 * normally found on {@link ContentResolver} instances, unifying setting related actions in one
 * place.
 */
public interface UserSettingsProxy extends SettingsProxy {

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

    @Override
    default void registerContentObserver(Uri uri, ContentObserver settingsObserver) {
        registerContentObserverForUser(uri, settingsObserver, getUserId());
    }

    /**
     * Convenience wrapper around
     * {@link ContentResolver#registerContentObserver(Uri, boolean, ContentObserver)}.'
     */
    @Override
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

    /**
     * Look up a name in the database.
     * @param name to look up in the table
     * @return the corresponding value, or null if not present
     */
    @Override
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

    @Override
    default boolean putString(String name, String value) {
        return putStringForUser(name, value, getUserId());
    }

    /** See {@link #putString(String, String)}. */
    boolean putStringForUser(String name, String value, int userHandle);

    /** See {@link #putString(String, String)}. */
    boolean putStringForUser(@NonNull String name, @Nullable String value, @Nullable String tag,
            boolean makeDefault, @UserIdInt int userHandle, boolean overrideableByRestore);

    @Override
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

    @Override
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

    @Override
    default boolean putInt(String name, int value) {
        return putIntForUser(name, value, getUserId());
    }

    /** See {@link #putInt(String, int)}. */
    default boolean putIntForUser(String name, int value, int userHandle) {
        return putStringForUser(name, Integer.toString(value), userHandle);
    }

    @Override
    default boolean getBool(String name, boolean def) {
        return getBoolForUser(name, def, getUserId());
    }

    /** See {@link #getBool(String, boolean)}. */
    default boolean getBoolForUser(String name, boolean def, int userHandle) {
        return getIntForUser(name, def ? 1 : 0, userHandle) != 0;
    }

    @Override
    default boolean getBool(String name) throws Settings.SettingNotFoundException {
        return getBoolForUser(name, getUserId());
    }

    /** See {@link #getBool(String)}. */
    default boolean getBoolForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        return getIntForUser(name, userHandle) != 0;
    }

    @Override
    default boolean putBool(String name, boolean value) {
        return putBoolForUser(name, value, getUserId());
    }

    /** See {@link #putBool(String, boolean)}. */
    default boolean putBoolForUser(String name, boolean value, int userHandle) {
        return putIntForUser(name, value ? 1 : 0, userHandle);
    }

    /** See {@link #getLong(String, long)}. */
    default long getLongForUser(String name, long def, int userHandle) {
        String valString = getStringForUser(name, userHandle);
        return SettingsProxy.parseLongOrUseDefault(valString, def);
    }

    /** See {@link #getLong(String)}. */
    default long getLongForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String valString = getStringForUser(name, userHandle);
        return SettingsProxy.parseLongOrThrow(name, valString);
    }

    /** See {@link #putLong(String, long)}. */
    default boolean putLongForUser(String name, long value, int userHandle) {
        return putStringForUser(name, Long.toString(value), userHandle);
    }

    /** See {@link #getFloat(String)}. */
    default float getFloatForUser(String name, float def, int userHandle) {
        String v = getStringForUser(name, userHandle);
        return SettingsProxy.parseFloat(v, def);
    }

    /** See {@link #getFloat(String, float)}. */
    default float getFloatForUser(String name, int userHandle)
            throws Settings.SettingNotFoundException {
        String v = getStringForUser(name, userHandle);
        return SettingsProxy.parseFloatOrThrow(name, v);
    }

    /** See {@link #putFloat(String, float)} */
    default boolean putFloatForUser(String name, float value, int userHandle) {
        return putStringForUser(name, Float.toString(value), userHandle);
    }
}
