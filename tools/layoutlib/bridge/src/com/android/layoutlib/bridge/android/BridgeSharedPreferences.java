/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/**
 * An empty shared preferences implementation which doesn't store anything. It always returns
 * null, 0 or false.
 */
public class BridgeSharedPreferences implements SharedPreferences {
    private Editor mEditor;

    @Override
    public Map<String, ?> getAll() {
        return null;
    }

    @Override
    public String getString(String key, String defValue) {
        return null;
    }

    @Override
    public Set<String> getStringSet(String key, Set<String> defValues) {
        return null;
    }

    @Override
    public int getInt(String key, int defValue) {
        return 0;
    }

    @Override
    public long getLong(String key, long defValue) {
        return 0;
    }

    @Override
    public float getFloat(String key, float defValue) {
        return 0;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return false;
    }

    @Override
    public boolean contains(String key) {
        return false;
    }

    @Override
    public Editor edit() {
        if (mEditor != null) {
            return mEditor;
        }
        mEditor = new Editor() {
            @Override
            public Editor putString(String key, String value) {
                return null;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                return null;
            }

            @Override
            public Editor putInt(String key, int value) {
                return null;
            }

            @Override
            public Editor putLong(String key, long value) {
                return null;
            }

            @Override
            public Editor putFloat(String key, float value) {
                return null;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                return null;
            }

            @Override
            public Editor remove(String key) {
                return null;
            }

            @Override
            public Editor clear() {
                return null;
            }

            @Override
            public boolean commit() {
                return false;
            }

            @Override
            public void apply() {
            }
        };
        return mEditor;
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
    }
}
