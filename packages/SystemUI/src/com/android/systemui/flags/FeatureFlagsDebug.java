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
import static com.android.systemui.flags.FlagManager.FIELD_FLAGS;
import static com.android.systemui.flags.FlagManager.FIELD_ID;
import static com.android.systemui.flags.FlagManager.FIELD_VALUE;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.settings.SecureSettings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

/**
 * Concrete implementation of the a Flag manager that returns default values for debug builds
 *
 * Flags can be set (or unset) via the following adb command:
 *
 *   adb shell am broadcast -a com.android.systemui.action.SET_FLAG --ei id <id> [--ez value <0|1>]
 *
 * To restore a flag back to its default, leave the `--ez value <0|1>` off of the command.
 */
@SysUISingleton
public class FeatureFlagsDebug implements FeatureFlags, Dumpable {
    private static final String TAG = "SysUIFlags";

    private final FlagManager mFlagManager;
    private final SecureSettings mSecureSettings;
    private final Resources mResources;
    private final Map<Integer, Boolean> mBooleanFlagCache = new HashMap<>();

    @Inject
    public FeatureFlagsDebug(
            FlagManager flagManager,
            Context context,
            SecureSettings secureSettings,
            @Main Resources resources,
            DumpManager dumpManager) {
        mFlagManager = flagManager;
        mSecureSettings = secureSettings;
        mResources = resources;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_FLAG);
        filter.addAction(ACTION_GET_FLAGS);
        context.registerReceiver(mReceiver, filter, null, null);
        dumpManager.registerDumpable(TAG, this);
    }

    @Override
    public boolean isEnabled(BooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(id, isEnabled(id, flag.getDefault()));
        }

        return mBooleanFlagCache.get(id);
    }

    @Override
    public boolean isEnabled(ResourceBooleanFlag flag) {
        int id = flag.getId();
        if (!mBooleanFlagCache.containsKey(id)) {
            mBooleanFlagCache.put(
                    id, isEnabled(id, mResources.getBoolean(flag.getResourceId())));
        }

        return mBooleanFlagCache.get(id);
    }

    /** Return a {@link BooleanFlag}'s value. */
    @Override
    public boolean isEnabled(int id, boolean defaultValue) {
        Boolean result = isEnabledInternal(id);
        return result == null ? defaultValue : result;
    }


    /** Returns the stored value or null if not set. */
    private Boolean isEnabledInternal(int id) {
        try {
            return mFlagManager.isEnabled(id);
        } catch (Exception e) {
            eraseInternal(id);
        }
        return null;
    }

    /** Set whether a given {@link BooleanFlag} is enabled or not. */
    public void setEnabled(int id, boolean value) {
        Boolean currentValue = isEnabledInternal(id);
        if (currentValue != null && currentValue == value) {
            return;
        }

        JSONObject json = new JSONObject();
        try {
            json.put(FlagManager.FIELD_TYPE, FlagManager.TYPE_BOOLEAN);
            json.put(FIELD_VALUE, value);
            mSecureSettings.putString(mFlagManager.keyToSettingsPrefix(id), json.toString());
            Log.i(TAG, "Set id " + id + " to " + value);
            restartSystemUI();
        } catch (JSONException e) {
            // no-op
        }
    }

    /** Erase a flag's overridden value if there is one. */
    public void eraseFlag(int id) {
        eraseInternal(id);
        restartSystemUI();
    }

    /** Works just like {@link #eraseFlag(int)} except that it doesn't restart SystemUI. */
    private void eraseInternal(int id) {
        // We can't actually "erase" things from sysprops, but we can set them to empty!
        mSecureSettings.putString(mFlagManager.keyToSettingsPrefix(id), "");
        Log.i(TAG, "Erase id " + id);
    }

    @Override
    public void addListener(Listener run) {
        mFlagManager.addListener(run);
    }

    @Override
    public void removeListener(Listener run) {
        mFlagManager.removeListener(run);
    }

    private void restartSystemUI() {
        Log.i(TAG, "Restarting SystemUI");
        // SysUI starts back when up exited. Is there a better way to do this?
        System.exit(0);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_SET_FLAG.equals(action)) {
                handleSetFlag(intent.getExtras());
            } else if (ACTION_GET_FLAGS.equals(action)) {
                Map<Integer, Flag<?>> knownFlagMap = Flags.collectFlags();
                ArrayList<Flag<?>> flags = new ArrayList<>(knownFlagMap.values());

                // Convert all flags to parcelable flags.
                ArrayList<ParcelableFlag<?>> pFlags = new ArrayList<>();
                for (Flag<?> f : flags) {
                    ParcelableFlag<?> pf = toParcelableFlag(f);
                    if (pf != null) {
                        pFlags.add(pf);
                    }
                }

                Bundle extras =  getResultExtras(true);
                if (extras != null) {
                    extras.putParcelableArrayList(FIELD_FLAGS, pFlags);
                }
            }
        }

        private void handleSetFlag(Bundle extras) {
            int id = extras.getInt(FIELD_ID);
            if (id <= 0) {
                Log.w(TAG, "ID not set or less than  or equal to 0: " + id);
                return;
            }

            Map<Integer, Flag<?>> flagMap = Flags.collectFlags();
            if (!flagMap.containsKey(id)) {
                Log.w(TAG, "Tried to set unknown id: " + id);
                return;
            }
            Flag<?> flag = flagMap.get(id);

            if (!extras.containsKey(FIELD_VALUE)) {
                eraseFlag(id);
                return;
            }

            if (flag instanceof BooleanFlag) {
                setEnabled(id, extras.getBoolean(FIELD_VALUE));
            }
        }

        /**
         * Ensures that the data we send to the app reflects the current state of the flags.
         *
         * Also converts an non-parcelable versions of the flags to their parcelable versions.
         */
        @Nullable
        private ParcelableFlag<?> toParcelableFlag(Flag<?> f) {
            if (f instanceof BooleanFlag) {
                return new BooleanFlag(f.getId(), isEnabled((BooleanFlag) f));
            }
            if (f instanceof ResourceBooleanFlag) {
                return new BooleanFlag(f.getId(), isEnabled((ResourceBooleanFlag) f));
            }

            // TODO: add support for other flag types.
            Log.w(TAG, "Unsupported Flag Type. Please file a bug.");
            return null;
        }
    };

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("can override: true");
        ArrayList<String> flagStrings = new ArrayList<>(mBooleanFlagCache.size());
        for (Map.Entry<Integer, Boolean> entry : mBooleanFlagCache.entrySet()) {
            flagStrings.add("  sysui_flag_" + entry.getKey() + ": " + entry.getValue());
        }
        flagStrings.sort(String.CASE_INSENSITIVE_ORDER);
        for (String flagString : flagStrings) {
            pw.println(flagString);
        }
    }
}
