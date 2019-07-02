/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.preference;

import android.annotation.Nullable;

import java.util.Set;

/**
 * A data store interface to be implemented and provided to the Preferences framework. This can be
 * used to replace the default {@link android.content.SharedPreferences}, if needed.
 *
 * <p>In most cases you want to use {@link android.content.SharedPreferences} as it is automatically
 * backed up and migrated to new devices. However, providing custom data store to preferences can be
 * useful if your app stores its preferences in a local db, cloud or they are device specific like
 * "Developer settings". It might be also useful when you want to use the preferences UI but
 * the data are not supposed to be stored at all because they are valid per session only.
 *
 * <p>Once a put method is called it is full responsibility of the data store implementation to
 * safely store the given values. Time expensive operations need to be done in the background to
 * prevent from blocking the UI. You also need to have a plan on how to serialize the data in case
 * the activity holding this object gets destroyed.
 *
 * <p>By default, all "put" methods throw {@link UnsupportedOperationException}.
 *
 * @see Preference#setPreferenceDataStore(PreferenceDataStore)
 * @see PreferenceManager#setPreferenceDataStore(PreferenceDataStore)
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public interface PreferenceDataStore {

    /**
     * Set a String value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     * @see #getString(String, String)
     */
    default void putString(String key, @Nullable String value) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Set a set of String value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param values The set of new values for the preference.
     * @see #getStringSet(String, Set)
     */
    default void putStringSet(String key, @Nullable Set<String> values) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Set an int value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     * @see #getInt(String, int)
     */
    default void putInt(String key, int value) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Set a long value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     * @see #getLong(String, long)
     */
    default void putLong(String key, long value) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Set a float value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     * @see #getFloat(String, float)
     */
    default void putFloat(String key, float value) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Set a boolean value to the data store.
     *
     * <p>Once the value is set the data store is responsible for holding it.
     *
     * @param key The name of the preference to modify.
     * @param value The new value for the preference.
     * @see #getBoolean(String, boolean)
     */
    default void putBoolean(String key, boolean value) {
        throw new UnsupportedOperationException("Not implemented on this data store");
    }

    /**
     * Retrieve a String value from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValue Value to return if this preference does not exist.
     * @see #putString(String, String)
     */
    @Nullable
    default String getString(String key, @Nullable String defValue) {
        return defValue;
    }

    /**
     * Retrieve a set of String values from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValues Values to return if this preference does not exist.
     * @see #putStringSet(String, Set)
     */
    @Nullable
    default Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        return defValues;
    }

    /**
     * Retrieve an int value from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValue Value to return if this preference does not exist.
     * @see #putInt(String, int)
     */
    default int getInt(String key, int defValue) {
        return defValue;
    }

    /**
     * Retrieve a long value from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValue Value to return if this preference does not exist.
     * @see #putLong(String, long)
     */
    default long getLong(String key, long defValue) {
        return defValue;
    }

    /**
     * Retrieve a float value from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValue Value to return if this preference does not exist.
     * @see #putFloat(String, float)
     */
    default float getFloat(String key, float defValue) {
        return defValue;
    }

    /**
     * Retrieve a boolean value from the data store.
     *
     * @param key The name of the preference to retrieve.
     * @param defValue Value to return if this preference does not exist.
     * @see #getBoolean(String, boolean)
     */
    default boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }
}
