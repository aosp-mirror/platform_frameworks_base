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
import android.util.SparseArray;

import androidx.annotation.BoolRes;
import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.util.wrapper.BuildInfo;

import javax.inject.Inject;
/**
 * Reads and caches feature flags for quick access
 *
 * Feature flags must be defined as boolean resources. For example:
 *
 * {@code
 *  <bool name="flag_foo_bar_baz">false</bool>
 * }
 *
 * It is strongly recommended that the name of the resource begin with "flag_".
 *
 * Flags can be overridden via adb on development builds. For example, to override the flag from the
 * previous example, do the following:
 *
 * {@code
 *  $ adb shell setprop persist.systemui.flag_foo_bar_baz 1
 * }
 *
 * Note that all storage keys begin with "flag_", even if their associated resId does not.
 *
 * Calls to this class should probably be wrapped by a method in {@link FeatureFlags}.
 */
@SysUISingleton
public class FeatureFlagReader {
    private final Resources mResources;
    private final boolean mAreFlagsOverrideable;
    private final SystemPropertiesHelper mSystemPropertiesHelper;
    private final SparseArray<CachedFlag> mCachedFlags = new SparseArray<>();

    @Inject
    public FeatureFlagReader(
            @Main Resources resources,
            BuildInfo build,
            SystemPropertiesHelper systemPropertiesHelper) {
        mResources = resources;
        mSystemPropertiesHelper = systemPropertiesHelper;
        mAreFlagsOverrideable =
                build.isDebuggable() && mResources.getBoolean(R.bool.are_flags_overrideable);
    }

    /**
     * Returns true if the specified feature flag has been enabled.
     *
     * @param resId The backing boolean resource that determines the value of the flag. This value
     *              can be overridden via DeviceConfig on development builds.
     */
    public boolean isEnabled(@BoolRes int resId) {
        synchronized (mCachedFlags) {
            CachedFlag cachedFlag = mCachedFlags.get(resId);

            if (cachedFlag == null) {
                String name = resourceIdToFlagName(resId);
                boolean value = mResources.getBoolean(resId);
                if (mAreFlagsOverrideable) {
                    value = mSystemPropertiesHelper.getBoolean(flagNameToStorageKey(name), value);
                }

                cachedFlag = new CachedFlag(name, value);
                mCachedFlags.put(resId, cachedFlag);
            }

            return cachedFlag.value;
        }
    }

    private String resourceIdToFlagName(@BoolRes int resId) {
        String resName = mResources.getResourceEntryName(resId);
        if (resName.startsWith(RESNAME_PREFIX)) {
            resName = resName.substring(RESNAME_PREFIX.length());
        }
        return resName;
    }

    private String flagNameToStorageKey(String flagName) {
        if (flagName.startsWith(STORAGE_KEY_PREFIX)) {
            return flagName;
        } else {
            return STORAGE_KEY_PREFIX + flagName;
        }
    }

    @Nullable
    private String storageKeyToFlagName(String configName) {
        if (configName.startsWith(STORAGE_KEY_PREFIX)) {
            return configName.substring(STORAGE_KEY_PREFIX.length());
        } else {
            return null;
        }
    }

    private static class CachedFlag {
        public final String name;
        public final boolean value;

        private CachedFlag(String name, boolean value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final String STORAGE_KEY_PREFIX = "persist.systemui.flag_";
    private static final String RESNAME_PREFIX = "flag_";
}
