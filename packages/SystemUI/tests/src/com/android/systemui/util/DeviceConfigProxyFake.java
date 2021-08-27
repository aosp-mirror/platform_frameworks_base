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

package com.android.systemui.util;

import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * A Fake of {@link DeviceConfigProxy} useful for testing.
 *
 * No properties are set by default. No calls to {@link DeviceConfig} are made. Be sure to set any
 * properties you rely on ahead of time in your test.
 */
public class DeviceConfigProxyFake extends DeviceConfigProxy {

    private List<Pair<Executor, OnPropertiesChangedListener>> mListeners = new ArrayList<>();
    private Map<String, Map<String, String>> mDefaultProperties = new HashMap<>();
    private Map<String, Map<String, String>> mProperties = new HashMap<>();

    public DeviceConfigProxyFake() {
    }

    @Override
    public void addOnPropertiesChangedListener(
            String namespace, Executor executor,
            OnPropertiesChangedListener onPropertiesChangedListener) {
        mListeners.add(Pair.create(executor, onPropertiesChangedListener));
    }

    @Override
    public void removeOnPropertiesChangedListener(
            OnPropertiesChangedListener onPropertiesChangedListener) {
        mListeners.removeIf(listener -> {
            if (listener == null) {
                return false;
            }
            return listener.second.equals(onPropertiesChangedListener);
        });
    }

    @Override
    public boolean setProperty(String namespace, String name, String value, boolean makeDefault) {
        setPropertyInternal(namespace, name, value, mProperties);
        if (makeDefault) {
            setPropertyInternal(namespace, name, value, mDefaultProperties);
        }

        for (Pair<Executor, OnPropertiesChangedListener> listener : mListeners) {
            Properties.Builder propBuilder = new Properties.Builder(namespace);
            propBuilder.setString(name, value);
            listener.first.execute(() -> listener.second.onPropertiesChanged(propBuilder.build()));
        }
        return true;
    }

    private void setPropertyInternal(String namespace, String name, String value,
            Map<String, Map<String, String>> properties) {
        properties.putIfAbsent(namespace, new HashMap<>());
        properties.get(namespace).put(name, value);
    }

    @Override
    public void enforceReadPermission(Context context, String namespace) {
        // no-op
    }

    private Properties propsForNamespaceAndName(String namespace, String name) {
        if (mProperties.containsKey(namespace) && mProperties.get(namespace).containsKey(name)) {
            return new Properties.Builder(namespace)
                    .setString(name, mProperties.get(namespace).get(name)).build();
        }
        if (mDefaultProperties.containsKey(namespace)) {
            return new Properties.Builder(namespace)
                    .setString(name, mDefaultProperties.get(namespace).get(name)).build();
        }

        return null;
    }

    @Override
    public boolean getBoolean(String namespace, String name, boolean defaultValue) {
        Properties props = propsForNamespaceAndName(namespace, name);
        if (props != null) {
            return props.getBoolean(name, defaultValue);
        }

        return defaultValue;
    }

    @Override
    public int getInt(String namespace, String name, int defaultValue) {
        Properties props = propsForNamespaceAndName(namespace, name);
        if (props != null) {
            return props.getInt(name, defaultValue);
        }

        return defaultValue;
    }

    @Override
    public long getLong(String namespace, String name, long defaultValue) {
        Properties props = propsForNamespaceAndName(namespace, name);
        if (props != null) {
            return props.getLong(name, defaultValue);
        }

        return defaultValue;
    }

    @Override
    public String getProperty(String namespace, String name) {
        return getString(namespace, name, null);
    }

    @Override
    public String getString(String namespace, String name, String defaultValue) {
        Properties props = propsForNamespaceAndName(namespace, name);
        if (props != null) {
            return props.getString(name, defaultValue);
        }

        return defaultValue;
    }

    @Override
    public void resetToDefaults(int resetMode, String namespace) {
        if (mProperties.containsKey(namespace)) {
            mProperties.get(namespace).clear();
        }
    }
}
