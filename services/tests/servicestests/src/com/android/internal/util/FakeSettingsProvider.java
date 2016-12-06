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
 * limitations under the License.
 */

package com.android.internal.util;

import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.util.Log;

import java.util.HashMap;

/**
 * Fake for system settings.
 *
 * To use, ensure that the Context used by the test code returns a ContentResolver that uses this
 * provider for the Settings authority:
 *
 *   class MyTestContext extends MockContext {
 *       ...
 *       private final MockContentResolver mContentResolver;
 *       public MyTestContext(...) {
 *           ...
 *           mContentResolver = new MockContentResolver();
 *           mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
 *       }
 *       ...
 *       @Override
 *       public ContentResolver getContentResolver() {
 *           return mContentResolver;
 *       }
 *
 * As long as the code under test is using the test Context, the actual code under test does not
 * need to be modified, and can access Settings using the normal static methods:
 *
 *   Settings.Global.getInt(cr, "my_setting", 0);  // Returns 0.
 *   Settings.Global.putInt(cr, "my_setting", 5);
 *   Settings.Global.getInt(cr, "my_setting", 0);  // Returns 5.
 *
 * Note that this class cannot be used in the same process as real settings. This is because it
 * works by passing an alternate ContentResolver to Settings operations. Unfortunately, the Settings
 * class only fetches the content provider from the passed-in ContentResolver the first time it's
 * used, and after that stores it in a per-process static.
 *
 * TODO: evaluate implementing settings change notifications. This would require:
 *
 * 1. Making ContentResolver#registerContentObserver non-final and overriding it in
 *    MockContentResolver.
 * 2. Making FakeSettingsProvider take a ContentResolver argument.
 * 3. Calling ContentResolver#notifyChange(getUriFor(table, arg), ...) on every settings change.
 */
public class FakeSettingsProvider extends MockContentProvider {

    private static final String TAG = FakeSettingsProvider.class.getSimpleName();
    private static final boolean DBG = false;
    private static final String[] TABLES = { "system", "secure", "global" };

    private final HashMap<String, HashMap<String, String>> mTables = new HashMap<>();

    public FakeSettingsProvider() {
        for (int i = 0; i < TABLES.length; i++) {
            mTables.put(TABLES[i], new HashMap<String, String>());
        }
    }

    private Uri getUriFor(String table, String key) {
        switch (table) {
            case "system":
                return Settings.System.getUriFor(key);
            case "secure":
                return Settings.Secure.getUriFor(key);
            case "global":
                return Settings.Global.getUriFor(key);
            default:
                throw new UnsupportedOperationException("Unknown settings table " + table);
        }
    }

    public Bundle call(String method, String arg, Bundle extras) {
        // Methods are "GET_system", "GET_global", "PUT_secure", etc.
        String[] commands = method.split("_", 2);
        String op = commands[0];
        String table = commands[1];

        Bundle out = new Bundle();
        String value;
        switch (op) {
            case "GET":
                value = mTables.get(table).get(arg);
                if (value != null) {
                    if (DBG) {
                        Log.d(TAG, String.format("Returning fake setting %s.%s = %s",
                                table, arg, value));
                    }
                    out.putString(Settings.NameValueTable.VALUE, value);
                }
                break;
            case "PUT":
                value = extras.getString(Settings.NameValueTable.VALUE, null);
                if (DBG) {
                    Log.d(TAG, String.format("Inserting fake setting %s.%s = %s",
                            table, arg, value));
                }
                if (value != null) {
                    mTables.get(table).put(arg, value);
                } else {
                    mTables.get(table).remove(arg);
                }
                break;
            default:
                throw new UnsupportedOperationException("Unknown command " + method);
        }

        return out;
    }
}
