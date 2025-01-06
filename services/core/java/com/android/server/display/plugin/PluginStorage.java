/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.plugin;

import android.annotation.Nullable;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.tools.r8.keepanno.annotations.KeepForApi;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores values pushed by Plugins and forwards them to corresponding listener.
 */
public class PluginStorage {
    private static final String TAG = "PluginStorage";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<PluginType<?>, Object> mValues = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<PluginType<?>, ListenersContainer<?>> mListeners = new HashMap<>();
    @GuardedBy("mLock")
    private final PluginEventStorage mPluginEventStorage = new PluginEventStorage();

    /**
     * Updates value in storage and forwards it to corresponding listeners.
     * Should be called by OEM Plugin implementation in order to provide communicate with Framework
     */
    @KeepForApi
    public <T> void updateValue(PluginType<T> type, @Nullable T value) {
        Slog.d(TAG, "updateValue, type=" + type.mName + "; value=" + value);
        Set<PluginManager.PluginChangeListener<T>> localListeners;
        synchronized (mLock) {
            mValues.put(type, value);
            mPluginEventStorage.onValueUpdated(type);
            ListenersContainer<T> container = getListenersContainerForTypeLocked(type);
            localListeners = new LinkedHashSet<>(container.mListeners);
        }
        Slog.d(TAG, "updateValue, notifying listeners=" + localListeners);
        localListeners.forEach(l -> l.onChanged(value));
    }

    /**
     * Adds listener for PluginType. If storage already has value for this type, listener will
     * be notified immediately.
     */
    <T> void addListener(PluginType<T> type, PluginManager.PluginChangeListener<T> listener) {
        T value = null;
        synchronized (mLock) {
            ListenersContainer<T> container = getListenersContainerForTypeLocked(type);
            if (container.mListeners.add(listener)) {
                value = getValueForTypeLocked(type);
            }
        }
        if (value != null) {
            listener.onChanged(value);
        }
    }

    /**
     * Removes listener
     */
    <T> void removeListener(PluginType<T> type, PluginManager.PluginChangeListener<T> listener) {
        synchronized (mLock) {
            ListenersContainer<T> container = getListenersContainerForTypeLocked(type);
            container.mListeners.remove(listener);
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     */
    void dump(PrintWriter pw) {
        Map<PluginType<?>, Object> localValues;
        @SuppressWarnings("rawtypes")
        Map<PluginType, Set> localListeners = new HashMap<>();
        List<PluginEventStorage.TimeFrame> timeFrames;
        synchronized (mLock) {
            timeFrames = mPluginEventStorage.getTimeFrames();
            localValues = new HashMap<>(mValues);
            mListeners.forEach((type, container) -> localListeners.put(type, container.mListeners));
        }
        pw.println("PluginStorage:");
        pw.println("values=" + localValues);
        pw.println("listeners=" + localListeners);
        pw.println("PluginEventStorage:");
        for (PluginEventStorage.TimeFrame timeFrame: timeFrames) {
            timeFrame.dump(pw);
        }
    }

    @GuardedBy("mLock")
    @SuppressWarnings("unchecked")
    private <T> T getValueForTypeLocked(PluginType<T> type) {
        Object value = mValues.get(type);
        if (value == null) {
            return null;
        } else if (type.mType == value.getClass()) {
            return (T) value;
        } else {
            Slog.d(TAG, "getValueForType: unexpected value type=" + value.getClass().getName()
                    + ", expected=" + type.mType.getName());
            return null;
        }
    }

    @GuardedBy("mLock")
    @SuppressWarnings("unchecked")
    private <T> ListenersContainer<T> getListenersContainerForTypeLocked(PluginType<T> type) {
        ListenersContainer<?> container = mListeners.get(type);
        if (container == null) {
            ListenersContainer<T> lc = new ListenersContainer<>();
            mListeners.put(type, lc);
            return lc;
        } else {
            return (ListenersContainer<T>) container;
        }
    }

    private static final class ListenersContainer<T> {
        private final Set<PluginManager.PluginChangeListener<T>> mListeners = new LinkedHashSet<>();
    }
}
