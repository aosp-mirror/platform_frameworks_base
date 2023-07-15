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

import android.database.Cursor;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent storage for the {@link FeatureFlagsService}.
 *
 * The implementation stores data in Settings.<store> (generally {@link Settings.Global}
 * is expected).
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class FlagOverrideStore {
    private static final String KEYNAME_PREFIX = "flag|";
    private static final String NAMESPACE_NAME_SEPARATOR = ".";

    private final SettingsProxy mSettingsProxy;

    private FlagChangeCallback mCallback;

    FlagOverrideStore(SettingsProxy settingsProxy) {
        mSettingsProxy = settingsProxy;
    }

    void setChangeCallback(FlagChangeCallback callback) {
        mCallback = callback;
    }

    /** Returns true if a non-null value is in the store. */
    boolean contains(String namespace, String name) {
        return get(namespace, name) != null;
    }

    /** Put a value in the store. */
    @VisibleForTesting
    public void set(String namespace, String name, String value) {
        mSettingsProxy.putString(getPropName(namespace, name), value);
        mCallback.onFlagChanged(namespace, name, value);
    }

    /** Read a value out of the store. */
    @VisibleForTesting
    public String get(String namespace, String name) {
        return mSettingsProxy.getString(getPropName(namespace, name));
    }

    /** Erase a value from the store. */
    @VisibleForTesting
    public void erase(String namespace, String name) {
        set(namespace, name, null);
    }

    Map<String, Map<String, String>> getFlags() {
        return getFlagsForNamespace(null);
    }

    Map<String, Map<String, String>> getFlagsForNamespace(String namespace) {
        Cursor c = mSettingsProxy.getContentResolver().query(
                Settings.Global.CONTENT_URI,
                new String[]{Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE},
                null, // Doesn't support a "LIKE" query
                null,
                null
        );

        if (c == null) {
            return Map.of();
        }
        int keynamePrefixLength = KEYNAME_PREFIX.length();
        Map<String, Map<String, String>> results = new HashMap<>();
        while (c.moveToNext()) {
            String key = c.getString(0);
            if (!key.startsWith(KEYNAME_PREFIX)
                    || key.indexOf(NAMESPACE_NAME_SEPARATOR, keynamePrefixLength) < 0) {
                continue;
            }
            String value = c.getString(1);
            if (value == null || value.isEmpty()) {
                continue;
            }
            String ns = key.substring(keynamePrefixLength, key.indexOf(NAMESPACE_NAME_SEPARATOR));
            if (namespace != null && !namespace.equals(ns)) {
                continue;
            }
            String name = key.substring(key.indexOf(NAMESPACE_NAME_SEPARATOR) + 1);
            results.putIfAbsent(ns, new HashMap<>());
            results.get(ns).put(name, value);
        }
        c.close();
        return results;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static String getPropName(String namespace, String name) {
        return KEYNAME_PREFIX + namespace + NAMESPACE_NAME_SEPARATOR + name;
    }

    interface FlagChangeCallback {
        void onFlagChanged(String namespace, String name, String value);
    }
}
