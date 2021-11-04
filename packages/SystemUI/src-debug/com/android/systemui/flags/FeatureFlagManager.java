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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;

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
public class FeatureFlagManager implements FlagReader, FlagWriter, Dumpable {
    private static final String TAG = "SysUIFlags";

    private static final String SYSPROP_PREFIX = "persist.systemui.flag_";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_ID = "id";
    private static final String FIELD_VALUE = "value";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String ACTION_SET_FLAG = "com.android.systemui.action.SET_FLAG";
    private static final String FLAGS_PERMISSION = "com.android.systemui.permission.FLAGS";
    private final SystemPropertiesHelper mSystemPropertiesHelper;

    private final Map<Integer, Boolean> mBooleanFlagCache = new HashMap<>();

    @Inject
    public FeatureFlagManager(SystemPropertiesHelper systemPropertiesHelper, Context context,
            DumpManager dumpManager) {
        mSystemPropertiesHelper = systemPropertiesHelper;

        IntentFilter filter = new IntentFilter(ACTION_SET_FLAG);
        context.registerReceiver(mReceiver, filter, FLAGS_PERMISSION, null);
        dumpManager.registerDumpable(TAG, this);
    }

    /** Return a {@link BooleanFlag}'s value. */
    @Override
    public boolean isEnabled(int id, boolean defaultValue) {
        if (!mBooleanFlagCache.containsKey(id)) {
            Boolean result = isEnabledInternal(id);
            mBooleanFlagCache.put(id, result == null ? defaultValue : result);
        }

        return mBooleanFlagCache.get(id);
    }

    /** Returns the stored value or null if not set. */
    private Boolean isEnabledInternal(int id) {
        String data = mSystemPropertiesHelper.get(keyToSysPropKey(id));
        if (data.isEmpty()) {
            return null;
        }
        JSONObject json;
        try {
            json = new JSONObject(data);
            if (!assertType(json, TYPE_BOOLEAN)) {
                return null;
            }

            return json.getBoolean(FIELD_VALUE);
        } catch (JSONException e) {
            eraseInternal(id);  // Don't restart SystemUI in this case.
        }
        return null;
    }

    /** Set whether a given {@link BooleanFlag} is enabled or not. */
    @Override
    public void setEnabled(int id, boolean value) {
        Boolean currentValue = isEnabledInternal(id);
        if (currentValue != null && currentValue == value) {
            return;
        }

        JSONObject json = new JSONObject();
        try {
            json.put(FIELD_TYPE, TYPE_BOOLEAN);
            json.put(FIELD_VALUE, value);
            mSystemPropertiesHelper.set(keyToSysPropKey(id), json.toString());
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
        mSystemPropertiesHelper.set(keyToSysPropKey(id), "");
        Log.i(TAG, "Erase id " + id);
    }

    @Override
    public void addListener(Listener run) {}

    @Override
    public void removeListener(Listener run) {}

    private void restartSystemUI() {
        Log.i(TAG, "Restarting SystemUI");
        // SysUI starts back when up exited. Is there a better way to do this?
        System.exit(0);
    }

    private static String keyToSysPropKey(int key) {
        return SYSPROP_PREFIX + key;
    }

    private static boolean assertType(JSONObject json, String type) {
        try {
            return json.getString(FIELD_TYPE).equals(TYPE_BOOLEAN);
        } catch (JSONException e) {
            return false;
        }
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
