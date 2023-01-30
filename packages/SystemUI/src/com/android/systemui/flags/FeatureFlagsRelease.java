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

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.HashMap;
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
    private final ServerFlagReader mServerFlagReader;
    private final Restarter mRestarter;
    private final Map<String, Flag<?>> mAllFlags;
    private final Map<String, Boolean> mBooleanCache = new HashMap<>();
    private final Map<String, String> mStringCache = new HashMap<>();

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
            ServerFlagReader serverFlagReader,
            @Named(ALL_FLAGS) Map<String, Flag<?>> allFlags,
            Restarter restarter) {
        mResources = resources;
        mSystemProperties = systemProperties;
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
        if (!mBooleanCache.containsKey(flag.getName())) {
            return isEnabled(flag.getName(), mResources.getBoolean(flag.getResourceId()));
        }

        return mBooleanCache.get(flag.getName());
    }

    @Override
    public boolean isEnabled(SysPropBooleanFlag flag) {
        if (!mBooleanCache.containsKey(flag.getName())) {
            return isEnabled(
                    flag.getName(),
                    mSystemProperties.getBoolean(flag.getName(), flag.getDefault()));
        }

        return mBooleanCache.get(flag.getName());
    }

    private boolean isEnabled(String name, boolean defaultValue) {
        mBooleanCache.put(name, defaultValue);
        return defaultValue;
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        return getString(flag.getName(), flag.getDefault());
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        if (!mStringCache.containsKey(flag.getName())) {
            return getString(flag.getName(),
                    requireNonNull(mResources.getString(flag.getResourceId())));
        }

        return mStringCache.get(flag.getName());
    }

    private String getString(String name, String defaultValue) {
        mStringCache.put(name, defaultValue);
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
        pw.println("Booleans: ");
        for (Map.Entry<String, Flag<?>> nameToFlag : knownFlags.entrySet()) {
            Flag<?> flag = nameToFlag.getValue();
            if (!(flag instanceof BooleanFlag)
                    || !(flag instanceof ResourceBooleanFlag)
                    || !(flag instanceof SysPropBooleanFlag)) {
                continue;
            }

            boolean def = false;
            if (!mBooleanCache.containsKey(flag.getName())) {
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
            pw.println(
                    "  " + flag.getName() + ": "
                            + (mBooleanCache.getOrDefault(flag.getName(), def)));
        }

        pw.println("Strings: ");
        for (Map.Entry<String, Flag<?>> nameToFlag : knownFlags.entrySet()) {
            Flag<?> flag = nameToFlag.getValue();
            if (!(flag instanceof StringFlag)
                    || !(flag instanceof ResourceStringFlag)) {
                continue;
            }

            String def = "";
            if (!mBooleanCache.containsKey(flag.getName())) {
                if (flag instanceof ResourceStringFlag) {
                    ResourceStringFlag f = (ResourceStringFlag) flag;
                    def = mResources.getString(f.getResourceId());
                } else if (flag instanceof StringFlag) {
                    StringFlag f = (StringFlag) flag;
                    def = f.getDefault();
                }
            }
            String value = mStringCache.getOrDefault(flag.getName(), def);
            pw.println(
                    "  " + flag.getName() + ": [length=" + value.length() + "] \"" + value + "\"");
        }
    }
}
