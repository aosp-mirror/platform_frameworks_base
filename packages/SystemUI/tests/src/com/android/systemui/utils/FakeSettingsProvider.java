/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.utils;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.test.mock.MockContentProvider;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.utils.FakeSettingsProvider.SettingOverrider.Builder;

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
 *         .addSetting("secure", Secure.USER_SETUP_COMPLETE, "0")
 *         .build();
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
public class FakeSettingsProvider extends MockContentProvider {

    private static final String TAG = "FakeSettingsProvider";
    private static final boolean DEBUG = false;

    // Number of times to try to acquire a setting if in use.
    private static final int MAX_TRIES = 10;
    // Time to wait for each setting.  WAIT_TIMEOUT * MAX_TRIES will be the maximum wait time
    // for a setting.
    private static final long WAIT_TIMEOUT = 1000;

    private final Map<String, SettingOverrider> mOverrideMap = new ArrayMap<>();
    private final Map<SysuiTestCase, List<SettingOverrider>> mOwners = new ArrayMap<>();

    private static FakeSettingsProvider sInstance;
    private final ContentProviderClient mSettings;
    private final ContentResolver mResolver;

    private FakeSettingsProvider(ContentProviderClient settings, ContentResolver resolver) {
        mSettings = settings;
        mResolver = resolver;
    }

    public Builder acquireOverridesBuilder(SysuiTestCase test) {
        return new Builder(this, test);
    }

    public void clearOverrides(SysuiTestCase test) {
        List<SettingOverrider> overrides = mOwners.remove(test);
        if (overrides != null) {
            overrides.forEach(override -> override.ensureReleased());
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

    private void acquireSettings(SettingOverrider overridder, Set<String> keys,
            SysuiTestCase owner) throws AcquireTimeoutException {
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
                if (checkKeys(keys, false)) break;
                try {
                    if (DEBUG) Log.d(TAG, "Waiting for contention to finish");
                    mOverrideMap.wait(WAIT_TIMEOUT);
                } catch (InterruptedException e) {
                }
            }
            checkKeys(keys, true);
            for (String key : keys) {
                if (DEBUG) Log.d(TAG, "Acquiring " + key);
                mOverrideMap.put(key, overridder);
            }
        }
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

    @VisibleForTesting
    public Object getLock() {
        return mOverrideMap;
    }

    private boolean checkKeys(Set<String> keys, boolean shouldThrow)
            throws AcquireTimeoutException {
        for (String key : keys) {
            if (mOverrideMap.containsKey(key)) {
                if (shouldThrow) {
                    throw new AcquireTimeoutException("Could not acquire " + key);
                }
                return false;
            }
        }
        return true;
    }

    public static class SettingOverrider {
        private final Set<String> mValidKeys;
        private final Map<String, String> mValueMap = new ArrayMap<>();
        private final FakeSettingsProvider mProvider;
        private boolean mReleased;

        private SettingOverrider(Set<String> keys, FakeSettingsProvider provider) {
            mValidKeys = new ArraySet<>(keys);
            mProvider = provider;
        }

        private void ensureReleased() {
            if (!mReleased) {
                release();
            }
        }

        public void release() {
            mProvider.releaseSettings(mValidKeys);
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
            private final FakeSettingsProvider mProvider;
            private final SysuiTestCase mOwner;
            private Set<String> mKeys = new ArraySet<>();
            private Map<String, String> mValues = new ArrayMap<>();

            private Builder(FakeSettingsProvider provider, SysuiTestCase test) {
                mProvider = provider;
                mOwner = test;
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
                mProvider.acquireSettings(overrider, mKeys, mOwner);
                mValues.forEach((key, value) -> overrider.putDirect(key, value));
                return overrider;
            }
        }
    }

    public static class AcquireTimeoutException extends Exception {
        public AcquireTimeoutException(String str) {
            super(str);
        }
    }

    private static String key(String table, String key) {
        return table + "_" + key;
    }

    /**
     * Since the settings provider is cached inside android.provider.Settings, this must
     * be gotten statically to ensure there is only one instance referenced.
     * @param settings
     */
    public static FakeSettingsProvider getFakeSettingsProvider(ContentProviderClient settings,
            ContentResolver resolver) {
        if (sInstance == null) {
            sInstance = new FakeSettingsProvider(settings, resolver);
        }
        return sInstance;
    }
}
