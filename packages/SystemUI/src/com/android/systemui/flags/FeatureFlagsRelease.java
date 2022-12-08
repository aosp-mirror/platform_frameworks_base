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

package com.android.systemui.flags;

import static com.android.systemui.flags.FlagsCommonModule.ALL_FLAGS;

import static java.util.Objects.requireNonNull;

import android.content.res.Resources;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.DeviceConfigProxy;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Default implementation of the a Flag manager that returns default values for release builds
 *
 * There's a version of this file in src-debug which allows overriding, and has documentation about
 * how to set flags.
 */
@SysUISingleton
public class FeatureFlagsRelease implements FeatureFlags {
    static final String TAG = "SysUIFlags";

    private final Resources mResources;
    private final SystemPropertiesHelper mSystemProperties;
    private final DeviceConfigProxy mDeviceConfigProxy;
    private final ServerFlagReader mServerFlagReader;
    private final Restarter mRestarter;
    private final Map<Integer, Flag<?>> mAllFlags;
    SparseBooleanArray mBooleanCache = new SparseBooleanArray();
    SparseArray<String> mStringCache = new SparseArray<>();

    private final ServerFlagReader.ChangeListener mOnPropertiesChanged =
            new ServerFlagReader.ChangeListener() {
                @Override
                public void onChange() {
                    mRestarter.restartSystemUI();
                }
            };

    @Inject
    public FeatureFlagsRelease(
            @Main Resources resources,
            SystemPropertiesHelper systemProperties,
            DeviceConfigProxy deviceConfigProxy,
            ServerFlagReader serverFlagReader,
            @Named(ALL_FLAGS) Map<Integer, Flag<?>> allFlags,
            Restarter restarter) {
        mResources = resources;
        mSystemProperties = systemProperties;
        mDeviceConfigProxy = deviceConfigProxy;
        mServerFlagReader = serverFlagReader;
        mAllFlags = allFlags;
        mRestarter = restarter;
    }

    /** Call after construction to setup listeners. */
    void init() {
        mServerFlagReader.listenForChanges(mAllFlags.values(), mOnPropertiesChanged);
    }

    @Override
    public void addListener(@NonNull Flag<?> flag, @NonNull Listener listener) {
    }

    @Override
    public void removeListener(@NonNull Listener listener) {
    }

    @Override
    public boolean isEnabled(@NotNull UnreleasedFlag flag) {
        return false;
    }

    @Override
    public boolean isEnabled(@NotNull ReleasedFlag flag) {
        return mServerFlagReader.readServerOverride(flag.getNamespace(), flag.getName(), true);
    }

    @Override
    public boolean isEnabled(ResourceBooleanFlag flag) {
        int cacheIndex = mBooleanCache.indexOfKey(flag.getId());
        if (cacheIndex < 0) {
            return isEnabled(flag.getId(), mResources.getBoolean(flag.getResourceId()));
        }

        return mBooleanCache.valueAt(cacheIndex);
    }

    @Override
    public boolean isEnabled(SysPropBooleanFlag flag) {
        int cacheIndex = mBooleanCache.indexOfKey(flag.getId());
        if (cacheIndex < 0) {
            return isEnabled(
                    flag.getId(), mSystemProperties.getBoolean(flag.getName(), flag.getDefault()));
        }

        return mBooleanCache.valueAt(cacheIndex);
    }

    private boolean isEnabled(int key, boolean defaultValue) {
        mBooleanCache.append(key, defaultValue);
        return defaultValue;
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        return getString(flag.getId(), flag.getDefault());
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        int cacheIndex = mStringCache.indexOfKey(flag.getId());
        if (cacheIndex < 0) {
            return getString(flag.getId(),
                    requireNonNull(mResources.getString(flag.getResourceId())));
        }

        return mStringCache.valueAt(cacheIndex);
    }

    private String getString(int key, String defaultValue) {
        mStringCache.append(key, defaultValue);
        return defaultValue;
    }

    @NonNull
    @Override
    public int getInt(@NonNull IntFlag flag) {
        return flag.getDefault();
    }

    @NonNull
    @Override
    public int getInt(@NonNull ResourceIntFlag flag) {
        return mResources.getInteger(flag.getResourceId());
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: false");
        Map<String, Flag<?>> knownFlags = FlagsFactory.INSTANCE.getKnownFlags();
        for (Map.Entry<String, Flag<?>> nameToFlag : knownFlags.entrySet()) {
            Flag<?> flag = nameToFlag.getValue();
            int id = flag.getId();
            boolean def = false;
            if (mBooleanCache.indexOfKey(flag.getId()) < 0) {
                if (flag instanceof SysPropBooleanFlag) {
                    SysPropBooleanFlag f = (SysPropBooleanFlag) flag;
                    def = mSystemProperties.getBoolean(f.getName(), f.getDefault());
                } else if (flag instanceof ResourceBooleanFlag) {
                    ResourceBooleanFlag f = (ResourceBooleanFlag) flag;
                    def = mResources.getBoolean(f.getResourceId());
                } else if (flag instanceof BooleanFlag) {
                    BooleanFlag f = (BooleanFlag) flag;
                    def = f.getDefault();
                }
            }
            pw.println("  sysui_flag_" + id + ": " + (mBooleanCache.get(id, def)));
        }
        int numStrings = mStringCache.size();
        pw.println("Strings: " + numStrings);
        for (int i = 0; i < numStrings; i++) {
            final int id = mStringCache.keyAt(i);
            final String value = mStringCache.valueAt(i);
            final int length = value.length();
            pw.println("  sysui_flag_" + id + ": [length=" + length + "] \"" + value + "\"");
        }
    }
}
