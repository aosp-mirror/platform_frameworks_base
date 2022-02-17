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

import static java.util.Objects.requireNonNull;

import android.content.res.Resources;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Default implementation of the a Flag manager that returns default values for release builds
 *
 * There's a version of this file in src-debug which allows overriding, and has documentation about
 * how to set flags.
 */
@SysUISingleton
public class FeatureFlagsRelease implements FeatureFlags, Dumpable {
    private final Resources mResources;
    SparseBooleanArray mBooleanCache = new SparseBooleanArray();
    SparseArray<String> mStringCache = new SparseArray<>();
    @Inject
    public FeatureFlagsRelease(@Main Resources resources, DumpManager dumpManager) {
        mResources = resources;
        dumpManager.registerDumpable("SysUIFlags", this);
    }

    @Override
    public void addListener(@NonNull Flag<?> flag, @NonNull Listener listener) {}

    @Override
    public void removeListener(@NonNull Listener listener) {}

    @Override
    public boolean isEnabled(BooleanFlag flag) {
        return isEnabled(flag.getId(), flag.getDefault());
    }

    @Override
    public boolean isEnabled(ResourceBooleanFlag flag) {
        int cacheIndex = mBooleanCache.indexOfKey(flag.getId());
        if (cacheIndex < 0) {
            return isEnabled(flag.getId(), mResources.getBoolean(flag.getResourceId()));
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

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: false");
        int numBooleans = mBooleanCache.size();
        pw.println("booleans: " + numBooleans);
        for (int i = 0; i < numBooleans; i++) {
            pw.println("  sysui_flag_" + mBooleanCache.keyAt(i) + ": " + mBooleanCache.valueAt(i));
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
