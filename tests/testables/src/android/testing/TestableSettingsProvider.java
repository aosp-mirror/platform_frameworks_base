/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static org.junit.Assert.assertEquals;

import android.content.ContentProviderClient;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.util.Log;

import java.util.HashMap;

/**
 * Allows calls to android.provider.Settings to be tested easier.
 *
 * This provides a simple copy-on-write implementation of settings that gets cleared
 * at the end of each test.
 */
public class TestableSettingsProvider extends MockContentProvider {

    private static final String TAG = "TestableSettingsProvider";
    private static final boolean DEBUG = false;
    private static final String MY_UNIQUE_KEY = "Key_" + TestableSettingsProvider.class.getName();
    private static TestableSettingsProvider sInstance;

    private final ContentProviderClient mSettings;

    private final HashMap<String, String> mValues = new HashMap<>();

    private TestableSettingsProvider(ContentProviderClient settings) {
        mSettings = settings;
    }

    void clearValuesAndCheck(Context context) {
        int userId = UserHandle.myUserId();
        mValues.put(key("global", MY_UNIQUE_KEY, userId), MY_UNIQUE_KEY);
        mValues.put(key("secure", MY_UNIQUE_KEY, userId), MY_UNIQUE_KEY);
        mValues.put(key("system", MY_UNIQUE_KEY, userId), MY_UNIQUE_KEY);

        Settings.Global.clearProviderForTest();
        Settings.Secure.clearProviderForTest();
        Settings.System.clearProviderForTest();
        // Verify that if any test is using TestableContext, they all have the correct settings
        // provider.
        assertEquals("Incorrect settings provider, test using incorrect Context?", MY_UNIQUE_KEY,
                Settings.Global.getString(context.getContentResolver(), MY_UNIQUE_KEY));
        assertEquals("Incorrect settings provider, test using incorrect Context?", MY_UNIQUE_KEY,
                Settings.Secure.getString(context.getContentResolver(), MY_UNIQUE_KEY));
        assertEquals("Incorrect settings provider, test using incorrect Context?", MY_UNIQUE_KEY,
                Settings.System.getString(context.getContentResolver(), MY_UNIQUE_KEY));

        mValues.clear();
    }

    public Bundle call(String method, String arg, Bundle extras) {
        // Methods are "GET_system", "GET_global", "PUT_secure", etc.
        final int userId = extras.getInt(Settings.CALL_METHOD_USER_KEY, UserHandle.myUserId());
        final String[] commands = method.split("_", 2);
        final String op = commands[0];
        final String table = commands[1];

            String k = key(table, arg, userId);
            String value;
            Bundle out = new Bundle();
            switch (op) {
                case "GET":
                    if (mValues.containsKey(k)) {
                        value = mValues.get(k);
                        if (value != null) {
                            out.putString(Settings.NameValueTable.VALUE, value);
                        }
                    } else {
                        // Fall through to real settings.
                        try {
                            if (DEBUG) Log.d(TAG, "Falling through to real settings " + method);
                            // TODO: Add our own version of caching to handle this.
                            Bundle call = mSettings.call(method, arg, extras);
                            call.remove(Settings.CALL_METHOD_TRACK_GENERATION_KEY);
                            return call;
                        } catch (RemoteException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    break;
                case "PUT":
                    value = extras.getString(Settings.NameValueTable.VALUE, null);
                    mValues.put(k, value);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown command " + method);
            }
            return out;
    }

    private static String key(String table, String key, int userId) {
        if ("global".equals(table)) {
            return table + "_" + key;
        } else {
            return table + "_" + userId + "_" + key;
        }

    }

    /**
     * Since the settings provider is cached inside android.provider.Settings, this must
     * be gotten statically to ensure there is only one instance referenced.
     */
    static TestableSettingsProvider getFakeSettingsProvider(ContentProviderClient settings) {
        if (sInstance == null) {
            sInstance = new TestableSettingsProvider(settings);
        }
        return sInstance;
    }
}
