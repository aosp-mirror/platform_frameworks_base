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
    private final Map<String, Integer> mIntCache = new HashMap<>();

    private final ServerFlagReader.ChangeListener mOnPropertiesChanged =
            new ServerFlagReader.ChangeListener() {
                @Override
                public void onChange(Flag<?> flag, String value) {
                    boolean shouldRestart = false;
                    if (mBooleanCache.containsKey(flag.getName())) {
                        boolean newValue = value == null ? false : Boolean.parseBoolean(value);
                        if (mBooleanCache.get(flag.getName()) != newValue) {
                            shouldRestart = true;
                        }
                    } else if (mStringCache.containsKey(flag.getName())) {
                        String newValue = value == null ? "" : value;
                        if (mStringCache.get(flag.getName()) != newValue) {
                            shouldRestart = true;
                        }
                    } else if (mIntCache.containsKey(flag.getName())) {
                        int newValue = 0;
                        try {
                            newValue = value == null ? 0 : Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                        }
                        if (mIntCache.get(flag.getName()) != newValue) {
                            shouldRestart = true;
                        }
                    }
                    if (shouldRestart) {
                        mRestarter.restartSystemUI(
                                "Server flag change: " + flag.getNamespace() + "."
                                        + flag.getName());
                    }
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
        // Fill the cache.
        return isEnabledInternal(flag.getName(),
                mServerFlagReader.readServerOverride(flag.getNamespace(), flag.getName(), true));
    }

    @Override
    public boolean isEnabled(ResourceBooleanFlag flag) {
        // Fill the cache.
        return isEnabledInternal(flag.getName(), mResources.getBoolean(flag.getResourceId()));
    }

    @Override
    public boolean isEnabled(SysPropBooleanFlag flag) {
        // Fill the cache.
        return isEnabledInternal(
                flag.getName(),
                mSystemProperties.getBoolean(flag.getName(), flag.getDefault()));
    }

    /**
     * Checks and fills the boolean cache. This is important, Always call through to this method!
     *
     * We use the cache as a way to decide if we need to restart the process when server-side
     * changes occur.
     */
    private boolean isEnabledInternal(String name, boolean defaultValue) {
        // Fill the cache.
        if (!mBooleanCache.containsKey(name)) {
            mBooleanCache.put(name, defaultValue);
        }

        return mBooleanCache.get(name);
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        // Fill the cache.
        return getStringInternal(flag.getName(), flag.getDefault());
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        // Fill the cache.
        return getStringInternal(flag.getName(),
                requireNonNull(mResources.getString(flag.getResourceId())));
    }

    /**
     * Checks and fills the String cache. This is important, Always call through to this method!
     *
     * We use the cache as a way to decide if we need to restart the process when server-side
     * changes occur.
     */
    private String getStringInternal(String name, String defaultValue) {
        if (!mStringCache.containsKey(name)) {
            mStringCache.put(name, defaultValue);
        }

        return mStringCache.get(name);
    }

    @NonNull
    @Override
    public int getInt(@NonNull IntFlag flag) {
        // Fill the cache.
        return getIntInternal(flag.getName(), flag.getDefault());
    }

    @NonNull
    @Override
    public int getInt(@NonNull ResourceIntFlag flag) {
        // Fill the cache.
        return mResources.getInteger(flag.getResourceId());
    }

    /**
     * Checks and fills the integer cache. This is important, Always call through to this method!
     *
     * We use the cache as a way to decide if we need to restart the process when server-side
     * changes occur.
     */
    private int getIntInternal(String name, int defaultValue) {
        if (!mIntCache.containsKey(name)) {
            mIntCache.put(name, defaultValue);
        }

        return mIntCache.get(name);
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
