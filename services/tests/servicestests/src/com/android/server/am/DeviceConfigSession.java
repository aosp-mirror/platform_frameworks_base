/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.provider.DeviceConfig;

import com.android.compatibility.common.util.SystemUtil;
import com.android.internal.util.function.TriFunction;

/**
 * An utility class to set/restore the given device_config item.
 */
public class DeviceConfigSession<T> implements AutoCloseable {
    private final TriFunction<String, String, T, T> mGetter;

    private final String mNamespace;
    private final String mKey;
    private final T mInitialValue;
    private final T mDefaultValue;
    private boolean mHasInitalValue;

    DeviceConfigSession(String namespace, String key,
            TriFunction<String, String, T, T> getter, T defaultValue) {
        mNamespace = namespace;
        mKey = key;
        mGetter = getter;
        mDefaultValue = defaultValue;
        // Try {@DeviceConfig#getString} firstly since the DeviceConfig API doesn't
        // support "not found" exception.
        final String initialStringValue = DeviceConfig.getString(namespace, key, null);
        if (initialStringValue == null) {
            mHasInitalValue = false;
            mInitialValue = defaultValue;
        } else {
            mHasInitalValue = true;
            mInitialValue = getter.apply(namespace, key, defaultValue);
        }
    }

    public void set(final @NonNull T value) {
        DeviceConfig.setProperty(mNamespace, mKey,
                value == null ? null : value.toString(), false);
    }

    public T get() {
        return mGetter.apply(mNamespace, mKey, mDefaultValue);
    }

    @Override
    public void close() throws Exception {
        if (mHasInitalValue) {
            set(mInitialValue);
        } else {
            SystemUtil.runShellCommand("device_config delete " + mNamespace + " " + mKey);
        }
    }
}
