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
import com.android.internal.annotations.VisibleForTesting;
import com.android.tools.r8.keepanno.annotations.KeepForApi;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores values pushed by Plugins and forwards them to corresponding listener.
 */
public class PluginStorage {
    private static final String TAG = "PluginStorage";

    // Special ID used to indicate that given value is to be applied globally, rather than to a
    // specific display. If both GLOBAL and specific display values are present - specific display
    // value is selected.
    @VisibleForTesting
    static final String GLOBAL_ID = "GLOBAL";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<PluginType<?>, ValuesContainer<?>> mValues = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<PluginType<?>, ListenersContainer<?>> mListeners = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<String, PluginEventStorage> mPluginEventStorages = new HashMap<>();

    /**
     * Updates value in storage and forwards it to corresponding listeners for all displays
     * that does not have display specific value.
     * Should be called by OEM Plugin implementation in order to communicate with Framework
     */
    @KeepForApi
    public <T> void updateGlobalValue(PluginType<T> type, @Nullable T value) {
        updateValue(type, GLOBAL_ID, value);
    }

    private final Set<PluginType<?>> mEnabledTypes;

    PluginStorage(Set<PluginType<?>> enabledTypes) {
        mEnabledTypes = Collections.unmodifiableSet(enabledTypes);
    }

    /**
     * Updates value in storage and forwards it to corresponding listeners for specific display.
     * Should be called by OEM Plugin implementation in order to communicate with Framework
     * @param type - plugin type, that need to be updated
     * @param uniqueDisplayId - uniqueDisplayId that this type/value should be applied to
     * @param value - plugin value for particular type and display
     */
    @KeepForApi
    public <T> void updateValue(PluginType<T> type, String uniqueDisplayId, @Nullable T value) {
        if (isTypeDisabled(type)) {
            Slog.d(TAG, "updateValue ignored for disabled type=" + type.mName);
            return;
        }
        Slog.d(TAG, "updateValue, type=" + type.mName + "; value=" + value
                + "; displayId=" + uniqueDisplayId);
        Set<PluginManager.PluginChangeListener<T>> localListeners;
        T valueToNotify;
        synchronized (mLock) {
            ValuesContainer<T> valuesByType = getValuesContainerLocked(type);
            valuesByType.updateValueLocked(uniqueDisplayId, value);
            // if value was set to null, we might need to notify with GLOBAL value instead
            valueToNotify = valuesByType.getValueLocked(uniqueDisplayId);

            PluginEventStorage storage = mPluginEventStorages.computeIfAbsent(uniqueDisplayId,
                    d -> new PluginEventStorage());
            storage.onValueUpdated(type);

            localListeners =  getListenersForUpdateLocked(type, uniqueDisplayId);
        }
        Slog.d(TAG, "updateValue, notifying listeners=" + localListeners);
        localListeners.forEach(l -> l.onChanged(valueToNotify));
    }

    @GuardedBy("mLock")
    private <T> Set<PluginManager.PluginChangeListener<T>> getListenersForUpdateLocked(
            PluginType<T> type, String uniqueDisplayId) {
        ListenersContainer<T> listenersContainer = getListenersContainerLocked(type);
        Set<PluginManager.PluginChangeListener<T>> localListeners = new LinkedHashSet<>();
        // if GLOBAL value change we need to notify only listeners for displays that does not
        // have display specific value
        if (GLOBAL_ID.equals(uniqueDisplayId)) {
            ValuesContainer<T> valuesContainer = getValuesContainerLocked(type);
            Set<String> excludedDisplayIds = valuesContainer.getNonGlobalDisplaysLocked();
            listenersContainer.mListeners.forEach((localDisplayId, listeners) -> {
                if (!excludedDisplayIds.contains(localDisplayId)) {
                    localListeners.addAll(listeners);
                }
            });
        } else {
            localListeners.addAll(
                    listenersContainer.mListeners.getOrDefault(uniqueDisplayId, Set.of()));
        }
        return localListeners;
    }

    /**
     * Adds listener for PluginType. If storage already has value for this type, listener will
     * be notified immediately.
     */
    <T> void addListener(PluginType<T> type, String uniqueDisplayId,
            PluginManager.PluginChangeListener<T> listener) {
        if (isTypeDisabled(type)) {
            Slog.d(TAG, "addListener ignored for disabled type=" + type.mName);
            return;
        }
        if (GLOBAL_ID.equals(uniqueDisplayId)) {
            Slog.d(TAG, "addListener ignored for GLOBAL_ID, type=" + type.mName);
            return;
        }
        T value = null;
        synchronized (mLock) {
            ListenersContainer<T> container = getListenersContainerLocked(type);
            if (container.addListenerLocked(uniqueDisplayId, listener)) {
                ValuesContainer<T> valuesContainer = getValuesContainerLocked(type);
                value = valuesContainer.getValueLocked(uniqueDisplayId);
            }
        }
        if (value != null) {
            listener.onChanged(value);
        }
    }

    /**
     * Removes listener
     */
    <T> void removeListener(PluginType<T> type, String uniqueDisplayId,
            PluginManager.PluginChangeListener<T> listener) {
        if (isTypeDisabled(type)) {
            Slog.d(TAG, "removeListener ignored for disabled type=" + type.mName);
            return;
        }
        if (GLOBAL_ID.equals(uniqueDisplayId)) {
            Slog.d(TAG, "removeListener ignored for GLOBAL_ID, type=" + type.mName);
            return;
        }
        synchronized (mLock) {
            ListenersContainer<T> container = getListenersContainerLocked(type);
            container.removeListenerLocked(uniqueDisplayId, listener);
        }
    }

    /**
     * Print the object's state and debug information into the given stream.
     */
    void dump(PrintWriter pw) {
        Map<PluginType<?>, Map<String, Object>> localValues = new HashMap<>();
        @SuppressWarnings("rawtypes")
        Map<PluginType, Map<String, Set>> localListeners = new HashMap<>();
        Map<String, List<PluginEventStorage.TimeFrame>> timeFrames = new HashMap<>();
        synchronized (mLock) {
            mPluginEventStorages.forEach((displayId, storage) -> {
                timeFrames.put(displayId, storage.getTimeFrames());
            });
            mValues.forEach((type, valueContainer) -> {
                localValues.put(type, new HashMap<>(valueContainer.mValues));
            });
            mListeners.forEach((type, container) -> {
                localListeners.put(type, new HashMap<>(container.mListeners));
            });
        }
        pw.println("PluginStorage:");
        pw.println("values=" + localValues);
        pw.println("listeners=" + localListeners);
        pw.println("PluginEventStorage:");
        for (Map.Entry<String, List<PluginEventStorage.TimeFrame>> timeFrameEntry :
                timeFrames.entrySet()) {
            pw.println("TimeFrames for displayId=" + timeFrameEntry.getKey());
            for (PluginEventStorage.TimeFrame timeFrame : timeFrameEntry.getValue()) {
                timeFrame.dump(pw);
            }
        }
    }

    private boolean isTypeDisabled(PluginType<?> type) {
        return !mEnabledTypes.contains(type);
    }

    @GuardedBy("mLock")
    @SuppressWarnings("unchecked")
    private <T> ListenersContainer<T> getListenersContainerLocked(PluginType<T> type) {
        ListenersContainer<?> container = mListeners.get(type);
        if (container == null) {
            ListenersContainer<T> lc = new ListenersContainer<>();
            mListeners.put(type, lc);
            return lc;
        } else {
            return (ListenersContainer<T>) container;
        }
    }

    @GuardedBy("mLock")
    @SuppressWarnings("unchecked")
    private <T> ValuesContainer<T> getValuesContainerLocked(PluginType<T> type) {
        ValuesContainer<?> container = mValues.get(type);
        if (container == null) {
            ValuesContainer<T> vc = new ValuesContainer<>();
            mValues.put(type, vc);
            return vc;
        } else {
            return (ValuesContainer<T>) container;
        }
    }

    private static final class ListenersContainer<T> {
        private final Map<String, Set<PluginManager.PluginChangeListener<T>>> mListeners =
                new LinkedHashMap<>();

        private boolean addListenerLocked(
                String uniqueDisplayId, PluginManager.PluginChangeListener<T> listener) {
            Set<PluginManager.PluginChangeListener<T>> listenersForDisplay =
                    mListeners.computeIfAbsent(uniqueDisplayId, k -> new LinkedHashSet<>());
            return listenersForDisplay.add(listener);
        }

        private void removeListenerLocked(String uniqueDisplayId,
                PluginManager.PluginChangeListener<T> listener) {
            Set<PluginManager.PluginChangeListener<T>> listenersForDisplay = mListeners.get(
                    uniqueDisplayId);
            if (listenersForDisplay == null) {
                return;
            }

            listenersForDisplay.remove(listener);

            if (listenersForDisplay.isEmpty()) {
                mListeners.remove(uniqueDisplayId);
            }
        }
    }

    private static final class ValuesContainer<T> {
        private final Map<String, T> mValues = new HashMap<>();

        private void updateValueLocked(String uniqueDisplayId, @Nullable T value) {
            if (value == null) {
                mValues.remove(uniqueDisplayId);
            } else {
                mValues.put(uniqueDisplayId, value);
            }
        }

        private Set<String> getNonGlobalDisplaysLocked() {
            Set<String> keys = new HashSet<>(mValues.keySet());
            keys.remove(GLOBAL_ID);
            return keys;
        }

        private @Nullable T getValueLocked(String displayId) {
            return mValues.getOrDefault(displayId, mValues.get(GLOBAL_ID));
        }
    }
}
