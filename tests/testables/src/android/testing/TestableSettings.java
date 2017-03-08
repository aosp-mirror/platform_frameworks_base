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

import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.test.mock.MockContentProvider;
import android.testing.TestableSettings.SettingOverrider.Builder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allows calls to android.provider.Settings to be tested easier.  A SettingOverride
 * can be acquired and a set of specific settings can be set to a value (and not changed
 * in the system when set), so that they can be tested without breaking the test device.
 * <p>
 * To use, in the before method acquire the override add all settings that will affect if
 * your test passes or not.
 *
 * <pre class="prettyprint">
 * {@literal
 * mSettingOverride = mTestableContext.getSettingsProvider().acquireOverridesBuilder()
 * .addSetting("secure", Secure.USER_SETUP_COMPLETE, "0")
 * .build();
 * }
 * </pre>
 *
 * Then in the after free up the settings.
 *
 * <pre class="prettyprint">
 * {@literal
 * mSettingOverride.release();
 * }
 * </pre>
 */
public class TestableSettings {

    private static final String TAG = "TestableSettings";
    private static final boolean DEBUG = false;

    // Number of times to try to acquire a setting if in use.
    private static final int MAX_TRIES = 10;
    // Time to wait for each setting.  WAIT_TIMEOUT * MAX_TRIES will be the maximum wait time
    // for a setting.
    private static final long WAIT_TIMEOUT = 1000;

    private static TestableSettingsProvider sInstance;

    private final TestableSettingsProvider mProvider;

    private TestableSettings(TestableSettingsProvider provider) {
        mProvider = provider;
    }

    public Builder acquireOverridesBuilder() {
        return new Builder(this);
    }

    public void clearOverrides() {
        List<SettingOverrider> overrides = mProvider.mOwners.remove(this);
        if (overrides != null) {
            overrides.forEach(override -> override.ensureReleased());
        }
    }

    private void acquireSettings(SettingOverrider overridder, Set<String> keys)
            throws AcquireTimeoutException {
        mProvider.acquireSettings(overridder, keys, this);
    }

    ContentProvider getProvider() {
        return mProvider;
    }

    @VisibleForTesting
    Object getLock() {
        return mProvider.mOverrideMap;
    }

    public static class SettingOverrider {
        private final Set<String> mValidKeys;
        private final Map<String, String> mValueMap = new ArrayMap<>();
        private final TestableSettings mSettings;
        private boolean mReleased;
        public Throwable mObtain;

        private SettingOverrider(Set<String> keys, TestableSettings provider) {
            mValidKeys = new ArraySet<>(keys);
            mSettings = provider;
        }

        private void ensureReleased() {
            if (!mReleased) {
                release();
            }
        }

        public void release() {
            mSettings.mProvider.releaseSettings(mValidKeys);
            mReleased = true;
        }

        private void putDirect(String key, String value) {
            mValueMap.put(key, value);
        }

        public void put(String table, String key, String value) {
            if (!mValidKeys.contains(key(table, key))) {
                throw new IllegalArgumentException("Key " + table + " " + key
                        + " not acquired for this overrider");
            }
            mValueMap.put(key(table, key), value);
        }

        public void remove(String table, String key) {
            if (!mValidKeys.contains(key(table, key))) {
                throw new IllegalArgumentException("Key " + table + " " + key
                        + " not acquired for this overrider");
            }
            mValueMap.remove(key(table, key));
        }

        public String get(String table, String key) {
            if (!mValidKeys.contains(key(table, key))) {
                throw new IllegalArgumentException("Key " + table + " " + key
                        + " not acquired for this overrider");
            }
            Log.d(TAG, "Get " + table + " " + key + " " + mValueMap.get(key(table, key)));
            return mValueMap.get(key(table, key));
        }

        public static class Builder {
            private final TestableSettings mProvider;
            private Set<String> mKeys = new ArraySet<>();
            private Map<String, String> mValues = new ArrayMap<>();

            private Builder(TestableSettings provider) {
                mProvider = provider;
            }

            public Builder addSetting(String table, String key) {
                mKeys.add(key(table, key));
                return this;
            }

            public Builder addSetting(String table, String key, String value) {
                addSetting(table, key);
                mValues.put(key(table, key), value);
                return this;
            }

            public SettingOverrider build() throws AcquireTimeoutException {
                SettingOverrider overrider = new SettingOverrider(mKeys, mProvider);
                mProvider.acquireSettings(overrider, mKeys);
                mValues.forEach((key, value) -> overrider.putDirect(key, value));
                return overrider;
            }
        }
    }

    private static class TestableSettingsProvider extends MockContentProvider {

        private final Map<String, SettingOverrider> mOverrideMap = new ArrayMap<>();
        private final Map<Object, List<SettingOverrider>> mOwners = new ArrayMap<>();

        private final ContentProviderClient mSettings;
        private final ContentResolver mResolver;

        public TestableSettingsProvider(ContentProviderClient settings, ContentResolver resolver) {
            mSettings = settings;
            mResolver = resolver;
        }

        private void releaseSettings(Set<String> keys) {
            synchronized (mOverrideMap) {
                for (String key : keys) {
                    if (DEBUG) Log.d(TAG, "Releasing " + key);
                    mOverrideMap.remove(key);
                }
                if (DEBUG) Log.d(TAG, "Notifying");
                mOverrideMap.notify();
            }
        }

        private boolean checkKeysLocked(Set<String> keys, boolean shouldThrow)
                throws AcquireTimeoutException {
            for (String key : keys) {
                if (mOverrideMap.containsKey(key)) {
                    if (shouldThrow) {
                        if (DEBUG) Log.e(TAG, "Lock obtained at",
                                mOverrideMap.get(key).mObtain);
                        throw new AcquireTimeoutException("Could not acquire " + key,
                                mOverrideMap.get(key).mObtain);
                    }
                    return false;
                }
            }
            return true;
        }

        private void acquireSettings(SettingOverrider overridder, Set<String> keys,
                Object owner) throws AcquireTimeoutException {
            synchronized (mOwners) {
                List<SettingOverrider> list = mOwners.get(owner);
                if (list == null) {
                    list = new ArrayList<>();
                    mOwners.put(owner, list);
                }
                list.add(overridder);
            }
            synchronized (mOverrideMap) {
                for (int i = 0; i < MAX_TRIES; i++) {
                    if (checkKeysLocked(keys, false)) break;
                    try {
                        if (DEBUG) Log.d(TAG, "Waiting for contention to finish");
                        mOverrideMap.wait(WAIT_TIMEOUT);
                    } catch (InterruptedException e) {
                    }
                }
                overridder.mObtain = new Throwable();
                checkKeysLocked(keys, true);
                for (String key : keys) {
                    if (DEBUG) Log.d(TAG, "Acquiring " + key);
                    mOverrideMap.put(key, overridder);
                }
            }
        }

        public Bundle call(String method, String arg, Bundle extras) {
            // Methods are "GET_system", "GET_global", "PUT_secure", etc.
            final String[] commands = method.split("_", 2);
            final String op = commands[0];
            final String table = commands[1];

            synchronized (mOverrideMap) {
                SettingOverrider overrider = mOverrideMap.get(key(table, arg));
                if (overrider == null) {
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
                String value;
                Bundle out = new Bundle();
                switch (op) {
                    case "GET":
                        value = overrider.get(table, arg);
                        if (value != null) {
                            out.putString(Settings.NameValueTable.VALUE, value);
                        }
                        break;
                    case "PUT":
                        value = extras.getString(Settings.NameValueTable.VALUE, null);
                        if (value != null) {
                            overrider.put(table, arg, value);
                        } else {
                            overrider.remove(table, arg);
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException("Unknown command " + method);
                }
                return out;
            }
        }
    }

    public static class AcquireTimeoutException extends Exception {
        public AcquireTimeoutException(String str, Throwable cause) {
            super(str, cause);
        }
    }

    private static String key(String table, String key) {
        return table + "_" + key;
    }

    /**
     * Since the settings provider is cached inside android.provider.Settings, this must
     * be gotten statically to ensure there is only one instance referenced.
     */
    public static TestableSettings getFakeSettingsProvider(ContentProviderClient settings,
            ContentResolver resolver) {
        if (sInstance == null) {
            sInstance = new TestableSettingsProvider(settings, resolver);
        }
        return new TestableSettings(sInstance);
    }
}
