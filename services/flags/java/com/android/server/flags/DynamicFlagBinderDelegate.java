/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.flags;

import android.annotation.NonNull;
import android.flags.IFeatureFlagsCallback;
import android.flags.SyncableFlag;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Handles DynamicFlags for {@link FeatureFlagsBinder}.
 *
 * Dynamic flags are simultaneously simpler and more complicated than process stable flags. We can
 * return whatever value is last known for a flag is, without too much worry about the flags
 * changing (they are dynamic after all). However, we have to alert all the relevant clients
 * about those flag changes, and need to be able to restore to a default value if the flag gets
 * reset/erased during runtime.
 */
class DynamicFlagBinderDelegate {

    private final FlagOverrideStore mFlagStore;
    private final FlagCache<DynamicFlagData> mDynamicFlags = new FlagCache<>();
    private final Map<Integer, Set<IFeatureFlagsCallback>> mCallbacks = new HashMap<>();
    private static final Function<Integer, Set<IFeatureFlagsCallback>> NEW_CALLBACK_SET =
            k -> new HashSet<>();

    private final DeviceConfig.OnPropertiesChangedListener mDeviceConfigListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
                    String ns = properties.getNamespace();
                    for (String name : properties.getKeyset()) {
                        // Don't alert for flags we don't care about.
                        // Don't alert for flags that have been overridden locally.
                        if (!mDynamicFlags.contains(ns, name) || mFlagStore.contains(ns, name)) {
                            continue;
                        }
                        mFlagChangeCallback.onFlagChanged(
                                ns, name, properties.getString(name, null));
                    }
                }
            };

    private final FlagOverrideStore.FlagChangeCallback mFlagChangeCallback =
            (namespace, name, value) -> {
                // Don't bother with callbacks for non-dynamic flags.
                if (!mDynamicFlags.contains(namespace, name)) {
                    return;
                }

                // Don't bother with callbacks if nothing changed.
                // Handling erasure (null) is special, as we may be restoring back to a value
                // we were already at.
                DynamicFlagData data = mDynamicFlags.getOrNull(namespace, name);
                if (data == null) {
                    return;  // shouldn't happen, but better safe than sorry.
                }
                if (value == null) {
                    if (data.getValue().equals(data.getDefaultValue())) {
                        return;
                    }
                    value = data.getDefaultValue();
                } else if (data.getValue().equals(value)) {
                    return;
                }
                data.setValue(value);

                final Set<IFeatureFlagsCallback> cbCopy;
                synchronized (mCallbacks) {
                    cbCopy = new HashSet<>();

                    for (Integer pid : mCallbacks.keySet()) {
                        if (data.containsPid(pid)) {
                            cbCopy.addAll(mCallbacks.get(pid));
                        }
                    }
                }
                SyncableFlag sFlag = new SyncableFlag(namespace, name, value, true);
                cbCopy.forEach(cb -> {
                    try {
                        cb.onFlagChange(sFlag);
                    } catch (RemoteException e) {
                        Slog.w(
                                FeatureFlagsService.TAG,
                                "Failed to communicate flag change to client.");
                    }
                });
            };

    DynamicFlagBinderDelegate(FlagOverrideStore flagStore) {
        mFlagStore = flagStore;
        mFlagStore.setChangeCallback(mFlagChangeCallback);
    }

    SyncableFlag syncDynamicFlag(int pid, SyncableFlag sf) {
        if (!sf.isDynamic()) {
            return sf;
        }

        String ns = sf.getNamespace();
        String name = sf.getName();

        // Dynamic flags don't need any special threading or synchronization considerations.
        // We simply give them whatever the current value is.
        // However, we do need to keep track of dynamic flags, so that we can alert
        // about changes coming in from adb, DeviceConfig, or other sources.
        // And also so that we can keep flags relatively consistent across processes.

        DynamicFlagData data = mDynamicFlags.getOrNull(ns, name);
        String value = getFlagValue(ns, name, sf.getValue());
        // DeviceConfig listeners are per-namespace.
        if (!mDynamicFlags.containsNamespace(ns)) {
            DeviceConfig.addOnPropertiesChangedListener(
                    ns, BackgroundThread.getExecutor(), mDeviceConfigListener);
        }
        data.addClientPid(pid);
        data.setValue(value);
        // Store the default value so that if an override gets erased, we can restore
        // to something.
        data.setDefaultValue(sf.getValue());

        return new SyncableFlag(sf.getNamespace(), sf.getName(), value, true);
    }


    void registerCallback(int pid, IFeatureFlagsCallback callback) {
        // Always add callback so that we don't end up with a possible race/leak.
        // We remove the callback directly if we fail to call #linkToDeath.
        // If we tried to add the callback after we linked, then we could end up in a
        // scenario where we link, then the binder dies, firing our BinderGriever which tries
        // to remove the callback (which has not yet been added), then finally we add the
        // callback, creating a leak.
        Set<IFeatureFlagsCallback> callbacks;
        synchronized (mCallbacks) {
            callbacks = mCallbacks.computeIfAbsent(pid, NEW_CALLBACK_SET);
            callbacks.add(callback);
        }
        try {
            callback.asBinder().linkToDeath(new BinderGriever(pid), 0);
        } catch (RemoteException e) {
            Slog.e(
                    FeatureFlagsService.TAG,
                    "Failed to link to binder death. Callback not registered.");
            synchronized (mCallbacks) {
                callbacks.remove(callback);
            }
        }
    }

    void unregisterCallback(int pid, IFeatureFlagsCallback callback) {
        // No need to unlink, since the BinderGriever will essentially be a no-op.
        // We would have to track our BinderGriever's in a map otherwise.
        synchronized (mCallbacks) {
            Set<IFeatureFlagsCallback> callbacks =
                    mCallbacks.computeIfAbsent(pid, NEW_CALLBACK_SET);
            callbacks.remove(callback);
        }
    }

    String getFlagValue(String namespace, String name, String defaultValue) {
        // If we already have a value cached, just use that.
        String value = null;
        DynamicFlagData data = mDynamicFlags.getOrNull(namespace, name);
        if (data != null) {
            value = data.getValue();
        } else {
            // Put the value in the cache for future reference.
            data = new DynamicFlagData(namespace, name);
            mDynamicFlags.setIfChanged(namespace, name, data);
        }
        // If we're not in a release build, flags can be overridden locally on device.
        if (!Build.IS_USER && value == null) {
            value = mFlagStore.get(namespace, name);
        }
        // If we still don't have a value, maybe DeviceConfig does?
        // Fallback to sf.getValue() here as well.
        if (value == null) {
            value = DeviceConfig.getString(namespace, name, defaultValue);
        }

        return value;
    }

    private static class DynamicFlagData {
        private final String mNamespace;
        private final String mName;
        private final Set<Integer> mPids = new HashSet<>();
        private String mValue;
        private String mDefaultValue;

        private DynamicFlagData(String namespace, String name) {
            mNamespace = namespace;
            mName = name;
        }

        String getValue() {
            return mValue;
        }

        void setValue(String value) {
            mValue = value;
        }

        String getDefaultValue() {
            return mDefaultValue;
        }

        void setDefaultValue(String value) {
            mDefaultValue = value;
        }

        void addClientPid(int pid) {
            mPids.add(pid);
        }

        boolean containsPid(int pid) {
            return mPids.contains(pid);
        }

        @Override
        public boolean equals(Object other) {
            if (other == null || !(other instanceof DynamicFlagData)) {
                return false;
            }

            DynamicFlagData o = (DynamicFlagData) other;

            return mName.equals(o.mName) && mNamespace.equals(o.mNamespace)
                    && mValue.equals(o.mValue) && mDefaultValue.equals(o.mDefaultValue);
        }

        @Override
        public int hashCode() {
            return mName.hashCode() + mNamespace.hashCode()
              + mValue.hashCode() + mDefaultValue.hashCode();
        }
    }


    private class BinderGriever implements IBinder.DeathRecipient {
        private final int mPid;

        private BinderGriever(int pid) {
            mPid = pid;
        }

        @Override
        public void binderDied() {
            synchronized (mCallbacks) {
                mCallbacks.remove(mPid);
            }
        }
    }
}
