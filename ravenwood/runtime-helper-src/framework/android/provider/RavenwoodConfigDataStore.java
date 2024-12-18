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
package android.provider;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig.BadConfigException;
import android.provider.DeviceConfig.MonitorCallback;
import android.provider.DeviceConfig.Properties;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link DeviceConfigDataStore} used only on Ravenwood.
 *
 * TODO(b/368591527) Support monitor callback related features
 * TODO(b/368591527) Support "default" related features
 */
final class RavenwoodConfigDataStore implements DeviceConfigDataStore {
    private static final RavenwoodConfigDataStore sInstance = new RavenwoodConfigDataStore();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int mSyncDisabledMode = DeviceConfig.SYNC_DISABLED_MODE_NONE;

    @GuardedBy("mLock")
    private final HashMap<String, HashMap<String, String>> mStore = new HashMap<>();

    private record ObserverInfo(String namespace, ContentObserver observer) {
    }

    @GuardedBy("mLock")
    private final ArrayList<ObserverInfo> mObservers = new ArrayList<>();

    static RavenwoodConfigDataStore getInstance() {
        return sInstance;
    }

    private static void shouldNotBeCalled() {
        throw new RuntimeException("shouldNotBeCalled");
    }

    void clearAll() {
        synchronized (mLock) {
            mSyncDisabledMode = DeviceConfig.SYNC_DISABLED_MODE_NONE;
            mStore.clear();
        }
    }

    @GuardedBy("mLock")
    private HashMap<String, String> getNamespaceLocked(@NonNull String namespace) {
        Objects.requireNonNull(namespace);
        return mStore.computeIfAbsent(namespace, k -> new HashMap<>());
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Map<String, String> getAllProperties() {
        synchronized (mLock) {
            var ret = new HashMap<String, String>();

            for (var namespaceAndMap : mStore.entrySet()) {
                for (var nameAndValue : namespaceAndMap.getValue().entrySet()) {
                    ret.put(namespaceAndMap.getKey() + "/" + nameAndValue.getKey(),
                            nameAndValue.getValue());
                }
            }
            return ret;
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Properties getProperties(@NonNull String namespace, @NonNull String... names) {
        Objects.requireNonNull(namespace);

        synchronized (mLock) {
            var namespaceMap = getNamespaceLocked(namespace);

            if (names.length == 0) {
                return new Properties(namespace, Map.copyOf(namespaceMap));
            } else {
                var map = new HashMap<String, String>();
                for (var name : names) {
                    Objects.requireNonNull(name);
                    map.put(name, namespaceMap.get(name));
                }
                return new Properties(namespace, map);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean setProperties(@NonNull Properties properties) throws BadConfigException {
        Objects.requireNonNull(properties);

        synchronized (mLock) {
            var namespaceMap = getNamespaceLocked(properties.getNamespace());
            for (var kv : properties.getPropertyValues().entrySet()) {
                namespaceMap.put(
                        Objects.requireNonNull(kv.getKey()),
                        Objects.requireNonNull(kv.getValue())
                );
            }
            notifyObserversLock(properties.getNamespace(),
                    properties.getKeyset().toArray(new String[0]));
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean setProperty(@NonNull String namespace, @NonNull String name,
            @Nullable String value, boolean makeDefault) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(name);

        synchronized (mLock) {
            var namespaceMap = getNamespaceLocked(namespace);
            namespaceMap.put(name, value);

            // makeDefault not supported.
            notifyObserversLock(namespace, new String[]{name});
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteProperty(@NonNull String namespace, @NonNull String name) {
        Objects.requireNonNull(namespace);
        Objects.requireNonNull(name);

        synchronized (mLock) {
            var namespaceMap = getNamespaceLocked(namespace);
            if (namespaceMap.remove(name) != null) {
                notifyObserversLock(namespace, new String[]{name});
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void resetToDefaults(int resetMode, @Nullable String namespace) {
        // not supported in DeviceConfig.java
        shouldNotBeCalled();
    }

    /** {@inheritDoc} */
    @Override
    public void setSyncDisabledMode(int syncDisabledMode) {
        synchronized (mLock) {
            mSyncDisabledMode = syncDisabledMode;
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getSyncDisabledMode() {
        synchronized (mLock) {
            return mSyncDisabledMode;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setMonitorCallback(@NonNull ContentResolver resolver, @NonNull Executor executor,
            @NonNull MonitorCallback callback) {
        // not supported in DeviceConfig.java
        shouldNotBeCalled();
    }

    /** {@inheritDoc} */
    @Override
    public void clearMonitorCallback(@NonNull ContentResolver resolver) {
        // not supported in DeviceConfig.java
        shouldNotBeCalled();
    }

    /** {@inheritDoc} */
    @Override
    public void registerContentObserver(@NonNull String namespace, boolean notifyForescendants,
            ContentObserver contentObserver) {
        synchronized (mLock) {
            mObservers.add(new ObserverInfo(
                    Objects.requireNonNull(namespace),
                    Objects.requireNonNull(contentObserver)
            ));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void unregisterContentObserver(@NonNull ContentObserver contentObserver) {
        synchronized (mLock) {
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                if (mObservers.get(i).observer == contentObserver) {
                    mObservers.remove(i);
                }
            }
        }
    }

    private static final Uri CONTENT_URI = Uri.parse("content://settings/config");

    @GuardedBy("mLock")
    private void notifyObserversLock(@NonNull String namespace, String[] keys) {
        var urib = CONTENT_URI.buildUpon().appendPath(namespace);
        for (var key : keys) {
            urib.appendEncodedPath(key);
        }
        var uri = urib.build();

        final var copy = List.copyOf(mObservers);
        new Handler(Looper.getMainLooper()).post(() -> {
            for (int i = copy.size() - 1; i >= 0; i--) {
                if (copy.get(i).namespace.equals(namespace)) {
                    copy.get(i).observer.dispatchChange(false, uri);
                }
            }
        });
    }
}
