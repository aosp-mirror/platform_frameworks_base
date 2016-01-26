/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.State.MODE_UNKNOWN;
import static com.android.internal.util.Preconditions.checkArgument;

import android.content.Context;
import android.preference.PreferenceManager;

import com.android.documentsui.State.ViewMode;
import com.android.documentsui.model.RootInfo;

public class LocalPreferences {
    private static final String KEY_ADVANCED_DEVICES = "advancedDevices";
    private static final String KEY_FILE_SIZE = "fileSize";
    private static final String ROOT_VIEW_MODE_PREFIX = "rootViewMode-";

    public static boolean getDisplayAdvancedDevices(Context context) {
        boolean defaultAdvanced = context.getResources()
                .getBoolean(R.bool.config_defaultAdvancedDevices);
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_ADVANCED_DEVICES, defaultAdvanced);
    }

    public static boolean getDisplayFileSize(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_FILE_SIZE, false);
    }

    public static @ViewMode int getViewMode(
            Context context, RootInfo root, @ViewMode int fallback) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(createKey(root), fallback);
    }

    public static void setDisplayAdvancedDevices(Context context, boolean display) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_ADVANCED_DEVICES, display).apply();
    }

    public static void setDisplayFileSize(Context context, boolean display) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(KEY_FILE_SIZE, display).apply();
    }

    public static void setViewMode(Context context, RootInfo root, @ViewMode int viewMode) {
        checkArgument(viewMode != MODE_UNKNOWN);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(createKey(root), viewMode).apply();
    }

    private static String createKey(RootInfo root) {
        return ROOT_VIEW_MODE_PREFIX + root.authority + root.rootId;
    }
}
