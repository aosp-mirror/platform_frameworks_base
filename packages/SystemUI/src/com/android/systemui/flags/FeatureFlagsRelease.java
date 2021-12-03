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

import android.content.res.Resources;
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
    SparseBooleanArray mFlagCache = new SparseBooleanArray();
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
        int cacheIndex = mFlagCache.indexOfKey(flag.getId());
        if (cacheIndex < 0) {
            return isEnabled(flag.getId(), mResources.getBoolean(flag.getResourceId()));
        }

        return mFlagCache.valueAt(cacheIndex);
    }

    private boolean isEnabled(int key, boolean defaultValue) {
        mFlagCache.append(key, defaultValue);
        return defaultValue;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: false");
        int size = mFlagCache.size();
        for (int i = 0; i < size; i++) {
            pw.println("  sysui_flag_" + mFlagCache.keyAt(i) + ": " + mFlagCache.valueAt(i));
        }
    }
}
