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

import static com.android.systemui.flags.FlagManager.ACTION_GET_FLAGS;
import static com.android.systemui.flags.FlagManager.ACTION_SET_FLAG;
import static com.android.systemui.flags.FlagManager.EXTRA_FLAGS;
import static com.android.systemui.flags.FlagManager.EXTRA_NAME;
import static com.android.systemui.flags.FlagManager.EXTRA_VALUE;
import static com.android.systemui.flags.FlagsCommonModule.ALL_FLAGS;

import static java.util.Objects.requireNonNull;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.GlobalSettings;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Concrete implementation of the a Flag manager that returns default values for debug builds
 *
 * Flags can be set (or unset) via the following adb command:
 *
 * adb shell cmd statusbar flag <id> <on|off|toggle|erase>
 *
 * Alternatively, you can change flags via a broadcast intent:
 *
 * adb shell am broadcast -a com.android.systemui.action.SET_FLAG --ei id <id> [--ez value <0|1>]
 *
 * To restore a flag back to its default, leave the `--ez value <0|1>` off of the command.
 */
@SysUISingleton
public class FeatureFlagsDebug implements FeatureFlags {
    static final String TAG = "SysUIFlags";

    private final FlagManager mFlagManager;
    private final Context mContext;
    private final GlobalSettings mGlobalSettings;
    private final Resources mResources;
    private final SystemPropertiesHelper mSystemProperties;
    private final ServerFlagReader mServerFlagReader;
    private final Map<String, Flag<?>> mAllFlags;
    private final Map<String, Boolean> mBooleanFlagCache = new TreeMap<>();
    private final Map<String, String> mStringFlagCache = new TreeMap<>();
    private final Map<String, Integer> mIntFlagCache = new TreeMap<>();
    private final Restarter mRestarter;

    private final ServerFlagReader.ChangeListener mOnPropertiesChanged =
            new ServerFlagReader.ChangeListener() {
                @Override
                public void onChange(Flag<?> flag, String value) {
                    boolean shouldRestart = false;
                    if (mBooleanFlagCache.containsKey(flag.getName())) {
                        boolean newValue = value == null ? false : Boolean.parseBoolean(value);
                        if (mBooleanFlagCache.get(flag.getName()) != newValue) {
                            shouldRestart = true;
                        }
                    } else if (mStringFlagCache.containsKey(flag.getName())) {
                        String newValue = value == null ? "" : value;
                        if (mStringFlagCache.get(flag.getName()) != value) {
                            shouldRestart = true;
                        }
                    } else if (mIntFlagCache.containsKey(flag.getName())) {
                        int newValue = 0;
                        try {
                            newValue = value == null ? 0 : Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                        }
                        if (mIntFlagCache.get(flag.getName()) != newValue) {
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
    public FeatureFlagsDebug(
            FlagManager flagManager,
            Context context,
            GlobalSettings globalSettings,
            SystemPropertiesHelper systemProperties,
            @Main Resources resources,
            ServerFlagReader serverFlagReader,
            @Named(ALL_FLAGS) Map<String, Flag<?>> allFlags,
            Restarter restarter) {
        mFlagManager = flagManager;
        mContext = context;
        mGlobalSettings = globalSettings;
        mResources = resources;
        mSystemProperties = systemProperties;
        mServerFlagReader = serverFlagReader;
        mAllFlags = allFlags;
        mRestarter = restarter;
    }

    /** Call after construction to setup listeners. */
    void init() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_FLAG);
        filter.addAction(ACTION_GET_FLAGS);
        mFlagManager.setOnSettingsChangedAction(
                suppressRestart -> restartSystemUI(suppressRestart, "Settings changed"));
        mFlagManager.setClearCacheAction(this::removeFromCache);
        mContext.registerReceiver(mReceiver, filter, null, null,
                Context.RECEIVER_EXPORTED_UNAUDITED);
        mServerFlagReader.listenForChanges(mAllFlags.values(), mOnPropertiesChanged);
    }

    @Override
    public boolean isEnabled(@NotNull UnreleasedFlag flag) {
        return isEnabledInternal(flag);
    }

    @Override
    public boolean isEnabled(@NotNull ReleasedFlag flag) {
        return isEnabledInternal(flag);
    }

    private boolean isEnabledInternal(@NotNull BooleanFlag flag) {
        String name = flag.getName();
        if (!mBooleanFlagCache.containsKey(name)) {
            mBooleanFlagCache.put(name,
                    readBooleanFlagInternal(flag, flag.getDefault()));
        }

        return mBooleanFlagCache.get(name);
    }

    @Override
    public boolean isEnabled(@NonNull ResourceBooleanFlag flag) {
        String name = flag.getName();
        if (!mBooleanFlagCache.containsKey(name)) {
            mBooleanFlagCache.put(name,
                    readBooleanFlagInternal(flag, mResources.getBoolean(flag.getResourceId())));
        }

        return mBooleanFlagCache.get(name);
    }

    @Override
    public boolean isEnabled(@NonNull SysPropBooleanFlag flag) {
        String name = flag.getName();
        if (!mBooleanFlagCache.containsKey(name)) {
            // Use #readFlagValue to get the default. That will allow it to fall through to
            // teamfood if need be.
            mBooleanFlagCache.put(
                    name,
                    mSystemProperties.getBoolean(
                            flag.getName(),
                            readBooleanFlagInternal(flag, flag.getDefault())));
        }

        return mBooleanFlagCache.get(name);
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        String name = flag.getName();
        if (!mStringFlagCache.containsKey(name)) {
            mStringFlagCache.put(name,
                    readFlagValueInternal(
                            flag.getId(), name, flag.getDefault(), StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(name);
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        String name = flag.getName();
        if (!mStringFlagCache.containsKey(name)) {
            mStringFlagCache.put(name,
                    readFlagValueInternal(
                            flag.getId(), name, mResources.getString(flag.getResourceId()),
                            StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(name);
    }


    @NonNull
    @Override
    public int getInt(@NonNull IntFlag flag) {
        String name = flag.getName();
        if (!mIntFlagCache.containsKey(name)) {
            mIntFlagCache.put(name,
                    readFlagValueInternal(
                            flag.getId(), name, flag.getDefault(), IntFlagSerializer.INSTANCE));
        }

        return mIntFlagCache.get(name);
    }

    @NonNull
    @Override
    public int getInt(@NonNull ResourceIntFlag flag) {
        String name = flag.getName();
        if (!mIntFlagCache.containsKey(name)) {
            mIntFlagCache.put(name,
                    readFlagValueInternal(
                            flag.getId(), name, mResources.getInteger(flag.getResourceId()),
                            IntFlagSerializer.INSTANCE));
        }

        return mIntFlagCache.get(name);
    }

    /** Specific override for Boolean flags that checks against the teamfood list.*/
    private boolean readBooleanFlagInternal(Flag<Boolean> flag, boolean defaultValue) {
        Boolean result = readBooleanFlagOverride(flag.getName());
        if (result == null) {
            result = readBooleanFlagOverride(flag.getId());
            if (result != null) {
                // Move overrides from id to name
                setFlagValueInternal(flag.getName(), result, BooleanFlagSerializer.INSTANCE);
            }
        }
        boolean hasServerOverride = mServerFlagReader.hasOverride(
                flag.getNamespace(), flag.getName());

        // Only check for teamfood if the default is false
        // and there is no server override.
        if (!hasServerOverride
                && !defaultValue
                && result == null
                && !flag.getName().equals(Flags.TEAMFOOD.getName())
                && flag.getTeamfood()) {
            return isEnabled(Flags.TEAMFOOD);
        }

        return result == null ? mServerFlagReader.readServerOverride(
                flag.getNamespace(), flag.getName(), defaultValue) : result;
    }

    private Boolean readBooleanFlagOverride(int id) {
        return readFlagValueInternal(id, BooleanFlagSerializer.INSTANCE);
    }

    private Boolean readBooleanFlagOverride(String name) {
        return readFlagValueInternal(name, BooleanFlagSerializer.INSTANCE);
    }

    // TODO(b/265188950): Remove id from this method once ids are fully deprecated.
    @NonNull
    private <T> T readFlagValueInternal(
            int id, String name, @NonNull T defaultValue, FlagSerializer<T> serializer) {
        requireNonNull(defaultValue, "defaultValue");
        T resultForName = readFlagValueInternal(name, serializer);
        if (resultForName == null) {
            T resultForId = readFlagValueInternal(id, serializer);
            if (resultForId == null) {
                return defaultValue;
            } else {
                setFlagValue(name, resultForId, serializer);
                return resultForId;
            }
        }
        return resultForName;
    }


    /** Returns the stored value or null if not set. */
    // TODO(b/265188950): Remove method this once ids are fully deprecated.
    @Nullable
    private <T> T readFlagValueInternal(int id, FlagSerializer<T> serializer) {
        try {
            return mFlagManager.readFlagValue(id, serializer);
        } catch (Exception e) {
            eraseInternal(id);
        }
        return null;
    }

    /** Returns the stored value or null if not set. */
    @Nullable
    private <T> T readFlagValueInternal(String name, FlagSerializer<T> serializer) {
        try {
            return mFlagManager.readFlagValue(name, serializer);
        } catch (Exception e) {
            eraseInternal(name);
        }
        return null;
    }

    private <T> void setFlagValue(String name, @NonNull T value, FlagSerializer<T> serializer) {
        requireNonNull(value, "Cannot set a null value");
        T currentValue = readFlagValueInternal(name, serializer);
        if (Objects.equals(currentValue, value)) {
            Log.i(TAG, "Flag \"" + name + "\" is already " + value);
            return;
        }
        setFlagValueInternal(name, value, serializer);
        Log.i(TAG, "Set flag \"" + name + "\" to " + value);
        removeFromCache(name);
        mFlagManager.dispatchListenersAndMaybeRestart(
                name,
                suppressRestart -> restartSystemUI(
                        suppressRestart, "Flag \"" + name + "\" changed to " + value));
    }

    private <T> void setFlagValueInternal(
            String name, @NonNull T value, FlagSerializer<T> serializer) {
        final String data = serializer.toSettingsData(value);
        if (data == null) {
            Log.w(TAG, "Failed to set flag " + name + " to " + value);
            return;
        }
        mGlobalSettings.putStringForUser(mFlagManager.nameToSettingsKey(name), data,
                UserHandle.USER_CURRENT);
    }

    <T> void eraseFlag(Flag<T> flag) {
        if (flag instanceof SysPropFlag) {
            mSystemProperties.erase(flag.getName());
            dispatchListenersAndMaybeRestart(
                    flag.getName(),
                    suppressRestart -> restartSystemUI(
                            suppressRestart,
                            "SysProp Flag \"" + flag.getNamespace() + "."
                                    + flag.getName() + "\" reset to default."));
        } else {
            eraseFlag(flag.getName());
        }
    }

    /** Erase a flag's overridden value if there is one. */
    private void eraseFlag(String name) {
        eraseInternal(name);
        removeFromCache(name);
        dispatchListenersAndMaybeRestart(
                name,
                suppressRestart -> restartSystemUI(
                        suppressRestart, "Flag \"" + name + "\" reset to default"));
    }

    private void dispatchListenersAndMaybeRestart(String name, Consumer<Boolean> restartAction) {
        mFlagManager.dispatchListenersAndMaybeRestart(name, restartAction);
    }

    /** Works just like {@link #eraseFlag(String)} except that it doesn't restart SystemUI. */
    // TODO(b/265188950): Remove method this once ids are fully deprecated.
    private void eraseInternal(int id) {
        // We can't actually "erase" things from settings, but we can set them to empty!
        mGlobalSettings.putStringForUser(mFlagManager.idToSettingsKey(id), "",
                UserHandle.USER_CURRENT);
        Log.i(TAG, "Erase name " + id);
    }

    /** Works just like {@link #eraseFlag(String)} except that it doesn't restart SystemUI. */
    private void eraseInternal(String name) {
        // We can't actually "erase" things from settings, but we can set them to empty!
        mGlobalSettings.putStringForUser(mFlagManager.nameToSettingsKey(name), "",
                UserHandle.USER_CURRENT);
        Log.i(TAG, "Erase name " + name);
    }

    @Override
    public void addListener(@NonNull Flag<?> flag, @NonNull Listener listener) {
        mFlagManager.addListener(flag, listener);
    }

    @Override
    public void removeListener(@NonNull Listener listener) {
        mFlagManager.removeListener(listener);
    }

    private void restartSystemUI(boolean requestSuppress, String reason) {
        if (requestSuppress) {
            Log.i(TAG, "SystemUI Restart Suppressed");
            return;
        }
        mRestarter.restartSystemUI(reason);
    }

    private void restartAndroid(boolean requestSuppress, String reason) {
        if (requestSuppress) {
            Log.i(TAG, "Android Restart Suppressed");
            return;
        }
        mRestarter.restartAndroid(reason);
    }

    void setBooleanFlagInternal(Flag<?> flag, boolean value) {
        if (flag instanceof BooleanFlag) {
            setFlagValue(flag.getName(), value, BooleanFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceBooleanFlag) {
            setFlagValue(flag.getName(), value, BooleanFlagSerializer.INSTANCE);
        } else if (flag instanceof SysPropBooleanFlag) {
            // Store SysProp flags in SystemProperties where they can read by outside parties.
            mSystemProperties.setBoolean(((SysPropBooleanFlag) flag).getName(), value);
            dispatchListenersAndMaybeRestart(
                    flag.getName(),
                    suppressRestart -> restartSystemUI(
                            suppressRestart,
                            "Flag \"" + flag.getName() + "\" changed to " + value));
        } else {
            throw new IllegalArgumentException("Unknown flag type");
        }
    }

    void setStringFlagInternal(Flag<?> flag, String value) {
        if (flag instanceof StringFlag) {
            setFlagValue(flag.getName(), value, StringFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceStringFlag) {
            setFlagValue(flag.getName(), value, StringFlagSerializer.INSTANCE);
        } else {
            throw new IllegalArgumentException("Unknown flag type");
        }
    }

    void setIntFlagInternal(Flag<?> flag, int value) {
        if (flag instanceof IntFlag) {
            setFlagValue(flag.getName(), value, IntFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceIntFlag) {
            setFlagValue(flag.getName(), value, IntFlagSerializer.INSTANCE);
        } else {
            throw new IllegalArgumentException("Unknown flag type");
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_SET_FLAG.equals(action)) {
                handleSetFlag(intent.getExtras());
            } else if (ACTION_GET_FLAGS.equals(action)) {
                ArrayList<Flag<?>> flags = new ArrayList<>(mAllFlags.values());

                // Convert all flags to parcelable flags.
                ArrayList<ParcelableFlag<?>> pFlags = new ArrayList<>();
                for (Flag<?> f : flags) {
                    ParcelableFlag<?> pf = toParcelableFlag(f);
                    if (pf != null) {
                        pFlags.add(pf);
                    }
                }

                Bundle extras = getResultExtras(true);
                if (extras != null) {
                    extras.putParcelableArrayList(EXTRA_FLAGS, pFlags);
                }
            }
        }

        private void handleSetFlag(Bundle extras) {
            if (extras == null) {
                Log.w(TAG, "No extras");
                return;
            }
            String name = extras.getString(EXTRA_NAME);
            if (name == null || name.isEmpty()) {
                Log.w(TAG, "NAME not set or is empty: " + name);
                return;
            }

            if (!mAllFlags.containsKey(name)) {
                Log.w(TAG, "Tried to set unknown name: " + name);
                return;
            }
            Flag<?> flag = mAllFlags.get(name);

            if (!extras.containsKey(EXTRA_VALUE)) {
                eraseFlag(flag);
                return;
            }

            Object value = extras.get(EXTRA_VALUE);

            try {
                if (value instanceof Boolean) {
                    setBooleanFlagInternal(flag, (Boolean) value);
                } else if (value instanceof String) {
                    setStringFlagInternal(flag, (String) value);
                } else {
                    throw new IllegalArgumentException("Unknown value type");
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG,
                        "Unable to set " + flag.getId() + " of type " + flag.getClass()
                                + " to value of type " + (value == null ? null : value.getClass()));
            }
        }

        /**
         * Ensures that the data we send to the app reflects the current state of the flags.
         *
         * Also converts an non-parcelable versions of the flags to their parcelable versions.
         */
        @Nullable
        private ParcelableFlag<?> toParcelableFlag(Flag<?> f) {
            boolean enabled;
            boolean teamfood = f.getTeamfood();
            boolean overridden;

            if (f instanceof ReleasedFlag) {
                enabled = isEnabled((ReleasedFlag) f);
                overridden = readBooleanFlagOverride(f.getName()) != null
                            || readBooleanFlagOverride(f.getId()) != null;
            } else if (f instanceof UnreleasedFlag) {
                enabled = isEnabled((UnreleasedFlag) f);
                overridden = readBooleanFlagOverride(f.getName()) != null
                            || readBooleanFlagOverride(f.getId()) != null;
            } else if (f instanceof ResourceBooleanFlag) {
                enabled = isEnabled((ResourceBooleanFlag) f);
                overridden = readBooleanFlagOverride(f.getName()) != null
                            || readBooleanFlagOverride(f.getId()) != null;
            } else if (f instanceof SysPropBooleanFlag) {
                // TODO(b/223379190): Teamfood not supported for sysprop flags yet.
                enabled = isEnabled((SysPropBooleanFlag) f);
                teamfood = false;
                overridden = !mSystemProperties.get(((SysPropBooleanFlag) f).getName()).isEmpty();
            } else {
                // TODO: add support for other flag types.
                Log.w(TAG, "Unsupported Flag Type. Please file a bug.");
                return null;
            }

            if (enabled) {
                return new ReleasedFlag(
                        f.getId(), f.getName(), f.getNamespace(), teamfood, overridden);
            } else {
                return new UnreleasedFlag(
                        f.getId(), f.getName(), f.getNamespace(), teamfood, overridden);
            }
        }
    };

    private void removeFromCache(String name) {
        mBooleanFlagCache.remove(name);
        mStringFlagCache.remove(name);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: true");
        pw.println("booleans: " + mBooleanFlagCache.size());
        mBooleanFlagCache.forEach((key, value) -> pw.println("  sysui_flag_" + key + ": " + value));
        pw.println("Strings: " + mStringFlagCache.size());
        mStringFlagCache.forEach((key, value) -> pw.println("  sysui_flag_" + key
                + ": [length=" + value.length() + "] \"" + value + "\""));
    }

}
