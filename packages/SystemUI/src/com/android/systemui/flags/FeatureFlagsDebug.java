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
import static com.android.systemui.flags.FlagManager.EXTRA_ID;
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
import com.android.systemui.util.settings.SecureSettings;

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
    private final SecureSettings mSecureSettings;
    private final Resources mResources;
    private final SystemPropertiesHelper mSystemProperties;
    private final ServerFlagReader mServerFlagReader;
    private final Map<Integer, Flag<?>> mAllFlags;
    private final Map<Integer, Boolean> mBooleanFlagCache = new TreeMap<>();
    private final Map<Integer, String> mStringFlagCache = new TreeMap<>();
    private final Map<Integer, Integer> mIntFlagCache = new TreeMap<>();
    private final Restarter mRestarter;

    private final ServerFlagReader.ChangeListener mOnPropertiesChanged =
            new ServerFlagReader.ChangeListener() {
                @Override
                public void onChange() {
                    mRestarter.restartSystemUI();
                }
            };

    @Inject
    public FeatureFlagsDebug(
            FlagManager flagManager,
            Context context,
            SecureSettings secureSettings,
            SystemPropertiesHelper systemProperties,
            @Main Resources resources,
            ServerFlagReader serverFlagReader,
            @Named(ALL_FLAGS) Map<Integer, Flag<?>> allFlags,
            Restarter restarter) {
        mFlagManager = flagManager;
        mContext = context;
        mSecureSettings = secureSettings;
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
        mFlagManager.setOnSettingsChangedAction(this::restartSystemUI);
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
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(id,
                    readBooleanFlagInternal(flag, flag.getDefault()));
        }

        return mBooleanFlagCache.get(id);
    }

    @Override
    public boolean isEnabled(@NonNull ResourceBooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(id,
                    readBooleanFlagInternal(flag, mResources.getBoolean(flag.getResourceId())));
        }

        return mBooleanFlagCache.get(id);
    }

    @Override
    public boolean isEnabled(@NonNull SysPropBooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            // Use #readFlagValue to get the default. That will allow it to fall through to
            // teamfood if need be.
            mBooleanFlagCache.put(
                    id,
                    mSystemProperties.getBoolean(
                            flag.getName(),
                            readBooleanFlagInternal(flag, flag.getDefault())));
        }

        return mBooleanFlagCache.get(id);
    }

    @NonNull
    @Override
    public String getString(@NonNull StringFlag flag) {
        int id = flag.getId();
        if (!mStringFlagCache.containsKey(id)) {
            mStringFlagCache.put(id,
                    readFlagValueInternal(id, flag.getDefault(), StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(id);
    }

    @NonNull
    @Override
    public String getString(@NonNull ResourceStringFlag flag) {
        int id = flag.getId();
        if (!mStringFlagCache.containsKey(id)) {
            mStringFlagCache.put(id,
                    readFlagValueInternal(id, mResources.getString(flag.getResourceId()),
                            StringFlagSerializer.INSTANCE));
        }

        return mStringFlagCache.get(id);
    }


    @NonNull
    @Override
    public int getInt(@NonNull IntFlag flag) {
        int id = flag.getId();
        if (!mIntFlagCache.containsKey(id)) {
            mIntFlagCache.put(id,
                    readFlagValueInternal(id, flag.getDefault(), IntFlagSerializer.INSTANCE));
        }

        return mIntFlagCache.get(id);
    }

    @NonNull
    @Override
    public int getInt(@NonNull ResourceIntFlag flag) {
        int id = flag.getId();
        if (!mIntFlagCache.containsKey(id)) {
            mIntFlagCache.put(id,
                    readFlagValueInternal(id, mResources.getInteger(flag.getResourceId()),
                            IntFlagSerializer.INSTANCE));
        }

        return mIntFlagCache.get(id);
    }

    /** Specific override for Boolean flags that checks against the teamfood list.*/
    private boolean readBooleanFlagInternal(Flag<Boolean> flag, boolean defaultValue) {
        Boolean result = readBooleanFlagOverride(flag.getId());
        boolean hasServerOverride = mServerFlagReader.hasOverride(
                flag.getNamespace(), flag.getName());

        // Only check for teamfood if the default is false
        // and there is no server override.
        if (!hasServerOverride
                && !defaultValue
                && result == null
                && flag.getId() != Flags.TEAMFOOD.getId()
                && flag.getTeamfood()) {
            return isEnabled(Flags.TEAMFOOD);
        }

        return result == null ? mServerFlagReader.readServerOverride(
                flag.getNamespace(), flag.getName(), defaultValue) : result;
    }

    private Boolean readBooleanFlagOverride(int id) {
        return readFlagValueInternal(id, BooleanFlagSerializer.INSTANCE);
    }

    @NonNull
    private <T> T readFlagValueInternal(
            int id, @NonNull T defaultValue, FlagSerializer<T> serializer) {
        requireNonNull(defaultValue, "defaultValue");
        T result = readFlagValueInternal(id, serializer);
        return result == null ? defaultValue : result;
    }


    /** Returns the stored value or null if not set. */
    @Nullable
    private <T> T readFlagValueInternal(int id, FlagSerializer<T> serializer) {
        try {
            return mFlagManager.readFlagValue(id, serializer);
        } catch (Exception e) {
            eraseInternal(id);
        }
        return null;
    }

    private <T> void setFlagValue(int id, @NonNull T value, FlagSerializer<T> serializer) {
        requireNonNull(value, "Cannot set a null value");
        T currentValue = readFlagValueInternal(id, serializer);
        if (Objects.equals(currentValue, value)) {
            Log.i(TAG, "Flag id " + id + " is already " + value);
            return;
        }
        final String data = serializer.toSettingsData(value);
        if (data == null) {
            Log.w(TAG, "Failed to set id " + id + " to " + value);
            return;
        }
        mSecureSettings.putStringForUser(mFlagManager.idToSettingsKey(id), data,
                UserHandle.USER_CURRENT);
        Log.i(TAG, "Set id " + id + " to " + value);
        removeFromCache(id);
        mFlagManager.dispatchListenersAndMaybeRestart(id, this::restartSystemUI);
    }

    <T> void eraseFlag(Flag<T> flag) {
        if (flag instanceof SysPropFlag) {
            mSystemProperties.erase(((SysPropFlag<T>) flag).getName());
            dispatchListenersAndMaybeRestart(flag.getId(), this::restartAndroid);
        } else {
            eraseFlag(flag.getId());
        }
    }

    /** Erase a flag's overridden value if there is one. */
    private void eraseFlag(int id) {
        eraseInternal(id);
        removeFromCache(id);
        dispatchListenersAndMaybeRestart(id, this::restartSystemUI);
    }

    private void dispatchListenersAndMaybeRestart(int id, Consumer<Boolean> restartAction) {
        mFlagManager.dispatchListenersAndMaybeRestart(id, restartAction);
    }

    /** Works just like {@link #eraseFlag(int)} except that it doesn't restart SystemUI. */
    private void eraseInternal(int id) {
        // We can't actually "erase" things from sysprops, but we can set them to empty!
        mSecureSettings.putStringForUser(mFlagManager.idToSettingsKey(id), "",
                UserHandle.USER_CURRENT);
        Log.i(TAG, "Erase id " + id);
    }

    @Override
    public void addListener(@NonNull Flag<?> flag, @NonNull Listener listener) {
        mFlagManager.addListener(flag, listener);
    }

    @Override
    public void removeListener(@NonNull Listener listener) {
        mFlagManager.removeListener(listener);
    }

    private void restartSystemUI(boolean requestSuppress) {
        if (requestSuppress) {
            Log.i(TAG, "SystemUI Restart Suppressed");
            return;
        }
        mRestarter.restartSystemUI();
    }

    private void restartAndroid(boolean requestSuppress) {
        if (requestSuppress) {
            Log.i(TAG, "Android Restart Suppressed");
            return;
        }
        mRestarter.restartAndroid();
    }

    void setBooleanFlagInternal(Flag<?> flag, boolean value) {
        if (flag instanceof BooleanFlag) {
            setFlagValue(flag.getId(), value, BooleanFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceBooleanFlag) {
            setFlagValue(flag.getId(), value, BooleanFlagSerializer.INSTANCE);
        } else if (flag instanceof SysPropBooleanFlag) {
            // Store SysProp flags in SystemProperties where they can read by outside parties.
            mSystemProperties.setBoolean(((SysPropBooleanFlag) flag).getName(), value);
            dispatchListenersAndMaybeRestart(flag.getId(),
                    FeatureFlagsDebug.this::restartAndroid);
        } else {
            throw new IllegalArgumentException("Unknown flag type");
        }
    }

    void setStringFlagInternal(Flag<?> flag, String value) {
        if (flag instanceof StringFlag) {
            setFlagValue(flag.getId(), value, StringFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceStringFlag) {
            setFlagValue(flag.getId(), value, StringFlagSerializer.INSTANCE);
        } else {
            throw new IllegalArgumentException("Unknown flag type");
        }
    }

    void setIntFlagInternal(Flag<?> flag, int value) {
        if (flag instanceof IntFlag) {
            setFlagValue(flag.getId(), value, IntFlagSerializer.INSTANCE);
        } else if (flag instanceof ResourceIntFlag) {
            setFlagValue(flag.getId(), value, IntFlagSerializer.INSTANCE);
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
            int id = extras.getInt(EXTRA_ID);
            if (id <= 0) {
                Log.w(TAG, "ID not set or less than  or equal to 0: " + id);
                return;
            }

            if (!mAllFlags.containsKey(id)) {
                Log.w(TAG, "Tried to set unknown id: " + id);
                return;
            }
            Flag<?> flag = mAllFlags.get(id);

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
                overridden = readBooleanFlagOverride(f.getId()) != null;
            } else if (f instanceof UnreleasedFlag) {
                enabled = isEnabled((UnreleasedFlag) f);
                overridden = readBooleanFlagOverride(f.getId()) != null;
            } else if (f instanceof ResourceBooleanFlag) {
                enabled = isEnabled((ResourceBooleanFlag) f);
                overridden = readBooleanFlagOverride(f.getId()) != null;
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

    private void removeFromCache(int id) {
        mBooleanFlagCache.remove(id);
        mStringFlagCache.remove(id);
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
