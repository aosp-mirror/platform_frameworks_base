/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testutils;

import android.annotation.NonNull;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.util.Preconditions;
import com.android.server.utils.DeviceConfigInterface;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class FakeDeviceConfigInterface implements DeviceConfigInterface {

    private Map<String, String> mProperties = new HashMap<>();
    private ArrayMap<DeviceConfig.OnPropertiesChangedListener, Pair<String, Executor>> mListeners =
            new ArrayMap<>();

    public void clearProperties() {
        mProperties.clear();
    }

    public void putProperty(String namespace, String key, String value) {
        mProperties.put(createCompositeName(namespace, key), value);
    }

    public void putPropertyAndNotify(String namespace, String key, String value) {
        putProperty(namespace, key, value);
        DeviceConfig.Properties properties = makeProperties(namespace, key, value);
        CountDownLatch latch = new CountDownLatch(mListeners.size());
        for (int i = 0; i < mListeners.size(); i++) {
            if (namespace.equals(mListeners.valueAt(i).first)) {
                final int j = i;
                mListeners.valueAt(i).second.execute(
                        () -> {
                            mListeners.keyAt(j).onPropertiesChanged(properties);
                            latch.countDown();
                        });
            } else {
                latch.countDown();
            }
        }
        boolean success;
        try {
            success = latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            success = false;
        }
        if (!success) {
            throw new RuntimeException("Failed to notify all listeners in time.");
        }
    }

    private DeviceConfig.Properties makeProperties(String namespace, String key, String value) {
        try {
            final Constructor<DeviceConfig.Properties> ctor =
                    DeviceConfig.Properties.class.getDeclaredConstructor(String.class, Map.class);
            ctor.setAccessible(true);
            final HashMap<String, String> keyValueMap = new HashMap<>();
            keyValueMap.put(key, value);
            return ctor.newInstance(namespace, keyValueMap);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getProperty(String namespace, String name) {
        return mProperties.get(createCompositeName(namespace, name));
    }

    @Override
    public String getString(String namespace, String name, String defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? value : defaultValue;
    }

    @Override
    public int getInt(String namespace, String name, int defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public long getLong(String namespace, String name, long defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public float getFloat(String namespace, String name, float defaultValue) {
        String value = getProperty(namespace, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String namespace, String name, boolean defaultValue) {
        String value = getProperty(namespace, name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    @Override
    public void addOnPropertiesChangedListener(String namespace, Executor executor,
            DeviceConfig.OnPropertiesChangedListener listener) {
        Pair<String, Executor> oldNamespace = mListeners.get(listener);
        if (oldNamespace == null) {
            // Brand new listener, add it to the list.
            mListeners.put(listener, new Pair<>(namespace, executor));
        } else if (namespace.equals(oldNamespace.first)) {
            // Listener is already registered for this namespace, update executor just in case.
            mListeners.put(listener, new Pair<>(namespace, executor));
        } else {
            // DeviceConfig allows re-registering listeners for different namespaces, but that
            // silently unregisters the prior namespace. This likely isn't something the caller
            // intended.
            throw new IllegalStateException("Listener " + listener + " already registered. This"
                    + "is technically allowed by DeviceConfig, but likely indicates a logic "
                    + "error.");
        }
    }

    @Override
    public void removeOnPropertiesChangedListener(
            DeviceConfig.OnPropertiesChangedListener listener) {
        mListeners.remove(listener);
    }

    private static String createCompositeName(@NonNull String namespace, @NonNull String name) {
        Preconditions.checkNotNull(namespace);
        Preconditions.checkNotNull(name);
        return namespace + "/" + name;
    }
}
